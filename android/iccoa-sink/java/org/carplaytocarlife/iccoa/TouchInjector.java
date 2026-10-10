package org.carplaytocarlife.iccoa;

import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.DisplayMetrics;


/**
 * Injects the car's touch input back into this phone.
 *
 * The car does not send Android events: it sends HID reports in its own 0..4095
 * coordinate space (see {@link HidTouch}). Turning one into a real gesture needs
 * an AccessibilityService with canPerformGestures, which is why this class talks
 * to {@link ProjectedTouchService} instead of dispatching events itself.
 *
 * A touch sequence is buffered and dispatched as one stroke on lift. That costs
 * the live feedback of a streamed drag but keeps the gesture sequence valid (a
 * stroke may only be continued while it is still running), and taps - what a
 * head unit mostly sends - behave exactly as expected.
 */
public final class TouchInjector {

    /** Android drops gestures shorter than a frame; the cap keeps them sane. */
    public static final int MIN_DURATION_MS = 40;
    public static final int MAX_DURATION_MS = 1200;

    private static volatile ProjectedTouchService service;
    /** Gestures are handed to the service on its own thread. */
    private static android.os.Handler handler;

    /** Built on first use: on a plain JVM there is no main looper to ask. */
    private static synchronized android.os.Handler handler() {
        if (handler == null) {
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return handler;
    }

    private TouchInjector() {
    }

    static void attach(ProjectedTouchService instance) {
        service = instance;
        Logs.add("touch injection ready");
    }

    static void detach(ProjectedTouchService instance) {
        if (service == instance) {
            service = null;
        }
    }

    public static boolean ready() {
        return service != null;
    }

    /** Opens the accessibility settings page so injection can be enabled. */
    public static void requestPermission(Context context) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logs.add("open accessibility settings; enable the input service there");
        } catch (Throwable error) {
            Logs.add("cannot open accessibility settings: " + error);
        }
    }

    // ------------------------------------------------------------ the gesture

    /** How long one stroke segment lives before it has to be continued. */
    public static final int SEGMENT_MS = 200;
    /** How often an active touch is continued, so a still finger stays down. */
    public static final long FLUSH_INTERVAL_MS = 100;
    /** A touch that moves less than this and ends quickly is a tap. */
    public static final int TAP_SLOP = 16;
    /** ...and a tap is over by then. */
    public static final long TAP_MAX_MS = 250;

    private static GestureDescription.StrokeDescription stroke;
    private static int lastX;
    private static int lastY;
    private static int downX;
    private static int downY;
    private static boolean moved;
    /** When the finger went down, for the tap-versus-drag decision. */
    private static long downAt;
    private static long lastFlushAt;
    private static Thread pump;

    /** Clamps a gesture length into the range Android accepts. */    /** Clamps a gesture length into the range Android accepts. */
    public static int durationMs(long elapsedMs) {
        if (elapsedMs < MIN_DURATION_MS) {
            return MIN_DURATION_MS;
        }
        if (elapsedMs > MAX_DURATION_MS) {
            return MAX_DURATION_MS;
        }
        return (int) elapsedMs;
    }

    /** Maps one normalised 0..4095 point into screen pixels. */
    public static int[] mapPoint(int x, int y, int width, int height) {
        return new int[] {HidTouch.scale(x, width), HidTouch.scale(y, height)};
    }

    /**
     * Feeds one HID touch report from the car.
     *
     * A touch is dispatched as it happens and then continued, so a drag follows
     * the finger on the car screen instead of landing in one jump on lift. The
     * continuation has to be sent before the running stroke expires, which is
     * what the pump below is for: the car may send nothing while the finger is
     * held still, and a stroke that runs out is seen as a lift.
     */
    public static synchronized String apply(HidTouch.Touch touch) {
        if (touch.tip) {
            if (stroke == null) {
                return begin(touch.x, touch.y);
            }
            if (Math.abs(touch.x - downX) > TAP_SLOP || Math.abs(touch.y - downY) > TAP_SLOP) {
                moved = true;
            }
            lastX = touch.x;
            lastY = touch.y;
            long now = SystemClock.uptimeMillis();
            if (shouldFlush(now, lastFlushAt)) {
                return continueStroke(touch);
            }
            return "drag to " + touch.x + "," + touch.y;
        }
        if (stroke == null) {
            // a lift with nothing down still means "tap here"
            return tap(touch.x, touch.y);
        }
        return finish(touch.x, touch.y);
    }

    /** True when enough time has passed to continue the running stroke. */
    public static boolean shouldFlush(long nowMs, long lastFlushMs) {
        return nowMs - lastFlushMs >= FLUSH_INTERVAL_MS;
    }

    /** True when a finished touch was a tap rather than a drag. */
    public static boolean isTap(boolean movedEnough, long elapsedMs) {
        return !movedEnough && elapsedMs <= TAP_MAX_MS;
    }

    private static String begin(int x, int y) {
        ProjectedTouchService target = service;
        if (target == null) {
            return "ignored: enable the input service in accessibility";
        }
        float[] point = pixel(target, x, y);
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        moved = false;
        downAt = SystemClock.uptimeMillis();
        lastFlushAt = downAt;
        Path path = new Path();
        path.moveTo(point[0], point[1]);
        stroke = new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true);
        boolean dispatched = dispatch(target, stroke);
        startPump();
        return dispatched
                ? ("down at " + x + "," + y + " (live)")
                : ("down at " + x + "," + y + " rejected");
    }

    private static String continueStroke(HidTouch.Touch touch) {
        ProjectedTouchService target = service;
        if (target == null || stroke == null) {
            return "ignored: no live stroke";
        }
        float[] from = pixel(target, lastX, lastY);
        float[] to = pixel(target, touch.x, touch.y);
        Path path = new Path();
        path.moveTo(from[0], from[1]);
        path.lineTo(to[0], to[1]);
        GestureDescription.StrokeDescription next;
        try {
            next = stroke.continueStroke(path, 0, SEGMENT_MS, true);
        } catch (RuntimeException expired) {
            // the running stroke timed out; start a fresh one where the finger is
            moved = true;
            stroke = null;
            return begin(touch.x, touch.y);
        }
        lastFlushAt = SystemClock.uptimeMillis();
        boolean dispatched = dispatch(target, next);
        stroke = next;
        return dispatched
                ? ("drag to " + touch.x + "," + touch.y)
                : ("drag to " + touch.x + "," + touch.y + " rejected");
    }

    private static String finish(int x, int y) {
        ProjectedTouchService target = service;
        long elapsed = SystemClock.uptimeMillis() - downAt;
        boolean wasTap = isTap(!moved, elapsed);
        if (target == null) {
            stroke = null;
            return "ignored: enable the input service in accessibility";
        }
        String result;
        if (wasTap) {
            stroke = null;
            stopPump();
            return tap(x, y);
        }
        float[] from = pixel(target, lastX, lastY);
        float[] to = pixel(target, x, y);
        Path path = new Path();
        path.moveTo(from[0], from[1]);
        path.lineTo(to[0], to[1]);
        GestureDescription.StrokeDescription last;
        try {
            last = stroke.continueStroke(path, 0, Math.min(SEGMENT_MS, (int) Math.max(1, elapsed)),
                    false);
        } catch (RuntimeException expired) {
            stroke = null;
            stopPump();
            return "lift after the stroke expired; nothing to finish";
        }
        boolean dispatched = dispatch(target, last);
        result = dispatched
                ? ("up at " + x + "," + y + " after " + elapsed + "ms" + (moved ? " (drag)" : ""))
                : ("up at " + x + "," + y + " rejected");
        stroke = null;
        stopPump();
        return result;
    }

    /** A short tap, dispatched as one finished stroke. */
    private static String tap(int x, int y) {
        ProjectedTouchService target = service;
        if (target == null) {
            return "ignored: enable the input service in accessibility";
        }
        float[] point = pixel(target, x, y);
        Path path = new Path();
        path.moveTo(point[0], point[1]);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0,
                        durationMs(MIN_DURATION_MS)))
                .build();
        boolean dispatched = dispatch(target, gesture);
        return dispatched ? ("tap at " + x + "," + y) : ("tap at " + x + "," + y + " rejected");
    }

    /** Dispatches one stroke, which is all this injector ever sends. */
    private static boolean dispatch(ProjectedTouchService target,
            GestureDescription.StrokeDescription stroke) {
        return dispatch(target, new GestureDescription.Builder().addStroke(stroke).build());
    }

    /**
     * Dispatches a gesture from the service's main thread.
     *
     * The car's reports arrive on the event channel's thread, and gestures have
     * to go out in the order they were built, so a background caller is posted
     * to the main thread instead of calling into the service from where it is.
     */
    private static boolean dispatch(final ProjectedTouchService target,
            final GestureDescription gesture) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return dispatchNow(target, gesture);
        }
        handler().post(new Runnable() {
            @Override
            public void run() {
                dispatchNow(target, gesture);
            }
        });
        return true;
    }

    private static boolean dispatchNow(ProjectedTouchService target, GestureDescription gesture) {
        try {
            return target.dispatchGesture(gesture, null, null);
        } catch (Throwable error) {
            Logs.add("gesture dispatch threw: " + error);
            return false;
        }
    }

    private static float[] pixel(ProjectedTouchService target, int x, int y) {
        android.util.DisplayMetrics metrics = target.getResources().getDisplayMetrics();
        int[] mapped = mapPoint(x, y, metrics.widthPixels, metrics.heightPixels);
        return new float[] {mapped[0], mapped[1]};
    }

    /**
     * Keeps the running stroke alive while the finger is down.
     *
     * Without this a still finger would look like a lift after one segment, and
     * a long press could never be recognised.
     */
    private static void startPump() {
        if (pump != null) {
            return;
        }
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(FLUSH_INTERVAL_MS / 2);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                    synchronized (TouchInjector.class) {
                        if (stroke == null) {
                            return;
                        }
                        ProjectedTouchService target = service;
                        long now = SystemClock.uptimeMillis();
                        if (target == null || !shouldFlush(now, lastFlushAt)) {
                            continue;
                        }
                        float[] point = pixel(target, lastX, lastY);
                        Path path = new Path();
                        path.moveTo(point[0], point[1]);
                        GestureDescription.StrokeDescription next;
                        try {
                            next = stroke.continueStroke(path, 0, SEGMENT_MS, true);
                        } catch (RuntimeException expired) {
                            stroke = null;
                            return;
                        }
                        lastFlushAt = now;
                        dispatch(target, next);
                        stroke = next;
                    }
                }
            }
        }, "touch-pump");
        pump.setDaemon(true);
        pump.start();
    }

    private static void stopPump() {
        Thread running = pump;
        pump = null;
        if (running != null) {
            running.interrupt();
        }
    }

    /** Drops a half finished sequence, e.g. when the session ends. */
    public static synchronized void reset() {
        stroke = null;
        moved = false;
        lastFlushAt = 0;
        stopPump();
    }
}
