package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal binary property list codec.
 *
 * AirPlay uses binary plists for its RTSP bodies, so the CarPlay sender side
 * needs one. Only the types AirPlay actually carries are implemented: dict,
 * array, string, data, integer, real, bool and null. The encoder emits
 * uncompressed, non-deduplicated objects with one-byte references, which is
 * valid (if slightly larger than Apple's output) and decodes everywhere.
 */
public final class Bplist {

    public static final class Node {
        public final Object value;

        private Node(Object value) {
            this.value = value;
        }

        public static Node dict(Map<String, Node> entries) {
            return new Node(entries);
        }

        public static Node array(List<Node> items) {
            return new Node(items);
        }

        public static Node string(String text) {
            return new Node(text);
        }

        public static Node data(byte[] bytes) {
            return new Node(bytes);
        }

        public static Node integer(long number) {
            return new Node(number);
        }

        public static Node real(double number) {
            return new Node(number);
        }

        public static Node bool(boolean flag) {
            return new Node(flag);
        }

        public static Node nul() {
            return new Node(null);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Node> asDict() {
            return (Map<String, Node>) value;
        }

        @SuppressWarnings("unchecked")
        public List<Node> asArray() {
            return (List<Node>) value;
        }

        public String asString() {
            return (String) value;
        }

        public byte[] asData() {
            return (byte[]) value;
        }

        public long asInteger() {
            return ((Number) value).longValue();
        }

        public boolean asBool() {
            return value instanceof Boolean && (Boolean) value;
        }
    }

    private Bplist() {
    }

    public static byte[] encode(Node root) {
        List<Object> objects = new ArrayList<>();
        flatten(root, objects);
        Map<Object, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < objects.size(); i++) {
            Object object = objects.get(i);
            if (!(object instanceof Map) && !(object instanceof List) && !index.containsKey(object)) {
                index.put(object, i);
            } else if (object instanceof Map || object instanceof List) {
                index.put(object, i);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b');
        out.write('p');
        out.write('l');
        out.write('i');
        out.write('s');
        out.write('t');
        out.write('0');
        out.write('0');

        List<Integer> offsets = new ArrayList<>();
        for (Object object : objects) {
            offsets.add(out.size());
            writeObject(out, object, index);
        }

        int offsetTableOffset = out.size();
        int refSize = objects.size() < 256 ? 1 : 2;
        int offsetSize = offsetTableOffset < 256 ? 1 : (offsetTableOffset < 65536 ? 2 : 4);
        for (int offset : offsets) {
            writeSized(out, offset, offsetSize);
        }

        // trailer: 6 unused bytes, offset size, ref size, counts and offsets
        out.write(new byte[6], 0, 6);
        out.write(offsetSize);
        out.write(refSize);
        writeLong(out, objects.size());
        writeLong(out, 0);
        writeLong(out, offsetTableOffset);
        return out.toByteArray();
    }

    private static void flatten(Node node, List<Object> objects) {
        Object value = node.value;
        if (value instanceof Map) {
            Map<String, Node> map = node.asDict();
            objects.add(map);
            for (Map.Entry<String, Node> entry : map.entrySet()) {
                flatten(Node.string(entry.getKey()), objects);
                flatten(entry.getValue(), objects);
            }
        } else if (value instanceof List) {
            List<Node> items = node.asArray();
            objects.add(items);
            for (Node item : items) {
                flatten(item, objects);
            }
        } else {
            objects.add(value);
        }
    }

    private static void writeObject(ByteArrayOutputStream out, Object object, Map<Object, Integer> index) {
        if (object == null) {
            out.write(0x00);
        } else if (object instanceof Boolean) {
            out.write((Boolean) object ? 0x09 : 0x08);
        } else if (object instanceof Long) {
            long value = (Long) object;
            // Binary plists have no inline integer form: 0x1N always means an
            // integer of 2^N bytes, and there is no signed type at all, so a
            // negative value has to go out as eight byte two's complement.
            if (value < 0) {
                out.write(0x13);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (value >> shift) & 0xFF);
                }
            } else if (value <= 0xFF) {
                out.write(0x10);            // 2^0 = one byte
                out.write((int) value);
            } else if (value <= 0xFFFF) {
                out.write(0x11);            // 2^1 = two bytes
                out.write((int) (value >> 8) & 0xFF);
                out.write((int) value & 0xFF);
            } else if (value <= 0xFFFFFFFFL) {
                out.write(0x12);            // 2^2 = four bytes
                for (int shift = 24; shift >= 0; shift -= 8) {
                    out.write((int) (value >> shift) & 0xFF);
                }
            } else {
                out.write(0x13);            // 2^3 = eight bytes
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (value >> shift) & 0xFF);
                }
            }
        } else if (object instanceof Double) {
            out.write(0x23);
            long bits = Double.doubleToLongBits((Double) object);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (bits >> shift) & 0xFF);
            }
        } else if (object instanceof String) {
            byte[] bytes = ((String) object).getBytes(StandardCharsets.UTF_8);
            writeLength(out, 0x5, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else if (object instanceof byte[]) {
            byte[] bytes = (byte[]) object;
            writeLength(out, 0x4, bytes.length);
            out.write(bytes, 0, bytes.length);
        } else if (object instanceof Map) {
            Map<String, Node> map = (Map<String, Node>) object;
            writeLength(out, 0xD, map.size());
            // binary plists store all key references first, then all values
            for (String key : map.keySet()) {
                out.write(refIndex(index, key));
            }
            for (Map.Entry<String, Node> entry : map.entrySet()) {
                out.write(refIndex(index, entry.getValue().value));
            }
        } else if (object instanceof List) {
            List<Node> items = (List<Node>) object;
            writeLength(out, 0xA, items.size());
            for (Node item : items) {
                out.write(refIndex(index, item.value));
            }
        } else {
            throw new IllegalStateException("unsupported plist value: " + object.getClass());
        }
    }

    private static int refIndex(Map<Object, Integer> index, Object value) {
        Integer found = index.get(value);
        if (found == null) {
            throw new IllegalStateException("object was not flattened: " + value);
        }
        return found;
    }

    private static void writeLength(ByteArrayOutputStream out, int type, int length) {
        if (length < 15) {
            out.write(type << 4 | length);
        } else if (length <= 0xFF) {
            out.write(type << 4 | 0x0F);
            out.write(0x10);          // 2^0 = one length byte
            out.write(length);
        } else {
            out.write(type << 4 | 0x0F);
            out.write(0x11);          // 2^1 = two length bytes
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    private static void writeSized(ByteArrayOutputStream out, int value, int size) {
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) {
            out.write((value >> shift) & 0xFF);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >> shift) & 0xFF);
        }
    }

    /** Decodes the subset of the format the encoder above produces. */
    public static Node decode(byte[] bytes) {
        if (bytes.length < 40 || bytes[0] != 'b') {
            throw new IllegalArgumentException("not a binary plist");
        }
        int trailer = bytes.length - 32;
        int offsetSize = bytes[trailer + 6] & 0xFF;
        int refSize = bytes[trailer + 7] & 0xFF;
        long objectCount = readLong(bytes, trailer + 8);
        long topObject = readLong(bytes, trailer + 16);
        long tableOffset = readLong(bytes, trailer + 24);
        int[] offsets = new int[(int) objectCount];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = (int) readLong(bytes, (int) (tableOffset + (long) i * offsetSize), offsetSize);
        }
        return readObject(bytes, offsets[(int) topObject], refSize, offsets);
    }

    private static Node readObject(byte[] bytes, int offset, int refSize, int[] offsets) {
        int marker = bytes[offset] & 0xFF;
        int type = marker >> 4;
        int info = marker & 0x0F;
        switch (type) {
            case 0x0: {
                if (info == 0x0) {
                    return Node.nul();
                }
                return Node.bool(info == 0x9);
            }
            case 0x1: {
                int size = 1 << info;
                long value = 0;
                for (int i = 0; i < size; i++) {
                    value = (value << 8) | (bytes[offset + 1 + i] & 0xFF);
                }
                return Node.integer(value);
            }
            case 0x2: {
                int size = 1 << info;
                long bits = 0;
                for (int i = 0; i < size; i++) {
                    bits = (bits << 8) | (bytes[offset + 1 + i] & 0xFF);
                }
                return Node.real(Double.longBitsToDouble(bits));
            }
            case 0x4:
            case 0x5:
            case 0x6: {
                int[] cursor = new int[] {offset + 1};
                int length = readLength(bytes, info, cursor);
                byte[] payload = new byte[length];
                System.arraycopy(bytes, cursor[0], payload, 0, length);
                return type == 0x4 ? Node.data(payload) : Node.string(new String(payload, StandardCharsets.UTF_8));
            }
            case 0xA: {
                int[] cursor = new int[] {offset + 1};
                int length = readLength(bytes, info, cursor);
                List<Node> items = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    int ref = bytes[cursor[0] + i * refSize] & 0xFF;
                    items.add(readObject(bytes, offsets[ref], refSize, offsets));
                }
                return Node.array(items);
            }
            case 0xD: {
                int[] cursor = new int[] {offset + 1};
                int length = readLength(bytes, info, cursor);
                Map<String, Node> map = new LinkedHashMap<>();
                String[] keys = new String[length];
                for (int i = 0; i < length; i++) {
                    int keyRef = bytes[cursor[0] + i * refSize] & 0xFF;
                    keys[i] = readObject(bytes, offsets[keyRef], refSize, offsets).asString();
                }
                for (int i = 0; i < length; i++) {
                    int valueRef = bytes[cursor[0] + (length + i) * refSize] & 0xFF;
                    map.put(keys[i], readObject(bytes, offsets[valueRef], refSize, offsets));
                }
                return Node.dict(map);
            }
            default:
                throw new IllegalArgumentException("unsupported plist marker 0x"
                        + Integer.toHexString(marker));
        }
    }

    private static int readLength(byte[] bytes, int info, int[] cursor) {
        if (info != 0x0F) {
            return info;
        }
        int marker = bytes[cursor[0]] & 0xFF;
        cursor[0]++;
        int size = 1 << (marker & 0x0F);
        int length = 0;
        for (int i = 0; i < size; i++) {
            length = (length << 8) | (bytes[cursor[0] + i] & 0xFF);
        }
        cursor[0] += size;
        return length;
    }

    private static long readLong(byte[] bytes, int offset) {
        return readLong(bytes, offset, 8);
    }

    private static long readLong(byte[] bytes, int offset, int size) {
        long value = 0;
        for (int i = 0; i < size; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
