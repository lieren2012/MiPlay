package org.carplaytocarlife.iccoa;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Desktop self test for the AirPlay timing responder. */
public final class SelfTestTiming {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // NTP conversion
        long ntp = AirplayTiming.ntpFromMillis(1_000_000_000_000L);
        long seconds = ntp >>> 32;
        check("NTP seconds are Unix seconds plus the 1900 offset",
                seconds == 1_000_000_000L + 2_208_988_800L);
        long fraction = ntp & 0xFFFFFFFFL;
        check("the fraction is zero for a whole second", fraction == 0);
        long half = AirplayTiming.ntpFromMillis(1_500L);
        check("half a second is half the fraction range",
                Math.abs((half & 0xFFFFFFFFL) - 0x80000000L) < 0x1000000L);

        // request handling
        byte[] request = new byte[64];
        request[0] = (byte) 0x80;
        request[1] = (byte) AirplayTiming.REQUEST_TYPE;
        for (int i = 24; i < 32; i++) {
            request[i] = (byte) (0x40 + i);
        }
        check("a timing request is recognised", AirplayTiming.isRequest(request, request.length));
        byte[] reply = AirplayTiming.replyFor(request, request.length);
        check("the reply is 32 bytes", reply != null && reply.length == AirplayTiming.REPLY_LENGTH);
        check("the reply type is D3", (reply[1] & 0xFF) == AirplayTiming.REPLY_TYPE);
        check("the reply carries 0x80 in byte 0", (reply[0] & 0xFF) == 0x80);
        check("byte 3 is 7", reply[3] == 7);
        boolean echoed = true;
        for (int i = 0; i < 8; i++) {
            if (reply[8 + i] != request[24 + i]) {
                echoed = false;
            }
        }
        check("the request's bytes 24..31 are echoed to 8..15", echoed);
        check("both NTP fields are filled",
                readLong(reply, 16) != 0 && readLong(reply, 24) != 0);
        check("the two NTP fields agree", readLong(reply, 16) == readLong(reply, 24));

        byte[] notATimingRequest = new byte[64];
        notATimingRequest[1] = (byte) 0x55;
        check("other traffic is ignored",
                AirplayTiming.replyFor(notATimingRequest, notATimingRequest.length) == null);
        check("short packets are ignored",
                AirplayTiming.replyFor(request, 20) == null);

        // end to end over a real socket
        DatagramSocket responderSocket = new DatagramSocket(0);
        AirplayTiming responder = new AirplayTiming(responderSocket, null);
        responder.start();
        DatagramSocket client = new DatagramSocket();
        client.setSoTimeout(3000);
        client.send(new DatagramPacket(request, request.length,
                InetAddress.getByName("127.0.0.1"), responder.port()));
        byte[] received = new byte[128];
        DatagramPacket incoming = new DatagramPacket(received, received.length);
        client.receive(incoming);
        check("the responder answers over UDP", incoming.getLength() == AirplayTiming.REPLY_LENGTH);
        check("the answer matches the expected shape",
                (received[1] & 0xFF) == AirplayTiming.REPLY_TYPE && received[3] == 7);
        check("the responder counted the reply", responder.replies() == 1);
        responder.stop();
        client.close();

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST TIMING OK" : ("SELFTEST TIMING FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
