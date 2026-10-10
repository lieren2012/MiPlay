package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal multicast DNS codec and responder.
 *
 * The CarPlay side is discovered over mDNS: the reference CarWith -> CarPlay app
 * advertised {@code CarPlay Android <id>._carplay-ctrl._tcp.local} with host
 * {@code carplay-<id>.local} (see docs/apk-analysis-carwith-carplay.md), so this
 * class builds exactly those records. Names are written uncompressed, which is
 * legal and keeps the encoder small; the parser understands compression so it
 * can read queries from real stack implementations.
 */
public final class Mdns {

    public static final String ADDRESS = "224.0.0.251";
    public static final int PORT = 5353;
    public static final int TYPE_A = 1;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_SRV = 33;
    public static final int CLASS_IN = 1;

    public static final class Question {
        public String name;
        public int type;
        /** The question class, top bit included: that bit asks for a unicast answer. */
        public int qclass = CLASS_IN;

        public Question(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    public static final class Record {
        public String name;
        public int type;
        public int ttl;
        public byte[] rdata;

        public Record(String name, int type, int ttl, byte[] rdata) {
            this.name = name;
            this.type = type;
            this.ttl = ttl;
            this.rdata = rdata;
        }

        public String target() {
            return type == TYPE_PTR || type == TYPE_SRV ? name(rdata, type == TYPE_SRV ? 6 : 0) : null;
        }

        public int port() {
            if (type != TYPE_SRV) {
                return -1;
            }
            // SRV rdata: priority(2) weight(2) port(2) target(name); the port is
            // at a fixed offset, the name comes after it.
            return ((rdata[4] & 0xFF) << 8) | (rdata[5] & 0xFF);
        }

        public byte[] address() {
            return type == TYPE_A ? rdata : null;
        }
    }

    public static final class Message {
        public int id;
        public boolean response;
        public final List<Question> questions = new ArrayList<>();
        public final List<Record> records = new ArrayList<>();
    }

    private Mdns() {
    }

    public static byte[] buildQuery(String serviceType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);
        writeShort(out, 0);      // standard query
        writeShort(out, 1);      // one question
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeName(out, serviceType);
        writeShort(out, TYPE_PTR);
        writeShort(out, CLASS_IN);
        return out.toByteArray();
    }

    /** Builds a response for one PTR question, mirroring the reference app. */
    public static byte[] buildResponse(Question question, String instance, String host,
            int port, byte[] ipv4) {
        String service = question.name;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0);
        writeShort(out, 0x8400);   // response, authoritative
        writeShort(out, 0);        // no questions echoed
        writeShort(out, 1);        // PTR
        writeShort(out, 0);
        writeShort(out, 3);        // SRV + TXT + A as additional records

        writeName(out, service);
        writeShort(out, TYPE_PTR);
        writeShort(out, CLASS_IN);
        writeInt(out, 4500);
        byte[] ptr = new byte[nameLength(instance)];
        writeNameInto(ptr, 0, instance);
        writeShort(out, ptr.length);
        out.write(ptr, 0, ptr.length);

        writeName(out, instance);
        writeShort(out, TYPE_SRV);
        writeShort(out, CLASS_IN);
        writeInt(out, 4500);
        byte[] srv = new byte[6 + nameLength(host)];
        writeShortInto(srv, 0, 0);      // priority
        writeShortInto(srv, 2, 0);      // weight
        writeShortInto(srv, 4, port);
        writeNameInto(srv, 6, host);
        writeShort(out, srv.length);
        out.write(srv, 0, srv.length);

        writeName(out, instance);
        writeShort(out, TYPE_TXT);
        writeShort(out, CLASS_IN);
        writeInt(out, 4500);
        ByteArrayOutputStream txt = new ByteArrayOutputStream();
        for (String entry : new String[] {"deviceid=" + shortId(instance), "srcvers=0.1.0"}) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            txt.write(bytes.length);
            txt.write(bytes, 0, bytes.length);
        }
        byte[] txtBytes = txt.toByteArray();
        writeShort(out, txtBytes.length);
        out.write(txtBytes, 0, txtBytes.length);

        writeName(out, host);
        writeShort(out, TYPE_A);
        writeShort(out, CLASS_IN);
        writeInt(out, 120);
        writeShort(out, 4);
        out.write(ipv4, 0, 4);
        return out.toByteArray();
    }

    public static Message parse(byte[] data) {
        Message message = new Message();
        message.id = readShort(data, 0);
        int flags = readShort(data, 2);
        message.response = (flags & 0x8000) != 0;
        int questions = readShort(data, 4);
        int answers = readShort(data, 6);
        int authority = readShort(data, 8);
        int additional = readShort(data, 10);
        int offset = 12;
        for (int i = 0; i < questions; i++) {
            String[] name = new String[] {""};
            offset = readName(data, offset, name);
            int type = readShort(data, offset);
            int qclass = readShort(data, offset + 2);
            offset += 4;
            Question question = new Question(name[0], type);
            question.qclass = qclass;
            message.questions.add(question);
        }
        int total = answers + authority + additional;
        for (int i = 0; i < total; i++) {
            String[] name = new String[] {""};
            offset = readName(data, offset, name);
            int type = readShort(data, offset);
            offset += 2;
            offset += 2;                        // class
            int ttl = readInt(data, offset);
            offset += 4;
            int length = readShort(data, offset);
            offset += 2;
            byte[] rdata = new byte[length];
            System.arraycopy(data, offset, rdata, 0, length);
            offset += length;
            message.records.add(new Record(name[0], type, ttl, rdata));
        }
        return message;
    }

    /** The instance name the reference app used, e.g. {@code CarPlay Android 3f2a...._carplay-ctrl._tcp.local}. */
    public static String instanceName(String id) {
        return "CarPlay Android " + id + "._carplay-ctrl._tcp.local";
    }

    public static String hostName(String id) {
        return "carplay-" + id + ".local";
    }

    public static String shortId(String instance) {
        int start = instance.indexOf("Android ");
        if (start < 0) {
            return "0";
        }
        int end = instance.indexOf("._", start);
        return end < 0 ? instance.substring(start + 8) : instance.substring(start + 8, end);
    }

    // ------------------------------------------------------------- encoding

    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) {
                continue;
            }
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            out.write(bytes.length & 0x3F);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
    }

    private static int nameLength(String name) {
        int total = 1;
        for (String label : name.split("\\.")) {
            if (!label.isEmpty()) {
                total += 1 + label.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private static void writeNameInto(byte[] target, int offset, String name) {
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) {
                continue;
            }
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            target[offset++] = (byte) (bytes.length & 0x3F);
            System.arraycopy(bytes, 0, target, offset, bytes.length);
            offset += bytes.length;
        }
        target[offset] = 0;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShortInto(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    // ------------------------------------------------------------- decoding

    private static int readName(byte[] data, int offset, String[] out) {
        StringBuilder name = new StringBuilder();
        int jumps = 0;
        while (true) {
            int length = data[offset] & 0xFF;
            if (length == 0) {
                offset++;
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                int pointer = ((length & 0x3F) << 8) | (data[offset + 1] & 0xFF);
                if (jumps++ == 0) {
                    offset += 2;
                }
                String[] tail = new String[] {""};
                readName(data, pointer, tail);
                name.append(tail[0]);
                break;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(data, offset + 1, length, StandardCharsets.UTF_8));
            offset += 1 + length;
        }
        out[0] = name.toString();
        return offset;
    }

    private static int skipName(byte[] data, int offset) {
        while (true) {
            int length = data[offset] & 0xFF;
            if (length == 0) {
                return offset + 1;
            }
            if ((length & 0xC0) == 0xC0) {
                return offset + 2;
            }
            offset += 1 + length;
        }
    }

    private static String name(byte[] rdata, int offset) {
        String[] out = new String[] {""};
        if (offset > 0) {
            byte[] tail = new byte[rdata.length - offset];
            System.arraycopy(rdata, offset, tail, 0, tail.length);
            readName(tail, 0, out);
        } else {
            readName(rdata, 0, out);
        }
        return out[0];
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
