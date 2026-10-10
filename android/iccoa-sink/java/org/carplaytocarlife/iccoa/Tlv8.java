package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The type-length-value encoding the pairing endpoints speak.
 *
 * pair-setup and pair-verify are not plist requests: their bodies are TLV
 * items ({@code application/pairing+tlv8}) - one byte of type, one byte of
 * length, then the value. A value longer than 255 bytes goes out as several
 * items with the same type and is joined again on the way in. The reference
 * implementation encodes and decodes exactly this, and a body in any other
 * shape is a pairing failure on a real car, however healthy our own fake car
 * looks while answering plists.
 *
 * The type numbers are HomeKit's, which is what AirPlay pairing reuses.
 */
public final class Tlv8 {

    /** 1 = PairSetup, 3 = PairVerify (the method item). */
    public static final int METHOD = 0;
    /** The identifier: the pairing id, as UTF-8 text. */
    public static final int IDENTIFIER = 1;
    public static final int SALT = 2;
    public static final int PUBLIC_KEY = 3;
    public static final int PROOF = 4;
    /** Sealed TLV, which is how the long term keys travel. */
    public static final int ENCRYPTED_DATA = 5;
    public static final int STATE = 6;
    public static final int ERROR = 7;
    public static final int SIGNATURE = 10;

    private Tlv8() {
    }

    /** One item per entry, in map order; a value over 255 bytes is fragmented. */
    public static byte[] encode(Map<Integer, byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> item : items.entrySet()) {
            byte[] value = item.getValue() == null ? new byte[0] : item.getValue();
            int offset = 0;
            do {
                int chunk = Math.min(255, value.length - offset);
                out.write(item.getKey() & 0xFF);
                out.write(chunk);
                out.write(value, offset, chunk);
                offset += chunk;
            } while (offset < value.length);
        }
        return out.toByteArray();
    }

    /** The items a TLV body carries, with the fragments of one item joined. */
    public static Map<Integer, byte[]> decode(byte[] bytes) {
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        int offset = 0;
        while (offset < bytes.length) {
            if (offset + 2 > bytes.length) {
                throw new IllegalArgumentException("a TLV item needs a type and a length");
            }
            int type = bytes[offset] & 0xFF;
            int length = bytes[offset + 1] & 0xFF;
            offset += 2;
            if (offset + length > bytes.length) {
                throw new IllegalArgumentException("truncated TLV item " + type);
            }
            byte[] value = Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            byte[] seen = items.get(type);
            items.put(type, seen == null ? value : AirplayCrypto.concat(seen, value));
        }
        return items;
    }

    /** A one byte value, which is what state and method are. */
    public static byte[] byteValue(int value) {
        return new byte[] {(byte) value};
    }

    /** The byte an item carries, or -1 when it is missing or empty. */
    public static int byteOf(Map<Integer, byte[]> items, int type) {
        byte[] value = items.get(type);
        return value == null || value.length == 0 ? -1 : value[0] & 0xFF;
    }

    /**
     * The value of an item, with its length checked.
     *
     * A length of 0 means "any"; anything else has to match, because a value of
     * the wrong size means the two sides are talking about different algorithms
     * (a 256-bit key where a 384-byte SRP public key was expected, say).
     */
    public static byte[] value(Map<Integer, byte[]> items, int type, int length) {
        byte[] value = items.get(type);
        if (value == null) {
            throw new IllegalStateException("the car did not send TLV item " + type);
        }
        if (length > 0 && value.length != length) {
            throw new IllegalStateException("TLV item " + type + " is " + value.length
                    + " bytes, expected " + length);
        }
        return value;
    }
}
