package org.carplaytocarlife.iccoa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AirPlay stream cipher and the video frame envelope.
 *
 * Both come from the reference app: {@code MfiSecurity.screenCipher} derives
 * {@code SHA-512("AirPlayStreamKey" + counter || baseKey)[0..16]} and the same
 * for {@code "AirPlayStreamIV"}, then runs one continuous {@code AES/CTR/NoPadding}
 * cipher for the whole session (its own log notes "CTR-continuation=true").
 * {@code VideoPackets.aesFrame} wraps the encrypted body in a 128-byte header
 * with the length at offset 0 and the timestamp at offset 8, both little endian.
 */
public final class AirplayStream {

    public static final int VIDEO_HEADER_LENGTH = 128;
    public static final String STREAM_KEY_LABEL = "AirPlayStreamKey";
    public static final String STREAM_IV_LABEL = "AirPlayStreamIV";

    private final Cipher cipher;
    private long frames;

    private AirplayStream(Cipher cipher) {
        this.cipher = cipher;
    }

    /**
     * Builds the session cipher.
     *
     * The key comes from {@code baseKey} and the IV from {@code baseIv}: the
     * reference derives them from two different 16 byte secrets, so the car can
     * only decrypt the stream if both are used the same way here.
     * {@code counter} is the stream index; 1 is the usual video stream.
     */
    public static AirplayStream create(byte[] baseKey, byte[] baseIv, int counter) {
        try {
            byte[] key = derive(counter, STREAM_KEY_LABEL, baseKey);
            byte[] iv = derive(counter, STREAM_IV_LABEL, baseIv);
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return new AirplayStream(cipher);
        } catch (Exception error) {
            throw new IllegalStateException("cannot build the stream cipher", error);
        }
    }

    public static byte[] derive(int counter, String label, byte[] baseKey) {
        byte[] prefix = (label + counter).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = AirplayCrypto.sha512(AirplayCrypto.concat(prefix, baseKey));
        return java.util.Arrays.copyOf(digest, 16);
    }

    /**
     * The frame that introduces the stream: 128 byte header plus the encoder's
     * configuration (SPS and PPS), in the clear.
     *
     * Header layout, little endian: int32 payload length at 0, 1 at offset 4
     * (version), 4 at offset 6 (flags - the reference logs "flags=4
     * view-area=zero"), the display size as two floats at 16 and 20, and the
     * payload from offset 128.
     */
    public static byte[] configFrame(byte[] codecConfig, int width, int height) {
        byte[] body = codecConfig == null ? new byte[0] : codecConfig;
        byte[] frame = new byte[VIDEO_HEADER_LENGTH + body.length];
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, body.length);
        frame[4] = 1;
        frame[6] = 4;
        header.putFloat(16, width);
        header.putFloat(20, height);
        System.arraycopy(body, 0, frame, VIDEO_HEADER_LENGTH, body.length);
        return frame;
    }

    /** The payload length a config frame announces. */
    public static int configLength(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
    }

    /** The display size a config frame announces. */
    public static float[] configSize(byte[] frame) {
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        return new float[] {header.getFloat(16), header.getFloat(20)};
    }

    /**
     * The 64 bit NTP timestamp the frame header carries, from a Unix millisecond
     * clock: seconds since 1900 in the high half, the fraction in the low half.
     * An AirPlay receiver reads this as its presentation timeline, so
     * microseconds here would put the picture an hour and a half out.
     */
    public static long ntpTimestamp(long unixMillis) {
        long seconds = unixMillis / 1000 + 2208988800L;
        long fraction = ((unixMillis % 1000) << 32) / 1000;
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    /** Reads an NTP timestamp back as Unix milliseconds, for tests and logs. */
    public static long ntpMillis(long ntp) {
        long seconds = (ntp >>> 32) - 2208988800L;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1000 + (fraction * 1000 >>> 32);
    }

    /**
     * Encrypts one body and wraps it in the 128-byte video header.
     *
     * {@code timestamp} is the NTP value {@link #ntpTimestamp} produces; it goes
     * to offset 8 little endian.
     */
    public byte[] videoFrame(byte[] body, long timestamp) {
        byte[] frame = new byte[VIDEO_HEADER_LENGTH + body.length];
        try {
            int written = cipher.update(body, 0, body.length, frame, VIDEO_HEADER_LENGTH);
            if (written != body.length) {
                throw new IllegalStateException("AES-CTR output length mismatch");
            }
        } catch (Exception error) {
            throw new IllegalStateException("cannot encrypt the frame", error);
        }
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, body.length);
        header.putLong(8, timestamp);
        frames++;
        return frame;
    }

    public long frames() {
        return frames;
    }

    /** Reads back the envelope, for tests and for decoding our own stream. */
    public static long frameTimestamp(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(8);
    }

    public static int frameLength(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
    }

    /** The matching decryptor, used by the self test. */
    public static Cipher decryptor(byte[] baseKey, byte[] baseIv, int counter) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(derive(counter, STREAM_KEY_LABEL, baseKey), "AES"),
                new IvParameterSpec(derive(counter, STREAM_IV_LABEL, baseIv)));
        return cipher;
    }
}
