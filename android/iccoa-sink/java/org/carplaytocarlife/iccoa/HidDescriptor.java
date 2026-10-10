package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.List;

/**
 * A HID report descriptor parsed into the bit fields it describes.
 *
 * The car does not send fixed touch reports: in its requestUI command it hands
 * over a list of HID devices, each with a raw report descriptor, and every
 * report it sends afterwards has to be decoded against that descriptor. So the
 * decoder is built from the car's own descriptor, not from ours.
 *
 * Only what a touch or key report needs is understood: the main items Input and
 * Collection, the global report size/count/logical range/usage page, and the
 * local usage list. Anything else is walked past, and a descriptor with signed
 * coordinates or an unsupported field is rejected rather than misread.
 */
public final class HidDescriptor {

    /** One bit field inside a report. */
    public static final class Field {
        public final int page;
        public final int usage;
        public final int bitOffset;
        public final int bitSize;
        public final int logicalMin;
        public final int logicalMax;

        Field(int page, int usage, int bitOffset, int bitSize, int logicalMin,
                int logicalMax) {
            this.page = page;
            this.usage = usage;
            this.bitOffset = bitOffset;
            this.bitSize = bitSize;
            this.logicalMin = logicalMin;
            this.logicalMax = logicalMax;
        }

        public String describe() {
            return String.format("page=0x%02X usage=0x%02X bits=%d..%d range=%d..%d",
                    page, usage, bitOffset, bitOffset + bitSize - 1, logicalMin, logicalMax);
        }
    }

    public static final int PAGE_GENERIC_DESKTOP = 0x01;
    public static final int PAGE_DIGITIZER = 0x0D;

    public static final int USAGE_X = 0x30;
    public static final int USAGE_Y = 0x31;
    public static final int USAGE_TIP_SWITCH = 0x42;
    public static final int USAGE_CONTACT_ID = 0x51;

    private final List<Field> fields = new ArrayList<>();
    private int reportBits;

    private HidDescriptor() {
    }

    public List<Field> fields() {
        return fields;
    }

    /** The number of bits the descriptor fills in one report. */
    public int reportBits() {
        return reportBits;
    }

    /** The first field for a usage, or null. */
    public Field find(int page, int usage) {
        for (Field field : fields) {
            if (field.page == page && field.usage == usage) {
                return field;
            }
        }
        return null;
    }

    /** Every field for a usage, in report order (one per finger). */
    public List<Field> findAll(int page, int usage) {
        List<Field> out = new ArrayList<>();
        for (Field field : fields) {
            if (field.page == page && field.usage == usage) {
                out.add(field);
            }
        }
        return out;
    }

    /** True when the descriptor describes a touch screen we can decode. */
    public boolean isTouchScreen() {
        return find(PAGE_GENERIC_DESKTOP, USAGE_X) != null
                && find(PAGE_GENERIC_DESKTOP, USAGE_Y) != null;
    }

    public static HidDescriptor parse(byte[] descriptor) {
        if (descriptor == null || descriptor.length == 0 || descriptor.length > 4096) {
            throw new IllegalArgumentException("a descriptor has to be 1..4096 bytes");
        }
        HidDescriptor out = new HidDescriptor();
        int offset = 0;
        int usagePage = 0;
        int reportSize = 0;
        int reportCount = 0;
        int logicalMin = 0;
        int logicalMax = 0;
        int bitOffset = 0;
        List<Integer> usages = new ArrayList<>();
        int usageMin = -1;
        int usageMax = -1;

        while (offset < descriptor.length) {
            int prefix = descriptor[offset] & 0xFF;
            offset++;
            if (prefix == 0xFE) {
                throw new IllegalArgumentException("long HID items are not supported");
            }
            int size = prefix & 0x03;
            if (size == 3) {
                size = 4;
            }
            if (offset + size > descriptor.length) {
                throw new IllegalArgumentException("truncated HID descriptor");
            }
            int value = 0;
            for (int index = 0; index < size; index++) {
                value |= (descriptor[offset + index] & 0xFF) << (index * 8);
            }
            offset += size;
            int type = (prefix >> 2) & 3;
            int tag = prefix >> 4;
            switch (type) {
                case 0:  // main
                    if (tag == 8) {   // Input
                        if (reportSize < 1 || reportSize > 16 || reportCount < 1
                                || reportCount > 64
                                || bitOffset + reportSize * reportCount > 1024) {
                            throw new IllegalArgumentException("report size/count out of range");
                        }
                        boolean constant = (value & 0x01) != 0;
                        boolean variable = (value & 0x02) != 0;
                        boolean relative = (value & 0x04) != 0;
                        if (!constant) {
                            if (!variable || relative) {
                                throw new IllegalArgumentException(
                                        "only absolute variable fields are supported");
                            }
                            if (usages.size() != reportCount) {
                                throw new IllegalArgumentException("usage count does not match "
                                        + "report count (" + usages.size() + " vs "
                                        + reportCount + ")");
                            }
                            for (int index = 0; index < reportCount; index++) {
                                out.fields.add(new Field(usagePage, usages.get(index),
                                        bitOffset + index * reportSize, reportSize,
                                        logicalMin, logicalMax));
                            }
                        }
                        bitOffset += reportSize * reportCount;
                        usages.clear();
                        usageMin = -1;
                        usageMax = -1;
                    } else if (tag == 10 || tag == 11 || tag == 12) {
                        // Output, Collection, End Collection: acknowledged, nothing to collect
                        usages.clear();
                    }
                    break;
                case 1:  // global
                    switch (tag) {
                        case 0:
                            usagePage = value;
                            break;
                        case 1:
                            logicalMin = signed(value, size);
                            if (logicalMin < 0) {
                                // a negative logical minimum means signed
                                // coordinates, which the reference refuses too
                                throw new IllegalArgumentException(
                                        "signed coordinates are not supported");
                            }
                            break;
                        case 2:
                            logicalMax = signed(value, size);
                            break;
                        case 7:
                            reportSize = value;
                            break;
                        case 8:
                        case 10:
                        case 11:
                            // a report id shifts every field by a byte, and Push
                            // and Pop change the sizes the fields are read with;
                            // both would silently misread the report, so the
                            // descriptor is refused the way the reference does
                            throw new IllegalArgumentException(
                                    "report ids and the global stack are not supported");
                        case 9:
                            reportCount = value;
                            break;
                        default:
                            break;
                    }
                    break;
                case 2:  // local
                    switch (tag) {
                        case 0:
                            usages.add(value & 0xFFFF);
                            break;
                        case 1:
                            usageMin = value & 0xFFFF;
                            break;
                        case 2:
                            usageMax = value & 0xFFFF;
                            break;
                        default:
                            break;
                    }
                    if (usageMin >= 0 && usageMax >= usageMin) {
                        usages.clear();
                        for (int usage = usageMin; usage <= usageMax; usage++) {
                            usages.add(usage);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        out.reportBits = bitOffset;
        if (out.fields.isEmpty()) {
            throw new IllegalArgumentException("the descriptor describes no input fields");
        }
        return out;
    }

    /** A little endian item value, sign extended the way HID logical limits are. */
    private static int signed(int value, int size) {
        if (size == 4) {
            return value;
        }
        int shift = 32 - size * 8;
        return (value << shift) >> shift;
    }

    /**
     * Reads one field out of a report: bit i of the field is bit (offset+i)%8 of
     * byte (offset+i)/8, which is how HID packs them.
     */
    public static int value(byte[] report, Field field) {
        int result = 0;
        for (int index = 0; index < field.bitSize; index++) {
            int bit = field.bitOffset + index;
            int byteIndex = bit / 8;
            if (byteIndex >= report.length) {
                throw new IllegalArgumentException("report is shorter than the descriptor "
                        + "expects (" + report.length + " bytes)");
            }
            result |= ((report[byteIndex] >> (bit % 8)) & 1) << index;
        }
        return result;
    }

    /** Maps a raw value onto 0..scale-1 through the field's logical range. */
    public static int scale(Field field, int value, int scale) {
        if (scale <= 1 || field.logicalMax <= field.logicalMin) {
            return 0;
        }
        int clamped = Math.max(field.logicalMin, Math.min(field.logicalMax, value));
        return (clamped - field.logicalMin) * (scale - 1) / (field.logicalMax - field.logicalMin);
    }

    /**
     * Decodes a report into a touch, in the 0..4095 space the injector uses.
     *
     * A descriptor whose X/Y fields are missing is refused, and so is one whose
     * contact count we cannot match to a finger.
     */
    public HidTouch.Touch decodeTouch(byte[] report, int finger) {
        Field xField = nth(PAGE_GENERIC_DESKTOP, USAGE_X, finger);
        Field yField = nth(PAGE_GENERIC_DESKTOP, USAGE_Y, finger);
        if (xField == null || yField == null) {
            return null;
        }
        HidTouch.Touch touch = new HidTouch.Touch();
        Field contact = nth(PAGE_DIGITIZER, USAGE_CONTACT_ID, finger);
        touch.contactId = contact == null ? finger : value(report, contact);
        Field tip = nth(PAGE_DIGITIZER, USAGE_TIP_SWITCH, finger);
        touch.tip = tip == null || value(report, tip) != 0;
        touch.x = scale(xField, value(report, xField), HidTouch.RANGE + 1);
        touch.y = scale(yField, value(report, yField), HidTouch.RANGE + 1);
        return touch;
    }

    private Field nth(int page, int usage, int index) {
        List<Field> all = findAll(page, usage);
        return index < all.size() ? all.get(index) : null;
    }

    /** How many fingers the descriptor describes, by its contact fields. */
    public int fingers() {
        int contacts = findAll(PAGE_DIGITIZER, USAGE_CONTACT_ID).size();
        if (contacts > 0) {
            return contacts;
        }
        return Math.max(1, findAll(PAGE_GENERIC_DESKTOP, USAGE_X).size());
    }

    public String describe() {
        return "fields=" + fields.size() + " bits=" + reportBits + " fingers=" + fingers();
    }
}
