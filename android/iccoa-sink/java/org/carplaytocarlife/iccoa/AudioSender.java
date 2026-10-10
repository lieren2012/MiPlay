package org.carplaytocarlife.iccoa;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * The AirPlay audio packet: a 12-byte RTP-style header, a ChaCha20-Poly1305
 * sealed payload and the tail of the nonce.
 *
 * Taken from the reference app's audio packetiser: header =
 * {@code 0x80 | payloadType | seq(16) | timestamp(32) | ssrc(32)}, the payload is
 * sealed with a 32-byte key and the AEAD's additional data is the header's
 * bytes 4..12 (timestamp plus ssrc), and the packet ends with the nonce's last
 * eight bytes.
 */
public final class AudioSender {

    public static final int HEADER_LENGTH = 12;
    public static final int TAG_LENGTH = 16;
    public static final int TRAILER_LENGTH = 8;
    public static final int KEY_LENGTH = 32;

    private final int payloadType;

    public AudioSender(int payloadType) {
        this.payloadType = payloadType;
    }

    /**
     * The 12-byte nonce AirPlay derives from a running counter: four zero bytes
     * and the counter little endian. The trailer carries the back half, so the
     * byte order has to match what the car reconstructs.
     */
    public static byte[] nonce(long counter) {
        byte[] out = new byte[12];
        for (int i = 0; i < 8; i++) {
            out[4 + i] = (byte) (counter >>> (i * 8));
        }
        return out;
    }

    /** Builds one audio packet from 16-bit big-endian PCM. */
    public byte[] packet(byte[] pcm, byte[] key, int sequence, long timestamp,
            long nonceCounter) {
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("audio key must be 32 bytes");
        }
        byte[] header = new byte[HEADER_LENGTH];
        header[0] = (byte) 0x80;
        header[1] = (byte) payloadType;
        header[2] = (byte) (sequence >> 8);
        header[3] = (byte) sequence;
        for (int i = 0; i < 4; i++) {
            header[4 + i] = (byte) (timestamp >> (24 - i * 8));
        }
        byte[] nonce = nonce(nonceCounter);
        byte[] aad = java.util.Arrays.copyOfRange(header, 4, 12);
        byte[] sealed = seal(key, nonce, aad, pcm);
        byte[] packet = new byte[HEADER_LENGTH + sealed.length + TRAILER_LENGTH];
        System.arraycopy(header, 0, packet, 0, HEADER_LENGTH);
        System.arraycopy(sealed, 0, packet, HEADER_LENGTH, sealed.length);
        System.arraycopy(nonce, 4, packet, HEADER_LENGTH + sealed.length, TRAILER_LENGTH);
        return packet;
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] out = new byte[cipher.getOutputSize(plaintext.length)];
        int written = cipher.processBytes(plaintext, 0, plaintext.length, out, 0);
        try {
            cipher.doFinal(out, written);
        } catch (Exception error) {
            throw new IllegalStateException("audio sealing failed", error);
        }
        return out;
    }

    /** Opens a packet built by {@link #packet}, for tests and for loopback. */
    public static byte[] open(byte[] packet, byte[] key) {
        int sealedLength = packet.length - HEADER_LENGTH - TRAILER_LENGTH;
        if (sealedLength <= 0) {
            return null;
        }
        byte[] nonce = nonceFromTrailer(packet);
        byte[] aad = java.util.Arrays.copyOfRange(packet, 4, 12);
        byte[] sealed = java.util.Arrays.copyOfRange(packet, HEADER_LENGTH,
                HEADER_LENGTH + sealedLength);
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(false, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] out = new byte[cipher.getOutputSize(sealed.length)];
        try {
            int written = cipher.processBytes(sealed, 0, sealed.length, out, 0);
            int total = written + cipher.doFinal(out, written);
            return java.util.Arrays.copyOf(out, total);
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * Rebuilds the nonce from the trailer. The counter's leading bytes are not
     * carried, so this only works while the counter fits in the low 32 bits;
     * that is how the reference uses it (a per-session counter).
     */
    private static byte[] nonceFromTrailer(byte[] packet) {
        byte[] nonce = new byte[12];
        System.arraycopy(packet, packet.length - TRAILER_LENGTH, nonce, 4, TRAILER_LENGTH);
        return nonce;
    }

    public int sequenceOf(byte[] packet) {
        return ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
    }

    public long timestampOf(byte[] packet) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (packet[4 + i] & 0xFF);
        }
        return value;
    }

    public int send(DatagramSocket socket, InetAddress address, int port, byte[] packet)
            throws java.io.IOException {
        socket.send(new DatagramPacket(packet, packet.length, address, port));
        return packet.length;
    }
}
