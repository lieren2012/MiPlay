package org.carplaytocarlife.iccoa;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * The AirPlay control channel: the receiver connects to the port this sender
 * announced and sends control messages, including the HID reports behind touch
 * input.
 *
 * The container is the same {@code 40 40} framing as iAP2, so the control
 * decoder is reused; each parameter is logged, and any parameter whose value
 * looks like our six-byte touch report is decoded with {@link HidTouch}.
 */
public final class ControlChannel implements Runnable {

    public interface Listener {
        void onLog(String message);

        void onTouch(HidTouch.Touch touch);

        void onMessage(Iap2.Message message);
    }

    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;
    private long messages;
    private long touches;

    public ControlChannel(Listener listener) {
        this.listener = listener;
    }

    public void start() throws java.io.IOException {
        server = new ServerSocket(0);
        running = true;
        Thread thread = new Thread(this, "airplay-control");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server == null ? 0 : server.getLocalPort();
    }

    public long messages() {
        return messages;
    }

    public long touches() {
        return touches;
    }

    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    @Override
    public void run() {
        while (running) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (java.io.IOException error) {
                if (running) {
                    log("control accept ended: " + error);
                }
                return;
            }
            log("control channel connected from " + socket.getInetAddress().getHostAddress());
            handle(socket);
        }
    }

    private void handle(Socket socket) {
        Iap2Reader.ControlReader reader = new Iap2Reader.ControlReader();
        byte[] buffer = new byte[4096];
        try (InputStream in = socket.getInputStream()) {
            while (running) {
                int count = in.read(buffer);
                if (count < 0) {
                    break;
                }
                for (Iap2.Message message : reader.feed(buffer, count)) {
                    messages++;
                    log("control message id=0x" + Integer.toHexString(message.command)
                            + " parameters=" + message.parameters.size());
                    for (Iap2.Parameter parameter : message.parameters) {
                        log("   param id=0x" + Integer.toHexString(parameter.id)
                                + " len=" + parameter.value.length + " value="
                                + Logs.hex(parameter.value, parameter.value.length));
                        if (parameter.value.length == HidTouch.REPORT_LENGTH) {
                            try {
                                HidTouch.Touch touch = HidTouch.decode(parameter.value,
                                        parameter.value.length);
                                touches++;
                                log("   decoded " + touch.describe());
                                listener.onTouch(touch);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                    listener.onMessage(message);
                }
            }
        } catch (Exception error) {
            if (running) {
                log("control channel ended: " + error);
            }
        }
        log("control channel closed");
    }

    private void log(String message) {
        listener.onLog(message);
    }
}
