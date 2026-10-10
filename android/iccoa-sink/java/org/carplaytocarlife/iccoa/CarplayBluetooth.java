package org.carplaytocarlife.iccoa;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.util.List;
import java.util.UUID;

/**
 * The car's Bluetooth side: the SDP records that make a stock Android phone look
 * like a CarPlay-capable device, and the iAP2 session the car then expects.
 *
 * Evidence for the identity route is in docs/apk-analysis-carwith-carplay.md:
 * the reference CarWith -> CarPlay app publishes its CarPlay identity purely
 * through the records
 * {@code BluetoothServerSocket.listenUsingRfcommWithServiceRecord()} creates -
 * no root, no system change. Its native EIR rewrite is an optional extra; its
 * own log says "actual EIR requires HCI confirmation", so the SDP route is what
 * makes a plain phone work.
 *
 * <pre>
 *   "CarPlay Probe"           -> 00000000-deca-fade-deca-deafdecacaff
 *   "CarPlay Identity Probe"  -> 00000000-deca-fade-deca-deafdecacafe
 *   outbound to the accessory -> 2d8d2466-e14d-451c-88bc-7301abea291a
 * </pre>
 *
 * Once a link is up the conversation is iAP2 ({@link CarplayBootstrap}); what
 * comes back out of it is the car's Wi-Fi credentials and the AirPlay endpoint.
 */
public final class CarplayBluetooth {

    public static final UUID PHONE_UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff");
    public static final UUID PHONE_EIR_UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe");
    public static final UUID ACCESSORY_UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a");

    /**
     * How long to let the car open the link before opening it ourselves.
     *
     * A CarPlay accessory either takes the lead or waits for the phone; four
     * seconds is long enough for the former and short enough not to stall the
     * latter. The reference app allows 25s in its "accessory initiates" round,
     * but it knows which car it is talking to and we do not.
     */
    public static final long SESSION_WAIT_MILLIS = 4000;

    /**
     * How long to wait before dialling the car again.
     *
     * The reference retries its RFCOMM connection in rounds and logs
     * "Next-round RFCOMM connect timeout=20000ms; closing socket", so twenty
     * seconds is its cadence and the one this side follows.
     */
    public static final long CONNECT_RETRY_DELAY_MILLIS = 20000;
    /** How many times a failed outbound connect is repeated. */
    public static final int MAX_CONNECT_ATTEMPTS = 3;

    /** True when another outbound attempt is worth making. */
    public static boolean shouldRetryConnect(int attempts) {
        return attempts < MAX_CONNECT_ATTEMPTS;
    }

    private final BluetoothAdapter adapter;
    private final String deviceUuid;
    private BluetoothServerSocket phoneSocket;
    private BluetoothServerSocket identitySocket;
    private volatile boolean running;
    private Listener listener;
    private Iap2Link link;
    private CarplayBootstrap bootstrap;
    /**
     * The links the car opened.
     *
     * Closing the listening sockets is not enough: the accepted socket is what
     * the reader thread blocks on, so without this the thread stays in read()
     * for the life of the process and the car still sees the link as up.
     */
    private final java.util.List<BluetoothSocket> peers = new java.util.ArrayList<>();

    public interface Listener {
        void onLog(String message);

        void onPeerConnected(BluetoothSocket socket, String description);

        /** The car handed over its Wi-Fi credentials and the AirPlay endpoint. */
        void onWirelessConfig(CarWifiConfig config);
    }

    public CarplayBluetooth(BluetoothAdapter adapter, String deviceUuid) {
        this.adapter = adapter;
        this.deviceUuid = deviceUuid;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Sends one control message on the Bluetooth link.
     *
     * The AirPlay tunnel is preferred once the session is up; this is the
     * fallback, and it is also what carries the bootstrap before Wi-Fi exists.
     */
    public boolean sendControl(int messageId, List<Iap2.Parameter> parameters) {
        Iap2Link current = link;
        if (current == null || !current.sessionOpen()) {
            return false;
        }
        try {
            current.sendControl(messageId, parameters);
            return true;
        } catch (Exception error) {
            log("Bluetooth send of 0x" + Integer.toHexString(messageId) + " failed: " + error);
            return false;
        }
    }

    /** The iAP2 session, once a link has been opened. */
    public CarplayBootstrap bootstrap() {
        return bootstrap;
    }

    /**
     * Publishes both SDP records and starts accepting the accessory's inbound
     * connection. This is the step that makes a stock phone visible as a
     * CarPlay-capable device.
     */
    public boolean start() {
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth is off; the car cannot see this phone as a CarPlay device");
            return false;
        }
        try {
            phoneSocket = adapter.listenUsingRfcommWithServiceRecord("CarPlay Probe", PHONE_UUID);
            identitySocket = adapter.listenUsingRfcommWithServiceRecord(
                    "CarPlay Identity Probe", PHONE_EIR_UUID);
            running = true;
            log("SDP registered: CarPlay Probe " + PHONE_UUID
                    + " + CarPlay Identity Probe " + PHONE_EIR_UUID);
        } catch (Exception error) {
            log("SDP registration failed: " + error);
            return false;
        }
        acceptLoop(phoneSocket, "phone");
        acceptLoop(identitySocket, "identity");
        return true;
    }

    public void stop() {
        running = false;
        if (link != null) {
            link.stop();
            link = null;
        }
        bootstrap = null;
        closePeers();
        closeQuietly(phoneSocket);
        closeQuietly(identitySocket);
        phoneSocket = null;
        identitySocket = null;
    }

    /** Called by the ACL_CONNECTED receiver: the car is nearby, go talk to it. */
    public void onDeviceConnected(final String address) {
        log("ACL_CONNECTED from " + address + "; opening the iAP2 link");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                int attempts = 1;
                while (running && connectAccessory(address) == null
                        && shouldRetryConnect(attempts)) {
                    attempts++;
                    log("the outbound link did not come up; trying again in "
                            + (CONNECT_RETRY_DELAY_MILLIS / 1000) + "s (attempt " + attempts
                            + " of " + MAX_CONNECT_ATTEMPTS + ")");
                    try {
                        Thread.sleep(CONNECT_RETRY_DELAY_MILLIS);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
                if (running) {
                    log("the outbound link gave up after " + attempts + " attempt(s); "
                            + "an inbound connection from the car still works");
                }
            }
        }, "carplay-accessory");
        thread.setDaemon(true);
        thread.start();
    }

    /** Connects out to the accessory, the direction the reference app also uses. */
    public BluetoothSocket connectAccessory(String address) {
        if (adapter == null || address == null) {
            return null;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(ACCESSORY_UUID);
            adapter.cancelDiscovery();
            socket.connect();
            log("outbound accessory RFCOMM connected to " + address);
            if (listener != null) {
                listener.onPeerConnected(socket, "accessory");
            }
            startIap2(socket, "accessory", address);
            return socket;
        } catch (Exception error) {
            log("outbound accessory RFCOMM failed: " + error
                    + " (an inbound connection from the car still works)");
            return null;
        }
    }

    /**
     * Runs the iAP2 session on a connected socket.
     *
     * Both directions are handled the same way: whoever speaks first, the link
     * frames and the session negotiation are symmetric. This side sends its
     * identification frame up front, and answers the car's if it came first.
     */
    /** Closes every accepted link, which is what wakes the reader threads. */
    private void closePeers() {
        synchronized (peers) {
            if (!peers.isEmpty()) {
                log("closing " + peers.size() + " open link(s) to the car");
            }
            for (BluetoothSocket socket : peers) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // best effort while shutting down
                }
            }
            peers.clear();
        }
    }

    private void startIap2(final BluetoothSocket socket, final String label,
            final String remoteAddress) {
        synchronized (peers) {
            peers.add(socket);
        }
        try {
            final Iap2Link[] linkRef = new Iap2Link[1];
            final CarplayBootstrap[] bootstrapRef = new CarplayBootstrap[1];
            final CarplayBootstrap.Sender sender = new CarplayBootstrap.Sender() {
                @Override
                public void send(int messageId, List<Iap2.Parameter> parameters) {
                    Iap2Link current = linkRef[0];
                    if (current == null) {
                        log("no link yet; dropping 0x" + Integer.toHexString(messageId));
                        return;
                    }
                    try {
                        current.sendControl(messageId, parameters);
                    } catch (Exception error) {
                        log("cannot send 0x" + Integer.toHexString(messageId) + ": " + error);
                    }
                }
            };

            Iap2Link created = new Iap2Link(socket.getInputStream(),
                    socket.getOutputStream(), new Iap2Link.Listener() {
                        @Override
                        public void onLog(String message) {
                            log(message);
                        }

                        @Override
                        public void onFrame(Iap2.Frame frame) {
                            // the link already logs every frame in full
                        }

                        @Override
                        public void onControl(Iap2.Message message) {
                            if (bootstrapRef[0] != null) {
                                bootstrapRef[0].receive(message, sender);
                            }
                        }

                        @Override
                        public void onSession(int session) {
                            log("iAP2 session " + session + " open; starting the phone handshake");
                            if (bootstrapRef[0] != null) {
                                bootstrapRef[0].begin(sender);
                            }
                        }
                    });
            linkRef[0] = created;
            bootstrapRef[0] = new CarplayBootstrap("CarPlay Android",
                    deviceUuid == null ? "000000000000" : deviceUuid,
                    remoteAddress == null ? "bluetooth" : remoteAddress,
                    new CarplayBootstrap.Listener() {
                        @Override
                        public void onLog(String message) {
                            log(message);
                        }

                        @Override
                        public void onConfig(CarWifiConfig config) {
                            if (listener != null) {
                                listener.onWirelessConfig(config);
                            }
                        }

                        @Override
                        public void onReady() {
                            log("iAP2 bootstrap ready; waiting for the car's Wi-Fi configuration");
                        }
                    });
            link = created;
            bootstrap = bootstrapRef[0];
            created.start();
            // give the car the first word, the way an accessory that leads does,
            // and open the link ourselves if it stays quiet
            created.openWhenPeerSilent(Iap2Link.SYNC_PAYLOAD, SESSION_WAIT_MILLIS);
            log("iAP2 " + label + " link open; waiting up to " + SESSION_WAIT_MILLIS
                    + "ms for the car's identification");
        } catch (Exception error) {
            log("cannot open the iAP2 link on " + label + ": " + error);
        }
    }

    /** Publishes a name the car is more likely to recognise. */
    public boolean setName(String name) {
        if (adapter == null || name == null) {
            return false;
        }
        try {
            String current = adapter.getName();
            if (name.equals(current)) {
                log("Bluetooth adapter name already " + name);
                return true;
            }
            boolean ok = adapter.setName(name);
            log("Bluetooth adapter name " + current + " -> " + name + " (ok=" + ok + ")");
            return ok;
        } catch (Exception error) {
            log("cannot set Bluetooth adapter name: " + error);
            return false;
        }
    }

    private void acceptLoop(final BluetoothServerSocket server, final String label) {
        if (server == null) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        BluetoothSocket socket = server.accept();
                        String address = socket.getRemoteDevice().getAddress();
                        log("inbound " + label + " RFCOMM from " + address);
                        if (listener != null) {
                            listener.onPeerConnected(socket, label);
                        }
                        startIap2(socket, label, address);
                    } catch (Exception error) {
                        if (running) {
                            log("inbound " + label + " accept ended: " + error);
                        }
                        return;
                    }
                }
            }
        }, "carplay-bt-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(BluetoothServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
            // best effort while shutting down
        }
    }

    public static String hex(byte[] data, int length) {
        return Logs.hex(data, length);
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        } else {
            Logs.add(message);
        }
    }
}
