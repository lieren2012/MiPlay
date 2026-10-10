package org.carplaytocarlife.iccoa;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * Mirrors this phone's screen into the encoder through MediaProjection.
 *
 * That is the content side of the bridge: whatever the phone shows (its own
 * launcher, the CarWith/ICCOA projection, any app) is rendered into a virtual
 * display whose output surface is the H.264 encoder's input surface, and the
 * encoded access units go out over the AirPlay tunnel to the car.
 *
 * MediaProjection needs a one-off user consent: MainActivity asks for it and
 * hands the resulting Intent to the service, which lives on after the activity
 * is gone.
 */
public final class ScreenSource {

    private final Context context;
    private final int width;
    private final int height;
    private final int densityDpi;

    private MediaProjection projection;
    private VirtualDisplay display;
    private Surface target;

    public ScreenSource(Context context, int width, int height, int densityDpi) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    public boolean isRunning() {
        return display != null;
    }

    /**
     * Starts mirroring into the encoder's input surface.
     *
     * The caller must already run a foreground service of type mediaProjection;
     * Android 14 refuses the projection otherwise.
     */
    public boolean start(int resultCode, Intent data, final SurfaceEncoder encoder) {
        if (data == null || encoder == null || encoder.inputSurface() == null) {
            Logs.add("screen capture: no projection consent or no encoder surface");
            return false;
        }
        try {
            Object manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (!(manager instanceof MediaProjectionManager)) {
                Logs.add("screen capture: no MediaProjectionManager on this device");
                return false;
            }
            projection = ((MediaProjectionManager) manager).getMediaProjection(resultCode, data);
            if (projection == null) {
                Logs.add("screen capture: the system returned no projection");
                return false;
            }
            final Handler handler = new Handler(Looper.getMainLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Logs.add("screen capture stopped by the system");
                    display = null;
                }
            }, handler);
            target = encoder.inputSurface();
            display = projection.createVirtualDisplay("iccoa-sink",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    target, null, handler);
            Logs.add("screen capture running: " + width + "x" + height + "@" + densityDpi
                    + "dpi (API " + Build.VERSION.SDK_INT + ")");
            return true;
        } catch (Throwable error) {
            Logs.add("screen capture failed: " + error);
            stop();
            return false;
        }
    }

    public void stop() {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
        target = null;
    }
}
