package org.carplaytocarlife.iccoa;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Best-effort UIBC (User Input Back Channel) touch sender.
 *
 * The phone announces a UIBC port in {@code wfd_uibc_capability}; the sink opens
 * a TCP connection there and sends input reports. The framing below follows the
 * Wi-Fi Display UIBC layout (version/type byte, then a generic input body), but
 * the exact payload encoding has not been verified against a real phone yet, so
 * failures here are logged and ignored rather than breaking the video path.
 */
public final class UibcChannel {

    private static final byte VERSION_1 = 1;
    private static final byte TYPE_GENERIC = 2;
    private static final byte HID_TOUCH_REPORT = 0x22;

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream output;
    private long sent;
    private volatile boolean failed;

    public UibcChannel(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public boolean connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setTcpNoDelay(true);
            output = socket.getOutputStream();
            Logs.add("UIBC connected to " + host + ":" + port);
            return true;
        } catch (Exception error) {
            failed = true;
            Logs.add("UIBC connect failed: " + error);
            return false;
        }
    }

    /** Sends one single-touch report; coordinates are normalised to 0..4095. */
    public void touch(int action, int x, int y) {
        if (failed || output == null) {
            return;
        }
        try {
            byte[] report = new byte[5];
            report[0] = HID_TOUCH_REPORT;
            report[1] = (byte) action;
            report[2] = (byte) ((x >> 8) & 0x0F);
            report[3] = (byte) (x & 0xFF);
            report[4] = (byte) (y & 0xFF);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            body.write(VERSION_1);
            body.write(TYPE_GENERIC);
            body.write(report.length >> 8);
            body.write(report.length & 0xFF);
            body.write(report);
            byte[] payload = body.toByteArray();
            java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
            frame.write(0);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
            frame.write(payload);
            output.write(frame.toByteArray());
            output.flush();
            sent++;
            if (sent == 1 || sent % 50 == 0) {
                Logs.add("UIBC sent " + sent + " input reports");
            }
        } catch (Exception error) {
            failed = true;
            Logs.add("UIBC send failed: " + error);
        }
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // closing is best effort
        }
        socket = null;
        output = null;
    }
}
