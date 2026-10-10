package org.carplaytocarlife.iccoa;

import java.util.List;

/** Desktop self test for the H.264 RTP packetiser. */
public final class SelfTestVideo {

    private static int failures;

    public static void main(String[] args) {
        // a small access unit: SPS + PPS + IDR, each a single NAL
        byte[] small = concat(
                new byte[] {0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E},
                new byte[] {0, 0, 0, 1, 0x68, (byte) 0xCE, 0x3C, (byte) 0x80},
                new byte[] {0, 0, 0, 1, 0x65, 0x11, 0x22});
        List<byte[]> nals = VideoSender.splitNals(small);
        check("three NAL units split out", nals.size() == 3);
        check("SPS kept its payload", nals.get(0).length == 4 && (nals.get(0)[0] & 0xFF) == 0x67);

        VideoSender sender = new VideoSender(0x12345678);
        List<byte[]> packets = sender.toPackets(small, 1_000_000);
        check("three small NALs become three packets", packets.size() == 3);
        check("RTP version is 2", (packets.get(0)[0] & 0xC0) == 0x80);
        check("payload type is 96", (packets.get(0)[1] & 0x7F) == 96);
        check("only the last packet is marked", (packets.get(0)[1] & 0x80) == 0
                && (packets.get(2)[1] & 0x80) != 0);
        check("sequence numbers increment", (packets.get(0)[3] & 0xFF) == 0
                && (packets.get(1)[3] & 0xFF) == 1 && (packets.get(2)[3] & 0xFF) == 2);
        check("timestamp is 90 kHz", ((packets.get(0)[4] & 0xFF) << 24
                | (packets.get(0)[5] & 0xFF) << 16
                | (packets.get(0)[6] & 0xFF) << 8
                | (packets.get(0)[7] & 0xFF)) == 90_000);
        check("SSRC is carried", ((packets.get(0)[8] & 0xFF) << 24) == 0x12000000);
        check("first packet payload is the SPS", (packets.get(0)[12] & 0xFF) == 0x67);

        // a large NAL must be fragmented
        byte[] large = new byte[4001];
        large[0] = 0x65;
        for (int i = 1; i < large.length; i++) {
            large[i] = (byte) i;
        }
        byte[] largeUnit = concat(new byte[] {0, 0, 0, 1}, large);
        List<byte[]> fragmented = sender.packetise(largeUnit, 2_000_000);
        check("a large NAL is fragmented", fragmented.size() == 3);
        check("first fragment declares the start bit",
                (fragmented.get(0)[0] & 0x1F) == 28 && (fragmented.get(0)[1] & 0x80) != 0);
        check("last fragment declares the end bit",
                (fragmented.get(2)[1] & 0x40) != 0);
        check("fragment types preserve the NAL type",
                (fragmented.get(0)[1] & 0x1F) == (0x65 & 0x1F));
        int total = 0;
        for (byte[] fragment : fragmented) {
            total += fragment.length - 2;
        }
        check("fragmentation loses no payload bytes", total == large.length - 1,
                "total=" + total);
        check("fragments respect the MTU", fragmented.get(0).length <= VideoSender.MTU);

        // round trip: rebuild the access unit with an independently written
        // depacketiser, which is what the receiving side has to do
        byte[] rebuilt = depacketise(sender.toPackets(small, 3_000_000));
        check("the access unit survives packetise + depacketise",
                java.util.Arrays.equals(rebuilt, small),
                "rebuilt " + rebuilt.length + " bytes, expected " + small.length);
        byte[] rebuiltLarge = depacketise(sender.toPackets(
                concat(new byte[] {0, 0, 0, 1}, large), 4_000_000));
        check("a fragmented NAL survives the round trip",
                java.util.Arrays.equals(rebuiltLarge, concat(new byte[] {0, 0, 0, 1}, large)));

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST VIDEO OK" : ("SELFTEST VIDEO FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** An independent depacketiser: single NAL units plus FU-A reassembly. */
    private static byte[] depacketise(List<byte[]> packets) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream fragment = null;
        int fragmentHeader = 0;
        for (byte[] packet : packets) {
            byte[] payload = java.util.Arrays.copyOfRange(packet, 12, packet.length);
            int type = payload[0] & 0x1F;
            if (type == 28) {
                int indicator = payload[0] & 0xE0;
                int fuHeader = payload[1] & 0xFF;
                if ((fuHeader & 0x80) != 0) {
                    fragment = new java.io.ByteArrayOutputStream();
                    fragmentHeader = indicator | (fuHeader & 0x1F);
                    fragment.write(fragmentHeader);
                }
                if (fragment != null) {
                    fragment.write(payload, 2, payload.length - 2);
                }
                if ((fuHeader & 0x40) != 0 && fragment != null) {
                    byte[] unit = fragment.toByteArray();
                    fragment = null;
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(1);
                    out.write(unit, 0, unit.length);
                }
            } else {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                out.write(payload, 0, payload.length);
            }
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  " + detail);
        }
    }
}
