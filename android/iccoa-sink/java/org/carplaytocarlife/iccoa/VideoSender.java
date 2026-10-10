package org.carplaytocarlife.iccoa;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Packetises H.264 access units into RTP for the car.
 *
 * The mirror of {@link RtpReceiver}: single NAL units when they fit, FU-A
 * fragmentation when they do not, with the marker bit set on the last packet of
 * an access unit and a 90 kHz timestamp. Kept free of Android APIs so it can be
 * checked on the desktop.
 */
public final class VideoSender {

    public static final int MTU = 1400;
    public static final int PAYLOAD_TYPE = 96;
    public static final int CLOCK_RATE = 90000;

    private int sequence;
    private final int ssrc;

    public VideoSender(int ssrc) {
        this.ssrc = ssrc;
    }

    public int sequence() {
        return sequence;
    }

    /** Splits one Annex-B access unit into RTP payloads. */
    public List<byte[]> packetise(byte[] annexB, long timestampUs) {
        List<byte[]> payloads = new ArrayList<>();
        List<byte[]> nals = splitNals(annexB);
        if (nals.isEmpty()) {
            return payloads;
        }
        byte[] marker = null;
        for (int index = 0; index < nals.size(); index++) {
            byte[] nal = nals.get(index);
            if (nal.length <= MTU) {
                byte[] payload = new byte[nal.length];
                System.arraycopy(nal, 0, payload, 0, nal.length);
                payloads.add(payload);
            } else {
                int header = nal[0] & 0xFF;
                int indicator = (header & 0xE0) | 28;      // FU-A
                byte type = (byte) (header & 0x1F);
                int offset = 1;
                boolean first = true;
                while (offset < nal.length) {
                    int size = Math.min(MTU - 2, nal.length - offset);
                    boolean last = offset + size >= nal.length;
                    byte[] payload = new byte[size + 2];
                    payload[0] = (byte) indicator;
                    payload[1] = (byte) ((first ? 0x80 : 0x00) | (last ? 0x40 : 0x00) | type);
                    System.arraycopy(nal, offset, payload, 2, size);
                    payloads.add(payload);
                    offset += size;
                    first = false;
                }
            }
        }
        if (!payloads.isEmpty()) {
            marker = payloads.get(payloads.size() - 1);
            payloads.set(payloads.size() - 1, marker);
        }
        return payloads;
    }

    /** Wraps each payload with an RTP header, marking the last one. */
    public List<byte[]> toPackets(byte[] annexB, long timestampUs) {
        List<byte[]> payloads = packetise(annexB, timestampUs);
        List<byte[]> packets = new ArrayList<>(payloads.size());
        long timestamp = timestampUs * CLOCK_RATE / 1_000_000L;
        for (int index = 0; index < payloads.size(); index++) {
            byte[] payload = payloads.get(index);
            byte[] packet = new byte[12 + payload.length];
            packet[0] = (byte) 0x80;
            packet[1] = (byte) ((index == payloads.size() - 1 ? 0x80 : 0x00) | PAYLOAD_TYPE);
            int seq = sequence++ & 0xFFFF;
            packet[2] = (byte) (seq >> 8);
            packet[3] = (byte) seq;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            System.arraycopy(payload, 0, packet, 12, payload.length);
            packets.add(packet);
        }
        return packets;
    }

    /** Splits Annex-B input into NAL units without start codes. */
    public static List<byte[]> splitNals(byte[] annexB) {
        List<byte[]> nals = new ArrayList<>();
        int index = 0;
        int start = -1;
        while (index + 2 < annexB.length) {
            boolean three = annexB[index] == 0 && annexB[index + 1] == 0 && annexB[index + 2] == 1;
            boolean four = annexB[index] == 0 && annexB[index + 1] == 0 && annexB[index + 2] == 0
                    && index + 3 < annexB.length && annexB[index + 3] == 1;
            if (three || four) {
                if (start >= 0) {
                    int end = index;
                    nals.add(java.util.Arrays.copyOfRange(annexB, start, end));
                }
                index += four ? 4 : 3;
                start = index;
                continue;
            }
            index++;
        }
        if (start >= 0 && start < annexB.length) {
            nals.add(java.util.Arrays.copyOfRange(annexB, start, annexB.length));
        }
        return nals;
    }

    /** Sends one access unit to the car's video port. */
    public int send(DatagramSocket socket, InetAddress address, int port, byte[] annexB,
            long timestampUs) throws java.io.IOException {
        List<byte[]> packets = toPackets(annexB, timestampUs);
        for (byte[] packet : packets) {
            socket.send(new DatagramPacket(packet, packet.length, address, port));
        }
        return packets.size();
    }
}
