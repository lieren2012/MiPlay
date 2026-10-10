package org.carplaytocarlife.iccoa;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

/**
 * Receives the RTP video stream and reassembles H.264 access units.
 *
 * Handles the three packetisation modes a WFD source uses: a single NAL unit,
 * STAP-A aggregation and FU-A fragmentation.
 */
public final class RtpReceiver implements Runnable {

    public interface Sink {
        void onAccessUnit(byte[] annexB, long timestampUs);
    }

    private final int port;
    private final Sink sink;
    private volatile boolean running;
    private DatagramSocket socket;

    public RtpReceiver(int port, Sink sink) {
        this.port = port;
        this.sink = sink;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(port);
        running = true;
        Thread thread = new Thread(this, "rtp-receiver");
        thread.setDaemon(true);
        thread.start();
        Logs.add("RTP listening on UDP " + port);
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[65536];
        long packets = 0;
        long bytes = 0;
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException error) {
                if (running) {
                    Logs.add("RTP receive error: " + error);
                }
                return;
            }
            packets++;
            bytes += packet.getLength();
            if (packets <= 3 || packets % 200 == 0) {
                Logs.add("RTP packet " + packets + " len=" + packet.getLength()
                        + " total=" + bytes);
            }
            try {
                handlePacket(packet.getData(), packet.getLength());
            } catch (RuntimeException error) {
                Logs.add("RTP parse error: " + error);
            }
        }
    }

    private void handlePacket(byte[] data, int length) {
        if (length < 12) {
            return;
        }
        int version = (data[0] >> 6) & 0x03;
        if (version != 2) {
            return;
        }
        int csrcCount = data[0] & 0x0F;
        boolean extension = (data[0] & 0x10) != 0;
        int headerLength = 12 + csrcCount * 4;
        if (extension && length >= headerLength + 4) {
            int words = ((data[headerLength] & 0xFF) << 8) | (data[headerLength + 1] & 0xFF);
            headerLength += 4 + words * 4;
        }
        if (length <= headerLength) {
            return;
        }
        long timestamp = ((long) (data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        long timestampUs = timestamp * 1000000L / 90000L;
        int nalType = data[headerLength] & 0x1F;

        if (nalType >= 1 && nalType <= 23) {
            emitRange(data, headerLength, length - headerLength, timestampUs);
        } else if (nalType == 24) {
            int offset = headerLength + 1;
            while (offset + 2 <= length) {
                int size = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                if (size <= 0 || offset + size > length) {
                    break;
                }
                emitRange(data, offset, size, timestampUs);
                offset += size;
            }
        } else if (nalType == 28) {
            if (length < headerLength + 2) {
                return;
            }
            int fuHeader = data[headerLength + 1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int reconstructed = (data[headerLength] & 0xE0) | (fuHeader & 0x1F);
            if (start) {
                fragment = new java.io.ByteArrayOutputStream();
                fragment.write(reconstructed);
            }
            if (fragment != null) {
                fragment.write(data, headerLength + 2, length - headerLength - 2);
            }
            if (end && fragment != null) {
                byte[] unit = fragment.toByteArray();
                fragment = null;
                emitAnnexB(unit, timestampUs);
            }
        }
    }

    private java.io.ByteArrayOutputStream fragment;

    private void emitRange(byte[] data, int offset, int length, long timestampUs) {
        if (length <= 0) {
            return;
        }
        emitAnnexB(Arrays.copyOfRange(data, offset, offset + length), timestampUs);
    }

    private void emitAnnexB(byte[] unit, long timestampUs) {
        byte[] annexB = new byte[unit.length + 4];
        annexB[0] = 0;
        annexB[1] = 0;
        annexB[2] = 0;
        annexB[3] = 1;
        System.arraycopy(unit, 0, annexB, 4, unit.length);
        sink.onAccessUnit(annexB, timestampUs);
    }
}
