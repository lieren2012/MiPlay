package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * iAP2 framing: the link layer plus the control (message) layer.
 *
 * The control layer format comes from the reference CarWith -> CarPlay app
 * (its Iap2Codec), which builds a message as
 *
 * <pre>
 *   40 40 | length(2) | command(2) | for each parameter: length(2) | id(2) | value
 * </pre>
 *
 * where each parameter length counts the four header bytes of the parameter, and
 * the trailing checksum is the 8-bit two's complement of the sum of the bytes.
 * The flag constants (SYN 128, ACK 64, EAK 32, RST 16) are the reference's too.
 *
 * The link header layout (start 0xFF, total length, control, sequence, ack,
 * session, header checksum, payload, payload checksum) follows the publicly
 * documented iAP2 framing; it is marked unverified until a car confirms it.
 */
public final class Iap2 {

    public static final int START = 0xFF;
    public static final int SYN = 128;
    public static final int ACK = 64;
    public static final int EAK = 32;
    public static final int RST = 16;
    public static final int CONTROL_HEADER = 0x4040;
    public static final int LINK_HEADER_LENGTH = 10;

    public static final class Parameter {
        public final int id;
        public final byte[] value;

        public Parameter(int id, byte[] value) {
            this.id = id;
            this.value = value;
        }
    }

    public static final class Message {
        public int command;
        public final List<Parameter> parameters = new ArrayList<>();

        public Parameter find(int id) {
            for (Parameter parameter : parameters) {
                if (parameter.id == id) {
                    return parameter;
                }
            }
            return null;
        }
    }

    public static final class Frame {
        public int control;
        public int sequence;
        public int ack;
        public int session;
        public byte[] payload = new byte[0];

        public boolean has(int flag) {
            return (control & flag) != 0;
        }

        public String describe() {
            StringBuilder flags = new StringBuilder();
            if (has(SYN)) {
                flags.append("SYN|");
            }
            if (has(ACK)) {
                flags.append("ACK|");
            }
            if (has(EAK)) {
                flags.append("EAK|");
            }
            if (has(RST)) {
                flags.append("RST|");
            }
            if (flags.length() == 0) {
                flags.append("DATA");
            } else {
                flags.setLength(flags.length() - 1);
            }
            return flags + " seq=" + sequence + " ack=" + ack + " session=" + session
                    + " payload=" + payload.length;
        }
    }

    private Iap2() {
    }

    /** The reference's checksum: 8-bit two's complement of the byte sum. */
    public static byte checksum(byte[] bytes) {
        int sum = 0;
        for (byte value : bytes) {
            sum += value & 0xFF;
        }
        return (byte) (-sum);
    }

    public static void writeWord(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    public static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /** Builds a control message payload (what goes inside a link frame). */
    public static byte[] control(int command, List<Parameter> parameters) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Parameter parameter : parameters) {
            byte[] value = parameter.value == null ? new byte[0] : parameter.value;
            writeWord(body, value.length + 4);
            writeWord(body, parameter.id);
            body.write(value, 0, value.length);
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeWord(out, CONTROL_HEADER);
        writeWord(out, payload.length + 6);
        writeWord(out, command);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    public static Message decodeControl(byte[] data) {
        if (data.length < 6 || readWord(data, 0) != CONTROL_HEADER) {
            throw new IllegalArgumentException("not an iAP2 control message");
        }
        if (readWord(data, 2) != data.length) {
            throw new IllegalArgumentException("control length mismatch: header says "
                    + readWord(data, 2) + ", have " + data.length);
        }
        Message message = new Message();
        message.command = readWord(data, 4);
        int offset = 6;
        while (offset + 4 <= data.length) {
            int length = readWord(data, offset);
            int id = readWord(data, offset + 2);
            if (length < 4 || offset + length > data.length) {
                throw new IllegalArgumentException("bad parameter length " + length
                        + " at offset " + offset);
            }
            byte[] value = new byte[length - 4];
            System.arraycopy(data, offset + 4, value, 0, value.length);
            message.parameters.add(new Parameter(id, value));
            offset += length;
        }
        return message;
    }

    /**
     * Finds the control session id in an identification payload, mirroring the
     * reference implementation: byte 0 must be 1, the advertised length at least
     * 10, the body a multiple of three from offset 10, and the triple whose last
     * two bytes are (0, 2) carries the session id.
     */
    public static int controlSession(byte[] payload) {
        if (payload.length < 13 || payload[0] != 1 || (payload.length - 10) % 3 != 0) {
            throw new IllegalArgumentException("unsupported identification payload");
        }
        if (readWord(payload, 2) < 10 || (payload[1] & 0xFF) <= 0) {
            throw new IllegalArgumentException("identification payload too small");
        }
        for (int offset = 10; offset + 2 < payload.length; offset += 3) {
            if (payload[offset + 1] == 0 && payload[offset + 2] == 2) {
                return payload[offset] & 0xFF;
            }
        }
        throw new IllegalStateException("peer does not advertise a control session");
    }

    /** Wraps a payload in a link frame. */
    public static byte[] frame(int control, int sequence, int ack, int session, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        int total = LINK_HEADER_LENGTH + body.length + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        out.write(START);
        writeWord(out, total);
        out.write(control & 0xFF);
        out.write(sequence & 0xFF);
        writeWord(out, ack);
        out.write(session & 0xFF);
        byte[] header = new byte[8];
        for (int i = 0; i < 8; i++) {
            header[i] = (byte) out.toByteArray()[i];
        }
        writeWord(out, sum16(header));
        out.write(body, 0, body.length);
        out.write(checksum(body));
        return out.toByteArray();
    }

    public static Frame parse(byte[] data) {
        if (data.length < LINK_HEADER_LENGTH + 1 || (data[0] & 0xFF) != START) {
            throw new IllegalArgumentException("not an iAP2 link frame");
        }
        int total = readWord(data, 1);
        if (total > data.length) {
            throw new IllegalArgumentException("frame truncated: header says " + total
                    + ", have " + data.length);
        }
        Frame frame = new Frame();
        frame.control = data[3] & 0xFF;
        frame.sequence = data[4] & 0xFF;
        frame.ack = readWord(data, 5);
        frame.session = data[7] & 0xFF;
        int headerSum = readWord(data, 8);
        int expected = sum16(java.util.Arrays.copyOfRange(data, 0, 8));
        if (headerSum != expected) {
            throw new IllegalArgumentException("header checksum mismatch: " + headerSum
                    + " vs " + expected);
        }
        int payloadLength = total - LINK_HEADER_LENGTH - 1;
        frame.payload = new byte[payloadLength];
        System.arraycopy(data, LINK_HEADER_LENGTH, frame.payload, 0, payloadLength);
        byte expectedPayload = checksum(frame.payload);
        if (data[total - 1] != expectedPayload) {
            throw new IllegalArgumentException("payload checksum mismatch");
        }
        return frame;
    }

    /** 16-bit sum used by the link header. */
    public static int sum16(byte[] bytes) {
        int sum = 0;
        for (byte value : bytes) {
            sum = (sum + (value & 0xFF)) & 0xFFFF;
        }
        return sum;
    }
}
