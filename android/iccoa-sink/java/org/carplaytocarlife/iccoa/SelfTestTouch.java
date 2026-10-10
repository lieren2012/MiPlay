package org.carplaytocarlife.iccoa;

/** Checks the touch mapping and gesture shaping that do not need Android. */
public final class SelfTestTouch {

    private static int failures;

    public static void main(String[] args) {
        check("a quick tap still lasts a frame",
                TouchInjector.durationMs(0) == TouchInjector.MIN_DURATION_MS);
        check("a long press is capped",
                TouchInjector.durationMs(90000) == TouchInjector.MAX_DURATION_MS);
        check("a normal drag keeps its length", TouchInjector.durationMs(320) == 320);
        check("the lower boundary is kept", TouchInjector.durationMs(40) == 40);

        int[] topLeft = TouchInjector.mapPoint(0, 0, 1080, 1920);
        int[] bottomRight = TouchInjector.mapPoint(4095, 4095, 1080, 1920);
        check("the origin maps to the first pixel",
                topLeft[0] == 0 && topLeft[1] == 0);
        check("the far corner maps to the last pixel",
                bottomRight[0] == 1079 && bottomRight[1] == 1919);
        int[] middle = TouchInjector.mapPoint(2048, 2048, 1080, 1920);
        check("the middle maps near the middle",
                middle[0] == 539 || middle[0] == 540);
        check("a degenerate screen size does not throw",
                TouchInjector.mapPoint(4095, 4095, 0, 1)[0] == 0);

        check("a fresh injector is not ready", !TouchInjector.ready());

        // ------------------------------------- the live drag decisions
        check("a stroke is continued before it expires",
                TouchInjector.FLUSH_INTERVAL_MS < TouchInjector.SEGMENT_MS,
                TouchInjector.FLUSH_INTERVAL_MS + " vs " + TouchInjector.SEGMENT_MS);
        check("a flush is due once the interval has passed",
                !TouchInjector.shouldFlush(1000, 1000)
                        && !TouchInjector.shouldFlush(1099, 1000)
                        && TouchInjector.shouldFlush(1100, 1000)
                        && TouchInjector.shouldFlush(1200, 1000));
        check("a quick touch that did not move is a tap",
                TouchInjector.isTap(false, 0) && TouchInjector.isTap(false, 120));
        check("a drag is not a tap", !TouchInjector.isTap(true, 30));
        check("a long still touch is not a tap either",
                !TouchInjector.isTap(false, TouchInjector.TAP_MAX_MS + 1));
        check("the tap slop is small enough to notice a drag",
                TouchInjector.TAP_SLOP > 0 && TouchInjector.TAP_SLOP <= 32);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST TOUCH OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
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
