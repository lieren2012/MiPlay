package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop self test: a fake phone drives the sink through M1..M7 and the RTP
 * depacketiser is exercised directly. Runs with a plain JDK, no Android.
 *
 * <pre>javac -d out Rtsp.java WfdSession.java RtspServer.java RtpReceiver.java Logs.java SelfTest.java
 * java -cp out org.carplaytocarlife.iccoa.SelfTest</pre>
 */
public final class SelfTest {

    private static int failures;
    private static final AtomicInteger accessUnits = new AtomicInteger();
    private static final CountDownLatch allUnits = new CountDownLatch(4);

    public static void main(String[] args) throws Exception {
        int rtspPort = 17360;
        int rtpPort = 17362;
        WfdSession session = new WfdSession(new WfdSession.Listener() {
            @Override public void onLog(String message) { Logs.add(message); }
            @Override public void onState(WfdSession.State state) { }
            @Override public void onStreamingStart() { Logs.add("streaming start"); }
            @Override public void onStreamingStop() { }
            @Override public void onInputPort(int port) { }
        });
        session.setRtpPort(rtpPort);
        check("capability block carries the ICCOA extensions",
                session.sinkCapabilities().has("iccoa_support_fusion"));
        check("capability block carries osc_display_mode",
                session.sinkCapabilities().has("osc_display_mode"));
        check("no capability report is missing", session.missingReports().isEmpty());

        RtspServer server = new RtspServer(rtspPort, session);
        server.start();
        RtpReceiver receiver = new RtpReceiver(rtpPort, new RtpReceiver.Sink() {
            @Override public void onAccessUnit(byte[] annexB, long timestampUs) {
                accessUnits.incrementAndGet();
                allUnits.countDown();
            }
        });
        receiver.start();
        Thread.sleep(300);

        Socket socket = new Socket("127.0.0.1", rtspPort);
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // M1
        send(out, "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n");
        String options = read(in);
        check("M1 answered 200", options.startsWith("RTSP/1.0 200"));
        check("M1 advertises methods", options.contains("Public:") && options.contains("SETUP"));

        // M2 -> sink replies and pushes M3
        send(out, "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n"
                + "Content-Type: text/parameters\r\nContent-Length: 17\r\n\r\n"
                + "wfd_video_formats");
        String m2 = read(in);
        check("M2 answered 200", m2.startsWith("RTSP/1.0 200"));
        String m3 = read(in);
        check("M3 is a SET_PARAMETER from the sink", m3.startsWith("SET_PARAMETER "));
        check("M3 carries iccoa_support_fusion", m3.contains("iccoa_support_fusion"));
        send(out, "RTSP/1.0 200 OK\r\nCSeq: 100\r\n\r\n");

        // M4 -> sink replies and pushes M5
        send(out, "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n"
                + "Content-Type: text/parameters\r\nContent-Length: 29\r\n\r\n"
                + "wfd_video_formats: 00 00 02 04\n");
        String m4 = read(in);
        check("M4 answered 200", m4.startsWith("RTSP/1.0 200"));
        String m5 = read(in);
        check("M5 triggers SETUP", m5.contains("wfd_trigger_method: SETUP"));
        send(out, "RTSP/1.0 200 OK\r\nCSeq: 101\r\n\r\n");

        // M6
        send(out, "SETUP rtsp://localhost/wfd1.0/streamid=0 RTSP/1.0\r\nCSeq: 4\r\n"
                + "Transport: RTP/AVP/UDP;unicast;client_port=5000-5001\r\n\r\n");
        String m6 = read(in);
        check("M6 answered 200", m6.startsWith("RTSP/1.0 200"));
        check("M6 echoes client ports and offers server ports",
                m6.contains("client_port=5000-5001")
                        && m6.contains("server_port=" + rtpPort + "-" + (rtpPort + 1)));
        check("M6 carries a Session header", m6.contains("Session:"));

        // M7
        send(out, "PLAY rtsp://localhost/wfd1.0/streamid=0 RTSP/1.0\r\nCSeq: 5\r\n"
                + "Session: 0\r\n\r\n");
        String m7 = read(in);
        check("M7 answered 200", m7.startsWith("RTSP/1.0 200"));
        check("session reached STREAMING", session.state() == WfdSession.State.STREAMING);

        // RTP: single NAL, STAP-A and FU-A fragmentation
        DatagramSocket rtp = new DatagramSocket();
        InetAddress local = InetAddress.getByName("127.0.0.1");
        sendRtp(rtp, local, rtpPort, new byte[] {0x65, 1, 2, 3, 4, 5});
        sendRtp(rtp, local, rtpPort, new byte[] {24, 0, 2, 0x41, 9, 0, 1, 0x41});
        sendRtp(rtp, local, rtpPort, new byte[] {0x7C, (byte) 0x85, 1, 2, 3});
        sendRtp(rtp, local, rtpPort, new byte[] {0x7C, (byte) 0x45, 4, 5, 6});
        boolean got = allUnits.await(3, TimeUnit.SECONDS);
        check("RTP depacketiser produced access units", got);
        check("all three packetisation modes emitted", accessUnits.get() >= 4,
                "units=" + accessUnits.get());

        socket.close();
        receiver.stop();
        server.stop();

        for (String line : Logs.snapshot()) {
            System.out.println("  log | " + line);
        }
        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST OK" : ("SELFTEST FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void send(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendRtp(DatagramSocket socket, InetAddress address, int port,
            byte[] payload) throws Exception {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 0x60;
        packet[2] = 0;
        packet[3] = 1;
        packet[4] = 0;
        packet[5] = 0;
        packet[6] = 0x10;
        packet[7] = 0;
        packet[8] = 0x0A;
        packet[9] = 0x0B;
        packet[10] = 0x0C;
        packet[11] = 0x0D;
        System.arraycopy(payload, 0, packet, 12, payload.length);
        socket.send(new DatagramPacket(packet, packet.length, address, port));
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int newlines = 0;
        while (newlines < 2) {
            int value = in.read();
            if (value < 0) {
                break;
            }
            buffer.write(value);
            if (value == '\n') {
                newlines++;
            } else if (value != '\r') {
                newlines = 0;
            }
        }
        String headers = buffer.toString("UTF-8");
        int contentLength = 0;
        for (String line : headers.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength > 0) {
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int count = in.read(body, read, contentLength - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return headers + new String(body, 0, read, StandardCharsets.UTF_8);
        }
        return headers;
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : ("  " + detail)));
        }
    }
}
