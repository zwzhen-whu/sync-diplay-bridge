package com.local.diplay.clusterbridge.homemap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Transparent edges reveal the actual desktop, including its changing background. */
final class SoftMapCard extends FrameLayout {
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient left, right, top, bottom;
    private final float radius = 28f;
    private float featherX = 72f, featherY = 44f;
    private int featherPercent = 100;

    SoftMapCard(Context context) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateMask(w, h);
    }
    void setFeatherPercent(int percent) {
        featherPercent = Math.max(0, Math.min(200, percent));
        updateMask(getWidth(), getHeight()); invalidate();
    }
    private void updateMask(int w, int h) {
        if (w <= 0 || h <= 0) return;
        featherX = Math.min(w * 0.45f, 72f * featherPercent / 100f);
        featherY = Math.min(h * 0.45f, 44f * featherPercent / 100f);
        if (featherPercent == 0) { invalidateOutline(); return; }
        left = new LinearGradient(0, 0, featherX, 0, 0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP);
        right = new LinearGradient(w - featherX, 0, w, 0, 0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
        top = new LinearGradient(0, 0, 0, featherY, 0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP);
        bottom = new LinearGradient(0, h - featherY, 0, h, 0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
        invalidateOutline();
    }
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (featherPercent == 0) { super.dispatchDraw(canvas); return; }
        int layer = canvas.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(canvas);
        mask.setShader(left); canvas.drawRect(0, 0, featherX, h, mask);
        mask.setShader(right); canvas.drawRect(w - featherX, 0, w, h, mask);
        mask.setShader(top); canvas.drawRect(0, 0, w, featherY, mask);
        mask.setShader(bottom); canvas.drawRect(0, h - featherY, w, h, mask);
        canvas.restoreToCount(layer);
    }
}
