package org.carplaytocarlife.iccoa;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * The AirPlay timing responder: the keep-alive that stops a receiver from
 * dropping the session.
 *
 * Layout taken from the reference app's timing job: a request of at least 32
 * bytes whose second byte is 0xD2 is answered with a 32-byte reply carrying
 * 0x80/0xD3/7, the request's bytes 24..31 copied to 8..15, and two NTP
 * timestamps at offsets 16 and 24.
 */
public final class AirplayTiming implements Runnable {

    public static final int REQUEST_TYPE = 0xD2;
    public static final int REPLY_TYPE = 0xD3;
    public static final int MIN_REQUEST = 32;
    public static final int REPLY_LENGTH = 32;

    private final DatagramSocket socket;
    private final Listener listener;
    private volatile boolean running;
    private long replies;

    public interface Listener {
        void onLog(String message);
    }

    public AirplayTiming(DatagramSocket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public long replies() {
        return replies;
    }

    public void start() {
        running = true;
        Thread thread = new Thread(this, "airplay-timing");
        thread.setDaemon(true);
        thread.start();
        log("timing responder on udp/" + port());
    }

    public void stop() {
        running = false;
        socket.close();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[512];
        while (running) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(request);
            } catch (Exception error) {
                if (running) {
                    log("timing receive ended: " + error);
                }
                return;
            }
            byte[] reply = replyFor(buffer, request.getLength());
            if (reply == null) {
                continue;
            }
            try {
                socket.send(new DatagramPacket(reply, reply.length,
                        request.getAddress(), request.getPort()));
                replies++;
                if (replies == 1 || replies % 30 == 0) {
                    log("timing replies=" + replies);
                }
            } catch (Exception error) {
                log("timing reply failed: " + error);
            }
        }
    }

    /** Builds the reply for one request, or null when it is not a timing request. */
    public static byte[] replyFor(byte[] request, int length) {
        if (length < MIN_REQUEST || (request[1] & 0xFF) != REQUEST_TYPE) {
            return null;
        }
        byte[] reply = new byte[REPLY_LENGTH];
        reply[0] = (byte) 0x80;
        reply[1] = (byte) REPLY_TYPE;
        reply[3] = 7;
        System.arraycopy(request, 24, reply, 8, 8);
        long ntp = ntpNow();
        putLong(reply, 16, ntp);
        putLong(reply, 24, ntp);
        return reply;
    }

    /** Seconds since 1900 in the high half, fraction in the low half. */
    public static long ntpNow() {
        return ntpFromMillis(System.currentTimeMillis());
    }

    public static long ntpFromMillis(long millis) {
        // one implementation, shared with the video frame header
        return AirplayStream.ntpTimestamp(millis);
    }

    private static void putLong(byte[] target, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            target[offset + i] = (byte) (value >> (56 - i * 8));
        }
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        } else {
            Logs.add(message);
        }
    }

    /** Convenience for the service: reply packets are the same shape. */
    public static boolean isRequest(byte[] data, int length) {
        return length >= MIN_REQUEST && (data[1] & 0xFF) == REQUEST_TYPE;
    }

    public static InetAddress addressOf(DatagramPacket packet) {
        return packet.getAddress();
    }
}
