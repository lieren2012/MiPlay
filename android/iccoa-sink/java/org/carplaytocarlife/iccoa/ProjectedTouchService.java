package org.carplaytocarlife.iccoa;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * The only app-level way to move the pointer without root.
 *
 * It does nothing on its own: it exists so {@link TouchInjector} has a live
 * AccessibilityService to hand gestures to. Android requires the user to enable
 * it once in Settings > Accessibility.
 */
public final class ProjectedTouchService extends AccessibilityService {

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Logs.add("input service connected on " + android.os.Build.MODEL);
        TouchInjector.attach(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        TouchInjector.detach(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        TouchInjector.detach(this);
        Logs.add("input service disconnected");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // nothing to observe; this service only performs gestures
    }

    @Override
    public void onInterrupt() {
        TouchInjector.reset();
    }
}
