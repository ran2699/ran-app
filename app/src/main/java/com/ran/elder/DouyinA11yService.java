package com.ran.elder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class DouyinA11yService extends AccessibilityService {

    public static final String PKG_DOUYIN = "com.ss.android.ugc.aweme";
    public static final String PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite";

    private static DouyinA11yService instance;
    private OverlayManager overlay;
    private long lastSwipe = 0L;
    private String lastPkg = "";

    public static DouyinA11yService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        overlay = new OverlayManager(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || overlay == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        lastPkg = pkg;

        if (getPackageName().equals(pkg)) return;

        if (isDouyin(pkg)) {
            overlay.show();
        } else {
            overlay.hide();
        }
    }

    private boolean isDouyin(String pkg) {
        return PKG_DOUYIN.equals(pkg) || PKG_DOUYIN_LITE.equals(pkg);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.hide();
        instance = null;
        super.onDestroy();
    }

    public void swipeNext() {
        doSwipe(true);
    }

    public void swipePrev() {
        doSwipe(false);
    }

    private void doSwipe(boolean next) {
        long now = System.currentTimeMillis();
        if (now - lastSwipe < 550L) return;
        lastSwipe = now;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dm.heightPixels);
        int h = Math.max(dm.widthPixels, dm.heightPixels);
        float x = w / 2f;

        Path path = new Path();
        if (next) {
            path.moveTo(x, h * 0.78f);
            path.lineTo(x, h * 0.22f);
        } else {
            path.moveTo(x, h * 0.22f);
            path.lineTo(x, h * 0.78f);
        }

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 230);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gd, null, null);
    }

    public void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public String currentPkg() {
        return lastPkg;
    }
}
