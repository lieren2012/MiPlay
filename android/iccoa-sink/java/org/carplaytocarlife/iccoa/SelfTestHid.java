package org.carplaytocarlife.iccoa;

/** Desktop self test for the HID touch descriptor and report decoder. */
public final class SelfTestHid {

    private static int failures;

    public static void main(String[] args) {
        byte[] descriptor = HidTouch.DESCRIPTOR;
        check("descriptor starts with digitizer/touch screen",
                (descriptor[0] & 0xFF) == 0x05 && (descriptor[1] & 0xFF) == 0x0D
                        && (descriptor[2] & 0xFF) == 0x09 && (descriptor[3] & 0xFF) == 0x04);
        check("descriptor opens two collections",
                count(descriptor, (byte) 0xA1) == 2 && count(descriptor, (byte) 0xC0) == 2);
        check("descriptor declares contact id, tip, X and Y usages",
                contains(descriptor, new byte[] {0x09, 0x51})
                        && contains(descriptor, new byte[] {0x09, 0x42})
                        && contains(descriptor, new byte[] {0x09, 0x30})
                        && contains(descriptor, new byte[] {0x09, 0x31}));
        check("descriptor declares a 4095 logical maximum",
                contains(descriptor, new byte[] {0x26, (byte) 0xFF, 0x0F}));

        HidTouch.Touch down = new HidTouch.Touch();
        down.contactId = 3;
        down.tip = true;
        down.x = 1000;
        down.y = 2000;
        HidTouch.Touch decoded = HidTouch.decode(down.report(), HidTouch.REPORT_LENGTH);
        check("report round trips contact id", decoded.contactId == 3);
        check("report round trips tip", decoded.tip);
        check("report round trips x/y", decoded.x == 1000 && decoded.y == 2000);
        check("down report is 6 bytes", down.report().length == HidTouch.REPORT_LENGTH);

        HidTouch.Touch up = new HidTouch.Touch();
        up.contactId = 3;
        up.tip = false;
        up.x = 4095;
        up.y = 4095;
        HidTouch.Touch decodedUp = HidTouch.decode(up.report(), HidTouch.REPORT_LENGTH);
        check("lift report clears the tip", !decodedUp.tip);
        check("maximum coordinates survive", decodedUp.x == 4095 && decodedUp.y == 4095);

        check("scaling maps 4095 to the last pixel", HidTouch.scale(4095, 1920) == 1919);
        check("scaling maps 0 to 0", HidTouch.scale(0, 720) == 0);
        check("scaling covers the middle", HidTouch.scale(2047, 1080) == 539);

        // ----------------------------------- the car's descriptor drives decoding
        HidDescriptor published = HidDescriptor.parse(HidTouch.DESCRIPTOR);
        check("our own descriptor parses into four fields", published.fields().size() == 4,
                published.describe());
        check("the contact identifier is one byte at bit 0",
                published.find(HidDescriptor.PAGE_DIGITIZER,
                        HidDescriptor.USAGE_CONTACT_ID).bitOffset == 0
                        && published.find(HidDescriptor.PAGE_DIGITIZER,
                                HidDescriptor.USAGE_CONTACT_ID).bitSize == 8);
        check("the tip switch is one bit at bit 8",
                published.find(HidDescriptor.PAGE_DIGITIZER,
                        HidDescriptor.USAGE_TIP_SWITCH).bitOffset == 8
                        && published.find(HidDescriptor.PAGE_DIGITIZER,
                                HidDescriptor.USAGE_TIP_SWITCH).bitSize == 1);
        check("X is sixteen bits at bit 16 with range 0..4095",
                published.find(HidDescriptor.PAGE_GENERIC_DESKTOP,
                        HidDescriptor.USAGE_X).bitOffset == 16
                        && published.find(HidDescriptor.PAGE_GENERIC_DESKTOP,
                                HidDescriptor.USAGE_X).bitSize == 16
                        && published.find(HidDescriptor.PAGE_GENERIC_DESKTOP,
                                HidDescriptor.USAGE_X).logicalMax == 4095);
        check("Y is sixteen bits at bit 32",
                published.find(HidDescriptor.PAGE_GENERIC_DESKTOP,
                        HidDescriptor.USAGE_Y).bitOffset == 32);
        check("the report is six bytes wide", published.reportBits() == 48);
        check("the descriptor describes one finger", published.fingers() == 1);
        check("and it is recognised as a touch screen", published.isTouchScreen());

        byte[] report = new byte[] {1, 1, 0x00, 0x08, 0x00, 0x04};
        HidTouch.Touch fromDescriptor = published.decodeTouch(report, 0);
        HidTouch.Touch fromCodec = HidTouch.decode(report, report.length);
        check("the descriptor and the codec agree on the same report",
                fromDescriptor.x == fromCodec.x && fromDescriptor.y == fromCodec.y
                        && fromDescriptor.tip == fromCodec.tip
                        && fromDescriptor.contactId == fromCodec.contactId);
        check("the report decodes to the value it carries",
                fromDescriptor.contactId == 1 && fromDescriptor.tip
                        && fromDescriptor.x == 2048 && fromDescriptor.y == 1024,
                fromDescriptor.describe());

        // a two finger descriptor, to check the field walk
        HidDescriptor two = HidDescriptor.parse(concat(
                new byte[] {0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01, 0x09, 0x22,
                        (byte) 0xA1, 0x02, 0x05, 0x0D, 0x09, 0x51, 0x09, 0x51, 0x15, 0x00,
                        0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x02, (byte) 0x81, 0x02},
                new byte[] {0x09, 0x42, 0x09, 0x42, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
                        (byte) 0x95, 0x02, (byte) 0x81, 0x02, 0x75, 0x06, (byte) 0x95, 0x01,
                        (byte) 0x81, 0x03},
                new byte[] {0x05, 0x01, 0x09, 0x30, 0x09, 0x30, 0x26, (byte) 0xFF, 0x0F,
                        0x75, 0x10, (byte) 0x95, 0x02, (byte) 0x81, 0x02},
                new byte[] {0x09, 0x31, 0x09, 0x31, 0x26, (byte) 0xFF, 0x0F, 0x75, 0x10,
                        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0, (byte) 0xC0}));
        check("a two finger descriptor parses", two.fields().size() == 8, two.describe());
        check("it knows about both fingers", two.fingers() == 2);
        check("the contacts and tips sit ahead of the coordinates",
                two.findAll(HidDescriptor.PAGE_DIGITIZER,
                        HidDescriptor.USAGE_CONTACT_ID).get(1).bitOffset == 8
                        && two.findAll(HidDescriptor.PAGE_DIGITIZER,
                                HidDescriptor.USAGE_TIP_SWITCH).get(1).bitOffset == 17);
        check("the second finger's fields sit after the first's",
                two.findAll(HidDescriptor.PAGE_GENERIC_DESKTOP,
                        HidDescriptor.USAGE_X).get(1).bitOffset == 40
                        && two.findAll(HidDescriptor.PAGE_GENERIC_DESKTOP,
                                HidDescriptor.USAGE_Y).get(1).bitOffset == 72);
        // two contacts, two tips, then the two fingers' X and Y
        byte[] twoReport = new byte[11];
        twoReport[0] = 1;
        twoReport[1] = 2;
        twoReport[2] = 0x03;
        putLe16(twoReport, 3, 100);
        putLe16(twoReport, 5, 200);
        putLe16(twoReport, 7, 300);
        putLe16(twoReport, 9, 400);
        HidTouch.Touch first = two.decodeTouch(twoReport, 0);
        HidTouch.Touch second = two.decodeTouch(twoReport, 1);
        check("both fingers decode with their own coordinates",
                first.contactId == 1 && first.x == 100 && first.y == 300
                        && second.contactId == 2 && second.x == 200 && second.y == 400,
                first.describe() + " / " + second.describe());

        // refusals: a descriptor we cannot trust is rejected instead of misread
        check("a descriptor with no input fields is refused",
                refused(new byte[] {0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01, (byte) 0xC0}));
        check("a truncated descriptor is refused",
                refused(new byte[] {0x05, 0x0D, 0x09}));
        check("a long item is refused",
                refused(new byte[] {(byte) 0xFE, 0x01, 0x02}));
        check("a signed logical range is refused",
                refused(concat(new byte[] {0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
                        0x09, 0x51, 0x15, (byte) 0xFF, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95,
                        0x01, (byte) 0x81, 0x02}, new byte[] {(byte) 0xC0})));
        check("a descriptor with a report id is refused",
                refused(concat(new byte[] {0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
                        (byte) 0x85, 0x01, 0x09, 0x51, 0x15, 0x00, 0x25, 0x7F, 0x75, 0x08,
                        (byte) 0x95, 0x01, (byte) 0x81, 0x02, (byte) 0xC0})));
        check("a descriptor that pushes global state is refused",
                refused(concat(new byte[] {0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
                        0x09, 0x51, 0x15, 0x00, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x01,
                        (byte) 0x81, 0x02}, new byte[] {(byte) 0xA4, (byte) 0xC0})));
        check("a report shorter than the descriptor expects is refused",
                refusedReport(HidDescriptor.parse(HidTouch.DESCRIPTOR), new byte[] {1, 1, 0}));

        check("a constant only report is refused",
                refused(new byte[] {0x75, 0x08, (byte) 0x95, 0x02, (byte) 0x81, 0x01}));

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST HID OK" : ("SELFTEST HID FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int count(byte[] data, byte value) {
        int total = 0;
        for (byte item : data) {
            if (item == value) {
                total++;
            }
        }
        return total;
    }

    private static boolean contains(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** True when decoding throws instead of reading past the end of the report. */
    private static boolean refusedReport(HidDescriptor descriptor, byte[] report) {
        try {
            descriptor.decodeTouch(report, 0);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static boolean refused(byte[] descriptor) {
        try {
            HidDescriptor.parse(descriptor);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void putLe16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
