package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.List;

/** Desktop self test for the iAP2 link and control codecs. */
public final class SelfTestIap2 {

    private static int failures;

    public static void main(String[] args) {
        // checksum: matches the reference formula
        check("checksum is the 8-bit twos complement",
                Iap2.checksum(new byte[] {1, 2, 3}) == (byte) (-6));

        // control message round trip
        List<Iap2.Parameter> parameters = new ArrayList<>();
        parameters.add(new Iap2.Parameter(0x0000, "1.0".getBytes()));
        parameters.add(new Iap2.Parameter(0x0001, new byte[] {0, 2}));
        byte[] control = Iap2.control(0x1D00, parameters);
        check("control header is 40 40", (control[0] & 0xFF) == 0x40 && (control[1] & 0xFF) == 0x40);
        check("control length field matches the buffer", Iap2.readWord(control, 2) == control.length);
        check("control command is preserved", Iap2.readWord(control, 4) == 0x1D00);
        Iap2.Message message = Iap2.decodeControl(control);
        check("control decodes to two parameters", message.parameters.size() == 2);
        check("parameter id 0x0000 round trips", message.find(0x0000) != null
                && new String(message.find(0x0000).value).equals("1.0"));
        check("parameter id 0x0001 round trips", message.find(0x0001) != null
                && (message.find(0x0001).value[1] & 0xFF) == 2);

        // link frame round trip
        byte[] frame = Iap2.frame(Iap2.SYN, 1, 0, 0, control);
        check("frame starts with 0xFF", (frame[0] & 0xFF) == 0xFF);
        Iap2.Frame parsed = Iap2.parse(frame);
        check("frame flags round trip", parsed.has(Iap2.SYN) && !parsed.has(Iap2.RST));
        check("frame sequence round trips", parsed.sequence == 1);
        check("frame payload is the control message",
                java.util.Arrays.equals(parsed.payload, control));
        check("frame description mentions SYN", parsed.describe().contains("SYN"));

        Iap2.Frame empty = Iap2.parse(Iap2.frame(Iap2.ACK, 2, 1, 0, null));
        check("empty payload frames parse", empty.payload.length == 0 && empty.has(Iap2.ACK));

        // corrupted frames must be rejected
        byte[] broken = frame.clone();
        broken[broken.length - 1] ^= 0x7F;
        boolean rejected = false;
        try {
            Iap2.parse(broken);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check("payload checksum mismatch is rejected", rejected);

        // identification payload: byte0=1, len>=10, triples from offset 10
        byte[] identification = new byte[16];
        identification[0] = 1;
        identification[1] = 2;
        identification[2] = 0;
        identification[3] = 13;
        identification[10] = 0;
        identification[11] = 0;
        identification[12] = 2;
        identification[13] = 3;
        identification[14] = 1;
        identification[15] = 0;
        check("control session found", Iap2.controlSession(identification) == 0);
        identification[10] = 4;
        check("another control session id is found", Iap2.controlSession(identification) == 4);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST IAP2 OK" : ("SELFTEST IAP2 FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
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
