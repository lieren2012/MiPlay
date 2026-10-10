package org.carplaytocarlife.iccoa;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Tells the network this phone is a CarPlay controller.
 *
 * The reference answers queries for {@code _carplay-ctrl._tcp} with its own
 * instance, host, port and address, and repeats the announcement every two
 * seconds ("control query answered peer=... unicast=..." in its log). Browsing
 * for the car's AirPlay service is the other half and lives in
 * {@link AirplayBootstrap}; this is the half that lets the car find the phone.
 *
 * The multicast socket is best effort: Android's own responder often holds port
 * 5353, so the announcement is sent from an ephemeral port when the bind fails,
 * and either way the socket gets a multicast lock so the announcements are not
 * filtered out while the screen is off.
 */
public final class CarplayAdvertiser implements Runnable {

    /** The service the reference advertises itself under. */
    public static final String SERVICE_TYPE = "_carplay-ctrl._tcp.local";
    /** How often the announcement goes out; the reference uses two seconds. */
    public static final long ANNOUNCE_INTERVAL_MILLIS = 2000;
    /** One log line every this many announcements, so the log stays readable. */
    public static final long ANNOUNCE_LOG_EVERY = 15;

    public interface Listener {
        void onLog(String message);
    }

    private final Context context;
    private final Listener listener;
    private final int port;
    private final String instance;
    private final String host;

    /**
     * A DatagramSocket cannot join a multicast group on Android: javac accepts
     * the overload when compiling against a desktop JDK, but ART has no such
     * method and the old implementation crashed as soon as the service started.
     */
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    private Thread thread;
    private volatile boolean running;
    private long answered;
    private long announced;

    public CarplayAdvertiser(Context context, int port, String id, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.port = port;
        this.instance = Mdns.instanceName(id);
        this.host = Mdns.hostName(id);
    }

    public String instance() {
        return instance;
    }

    public String host() {
        return host;
    }

    public long answered() {
        return answered;
    }

    public long announced() {
        return announced;
    }

    /** True when a question is about this phone's CarPlay service or its host. */
    public boolean answers(Mdns.Question question) {
        return question != null && answersName(question.name, instance, host);
    }

    /**
     * The decision itself, without an Android context, so it can be tested.
     *
     * A question naming the service, this phone's instance or its host is ours;
     * the trailing dot and the case are conventions, not meaning.
     */
    public static boolean answersName(String name, String instance, String host) {
        if (name == null) {
            return false;
        }
        String asked = trim(name);
        return asked.equalsIgnoreCase(trim(SERVICE_TYPE))
                || asked.equalsIgnoreCase(trim(instance))
                || asked.equalsIgnoreCase(trim(host));
    }

    /** True when the question asks for its answer to be sent straight back. */
    public static boolean unicastWanted(Mdns.Question question) {
        return question != null && (question.qclass & 0x8000) != 0;
    }

    private static String trim(String name) {
        String out = name;
        while (out.endsWith(".")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public void start() {
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Mdns.PORT));
        } catch (Exception taken) {
            try {
                socket = new MulticastSocket();
            } catch (Exception fatal) {
                log("cannot open an mDNS socket: " + fatal);
                return;
            }
            log("port " + Mdns.PORT + " is taken (" + taken + "); announcing from "
                    + socket.getLocalPort() + " instead, which the car may ignore");
        }
        try {
            socket.setSoTimeout(500);
        } catch (Exception error) {
            log("cannot set the mDNS socket timeout: " + error);
        }
        joinMulticast();
        acquireLock();
        running = true;
        thread = new Thread(this, "carplay-advertiser");
        thread.setDaemon(true);
        thread.start();
        log("advertising " + instance + " as " + host + ":" + port
                + " every " + (ANNOUNCE_INTERVAL_MILLIS / 1000) + "s");
    }

    private void joinMulticast() {
        try {
            InetAddress group = InetAddress.getByName(Mdns.ADDRESS);
            InetSocketAddress address = new InetSocketAddress(group, Mdns.PORT);
            for (NetworkInterface network : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) {
                    continue;
                }
                try {
                    socket.joinGroup(address, network);
                    log("joined " + Mdns.ADDRESS + " on " + network.getName());
                } catch (Exception error) {
                    // some interfaces refuse; the ones that work are enough
                }
            }
        } catch (Exception error) {
            log("cannot join the mDNS group: " + error);
        }
    }

    private void acquireLock() {
        Object service = context.getSystemService(Context.WIFI_SERVICE);
        if (!(service instanceof WifiManager)) {
            return;
        }
        try {
            lock = ((WifiManager) service).createMulticastLock("carplay-advertiser");
            lock.setReferenceCounted(true);
            lock.acquire();
        } catch (Throwable error) {
            log("no multicast lock (" + error + "); announcements may be filtered");
            lock = null;
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[9000];
        // The first announcement must also run off the main thread. Android
        // throws NetworkOnMainThreadException for DatagramSocket.send(), and
        // the old eager call made the initial advertisement disappear even
        // though later timer announcements worked.
        announce();
        long lastAnnounce = System.currentTimeMillis();
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (java.net.SocketTimeoutException idle) {
                packet = null;
            } catch (Exception error) {
                if (running) {
                    log("the mDNS socket stopped: " + error);
                }
                return;
            }
            if (packet != null) {
                handle(packet);
            }
            long now = System.currentTimeMillis();
            if (now - lastAnnounce >= ANNOUNCE_INTERVAL_MILLIS) {
                lastAnnounce = now;
                announce();
            }
        }
    }

    /** Answers one query, unicast when it asked for that and multicast otherwise. */
    private void handle(DatagramPacket packet) {
        Mdns.Message message;
        try {
            message = Mdns.parse(java.util.Arrays.copyOfRange(packet.getData(),
                    packet.getOffset(), packet.getOffset() + packet.getLength()));
        } catch (RuntimeException malformed) {
            return;
        }
        if (message.response) {
            return;
        }
        for (Mdns.Question question : message.questions) {
            if (!answers(question)) {
                continue;
            }
            boolean unicast = unicastWanted(question);
            if (send(question, unicast ? packet.getAddress() : group(),
                    unicast ? packet.getPort() : Mdns.PORT, "answer")) {
                answered++;
                log("control query answered peer=" + packet.getAddress().getHostAddress()
                        + ":" + packet.getPort() + " unicast=" + unicast
                        + " question=" + question.name);
            }
        }
    }

    /** Repeats the announcement, which is a response nobody asked for. */
    private void announce() {
        boolean sent = send(new Mdns.Question(SERVICE_TYPE, Mdns.TYPE_PTR), group(), Mdns.PORT,
                "announcement");
        if (!sent) {
            return;
        }
        announced++;
        if (announced == 1 || announced % ANNOUNCE_LOG_EVERY == 0) {
            log("advertised " + announced + " time(s) as " + instance + " ("
                    + localIpv4() + ":" + port + ")");
        }
    }

    private boolean send(Mdns.Question question, InetAddress to, int toPort, String what) {
        try {
            byte[] response = Mdns.buildResponse(question, instance, host, port, localIpv4());
            socket.send(new DatagramPacket(response, response.length, to, toPort));
            return true;
        } catch (Exception error) {
            log("cannot send the " + what + ": " + error);
            return false;
        }
    }

    private static InetAddress group() {
        try {
            return InetAddress.getByName(Mdns.ADDRESS);
        } catch (Exception error) {
            throw new IllegalStateException("the mDNS group address is unavailable", error);
        }
    }

    /** The address to advertise: the first private IPv4 this phone has. */
    public static byte[] localIpv4() {
        try {
            for (NetworkInterface network : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getAddress();
                    }
                }
            }
        } catch (Exception error) {
            Logs.add("cannot find a local address to advertise: " + error);
        }
        return new byte[] {0, 0, 0, 0};
    }

    public void stop() {
        running = false;
        Thread running = thread;
        thread = null;
        if (running != null) {
            running.interrupt();
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (lock != null) {
            try {
                lock.release();
            } catch (Throwable ignored) {
                // best effort while shutting down
            }
            lock = null;
        }
        log("stopped advertising (answered " + answered + " quer" + (answered == 1 ? "y" : "ies")
                + ", announced " + announced + " times)");
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        } else {
            Logs.add(message);
        }
    }
}
