package com.local.diplay.clusterbridge.homemap;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

/** Read the host display, never the fixed-size CarPlay virtual display. */
final class MapWindowMetrics {
    static final String AVOID_BARS_KEY = "avoid_system_bars";
    static MapViewport read(Context context, View attachedView, boolean avoidBars) {
        DisplayMetrics metrics = new DisplayMetrics();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0)
            metrics = context.getResources().getDisplayMetrics();
        MapViewport screen = MapViewport.screen(metrics.widthPixels, metrics.heightPixels);
        if (!avoidBars || attachedView == null || !attachedView.isAttachedToWindow()) return screen;
        Rect frame = new Rect(); attachedView.getWindowVisibleDisplayFrame(frame);
        return screen.visibleFrame(frame.left, frame.top, frame.right, frame.bottom);
    }
}
