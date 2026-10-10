package org.carplaytocarlife.iccoa;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * The port the car is invited to connect to.
 *
 * The advertisement this phone sends over mDNS names a control port, so that
 * port has to exist: the reference opens its server sockets first and advertises
 * one of them ("startInvitationListener"). Nothing of the conversation is
 * guessed here - whatever the car sends is logged as bytes, which is what the
 * next step is built from.
 */
public final class CarplayControlListener implements Runnable {

    public interface Listener {
        void onLog(String message);

        /** Raw bytes the car sent on the invited port. */
        void onControlBytes(Socket socket, byte[] bytes, int length);
    }

    /** Nothing longer than this is kept from one read. */
    public static final int MAX_READ = 4096;

    private final Listener listener;
    private final List<Socket> peers = new ArrayList<>();
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private long connections;

    public CarplayControlListener(Listener listener) {
        this.listener = listener;
    }

    /** The port to advertise, or 0 when the listener could not start. */
    public int port() {
        return server == null ? 0 : server.getLocalPort();
    }

    public long connections() {
        return connections;
    }

    public boolean start() {
        try {
            server = new ServerSocket(0);
        } catch (Exception error) {
            log("cannot open the invited control port: " + error);
            return false;
        }
        running = true;
        thread = new Thread(this, "carplay-control");
        thread.setDaemon(true);
        thread.start();
        log("inviting the car to tcp/" + port());
        return true;
    }

    @Override
    public void run() {
        while (running) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (Exception error) {
                if (running) {
                    log("the invited port stopped accepting: " + error);
                }
                return;
            }
            connections++;
            synchronized (peers) {
                peers.add(socket);
            }
            log("the car connected to the invited port from "
                    + socket.getInetAddress().getHostAddress());
            read(socket);
        }
    }

    /** Reads whatever the car sends and hands it over as bytes. */
    private void read(Socket socket) {
        byte[] buffer = new byte[MAX_READ];
        try {
            InputStream in = socket.getInputStream();
            while (running) {
                int count = in.read(buffer);
                if (count < 0) {
                    log("the car closed the invited port");
                    return;
                }
                if (count == 0) {
                    continue;
                }
                log("control bytes from the car: " + Logs.hex(buffer, Math.min(count, 64)));
                listener.onControlBytes(socket, buffer, count);
            }
        } catch (Exception error) {
            if (running) {
                log("the invited port ended: " + error);
            }
        }
    }

    public void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                // best effort while shutting down
            }
            server = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        synchronized (peers) {
            for (Socket socket : peers) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // best effort while shutting down
                }
            }
            peers.clear();
        }
    }

    private void log(String message) {
        listener.onLog(message);
    }
}
