package org.carplaytocarlife.iccoa;

/**
 * The HID touch descriptor this app publishes to the car, and the decoder for
 * the reports the car sends back.
 *
 * The reference CarWith -> CarPlay app decodes the car's reports through a field
 * map taken from exactly this kind of descriptor (see its TouchReports: page 13
 * usage 51 = contact identifier, page 1 usage 48 = X, and the tip switch), so
 * the layout below follows the standard HID touch screen pattern:
 *
 * <pre>
 *   report byte 0      contact identifier (0..127)
 *   report byte 1      tip switch in bit 0, 7 bits of padding
 *   report bytes 2-3   X, logical 0..4095
 *   report bytes 4-5   Y, logical 0..4095
 * </pre>
 */
public final class HidTouch {

    public static final int REPORT_LENGTH = 6;
    public static final int RANGE = 4095;

    /** A single-touch digitizer descriptor, 32 bytes. */
    public static final byte[] DESCRIPTOR = new byte[] {
        0x05, 0x0D,             // usage page: digitizer
        0x09, 0x04,             // usage: touch screen
        (byte) 0xA1, 0x01,      // collection: application
        0x09, 0x22,             //   usage: finger
        (byte) 0xA1, 0x02,      //   collection: logical
        0x09, 0x51,             //     usage: contact identifier
        0x15, 0x00,             //     logical minimum 0
        0x25, 0x7F,             //     logical maximum 127
        0x75, 0x08,             //     report size 8
        (byte) 0x95, 0x01,      //     report count 1
        (byte) 0x81, 0x02,      //     input: data, variable, absolute
        0x09, 0x42,             //     usage: tip switch
        0x15, 0x00,             //     logical minimum 0
        0x25, 0x01,             //     logical maximum 1
        0x75, 0x01,             //     report size 1
        (byte) 0x95, 0x01,      //     report count 1
        (byte) 0x81, 0x02,      //     input: data, variable, absolute
        0x75, 0x07,             //     report size 7 padding
        (byte) 0x95, 0x01,      //     report count 1
        (byte) 0x81, 0x03,      //     input: constant
        0x05, 0x01,             //     usage page: generic desktop
        0x09, 0x30,             //     usage: X
        0x26, (byte) 0xFF, 0x0F,//     logical maximum 4095
        0x75, 0x10,             //     report size 16
        (byte) 0x95, 0x01,      //     report count 1
        (byte) 0x81, 0x02,      //     input: data, variable, absolute
        0x09, 0x31,             //     usage: Y
        0x26, (byte) 0xFF, 0x0F,//     logical maximum 4095
        0x75, 0x10,             //     report size 16
        (byte) 0x95, 0x01,      //     report count 1
        (byte) 0x81, 0x02,      //     input: data, variable, absolute
        (byte) 0xC0,            //   end collection
        (byte) 0xC0             // end collection
    };

    public static final class Touch {
        public int contactId;
        public boolean tip;
        public int x;
        public int y;

        public String describe() {
            return "touch id=" + contactId + " " + (tip ? "down" : "up")
                    + " x=" + x + " y=" + y;
        }

        /**
         * The report for this touch.
         *
         * HID packs a multi byte field least significant byte first, and the
         * descriptor reads it back that way, so the two have to agree.
         */
        public byte[] report() {
            byte[] out = new byte[REPORT_LENGTH];
            out[0] = (byte) (contactId & 0x7F);
            out[1] = (byte) (tip ? 0x01 : 0x00);
            out[2] = (byte) (x & 0xFF);
            out[3] = (byte) ((x >> 8) & 0xFF);
            out[4] = (byte) (y & 0xFF);
            out[5] = (byte) ((y >> 8) & 0xFF);
            return out;
        }
    }

    private HidTouch() {
    }

    public static Touch decode(byte[] report, int length) {
        if (length < REPORT_LENGTH) {
            throw new IllegalArgumentException("touch report needs " + REPORT_LENGTH
                    + " bytes, got " + length);
        }
        Touch touch = new Touch();
        touch.contactId = report[0] & 0x7F;
        touch.tip = (report[1] & 0x01) != 0;
        touch.x = (report[2] & 0xFF) | ((report[3] & 0xFF) << 8);
        touch.y = (report[4] & 0xFF) | ((report[5] & 0xFF) << 8);
        return touch;
    }

    /** Scales a normalised 0..4095 coordinate into an arbitrary screen size. */
    public static int scale(int value, int target) {
        if (target <= 1) {
            return 0;
        }
        int scaled = (int) ((long) value * (target - 1) / RANGE);
        return Math.max(0, Math.min(target - 1, scaled));
    }
}
