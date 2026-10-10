package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.List;

/** Accumulates bytes from the RFCOMM link and yields complete iAP2 frames. */
public final class Iap2Reader {

    private byte[] buffer = new byte[4096];
    private int size;

    public List<Iap2.Frame> feed(byte[] data, int length) {
        List<Iap2.Frame> frames = new ArrayList<>();
        ensure(size + length);
        System.arraycopy(data, 0, buffer, size, length);
        size += length;

        int consumed = 0;
        while (size - consumed >= Iap2.LINK_HEADER_LENGTH + 1) {
            if ((buffer[consumed] & 0xFF) != Iap2.START) {
                consumed++;
                continue;
            }
            int total = Iap2.readWord(buffer, consumed + 1);
            if (total < Iap2.LINK_HEADER_LENGTH + 1) {
                throw new IllegalStateException("implausible iAP2 frame length " + total);
            }
            if (size - consumed < total) {
                break;
            }
            byte[] frame = new byte[total];
            System.arraycopy(buffer, consumed, frame, 0, total);
            frames.add(Iap2.parse(frame));
            consumed += total;
        }
        if (consumed > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, size - consumed);
            size -= consumed;
        }
        return frames;
    }

    private void ensure(int capacity) {
        if (capacity <= buffer.length) {
            return;
        }
        int next = buffer.length;
        while (next < capacity) {
            next *= 2;
        }
        byte[] grown = new byte[next];
        System.arraycopy(buffer, 0, grown, 0, size);
        buffer = grown;
    }

    /**
     * Accumulates the control container ({@code 40 40}) used on the control
     * channel. The reference app's ControlDecoder does exactly this and hands
     * each complete message to the same control decoder.
     */
    public static final class ControlReader {
        private byte[] buffer = new byte[8192];
        private int size;

        public java.util.List<Iap2.Message> feed(byte[] data, int length) {
            java.util.List<Iap2.Message> messages = new ArrayList<>();
            if (size + length > buffer.length) {
                int next = buffer.length;
                while (next < size + length) {
                    next *= 2;
                }
                byte[] grown = new byte[next];
                System.arraycopy(buffer, 0, grown, 0, size);
                buffer = grown;
            }
            System.arraycopy(data, 0, buffer, size, length);
            size += length;
            if (size > 131070) {
                throw new IllegalStateException("control input exceeded the buffer limit");
            }

            int consumed = 0;
            while (size - consumed >= 6) {
                if (Iap2.readWord(buffer, consumed) != Iap2.CONTROL_HEADER) {
                    throw new IllegalArgumentException("invalid control message marker");
                }
                int total = Iap2.readWord(buffer, consumed + 2);
                if (total < 6) {
                    throw new IllegalArgumentException("control message length " + total);
                }
                if (size - consumed < total) {
                    break;
                }
                byte[] message = new byte[total];
                System.arraycopy(buffer, consumed, message, 0, total);
                messages.add(Iap2.decodeControl(message));
                consumed += total;
            }
            if (consumed > 0) {
                System.arraycopy(buffer, consumed, buffer, 0, size - consumed);
                size -= consumed;
            }
            return messages;
        }
    }
}
