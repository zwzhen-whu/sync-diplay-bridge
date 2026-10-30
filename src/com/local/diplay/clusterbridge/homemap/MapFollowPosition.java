package com.local.diplay.clusterbridge.homemap;

/** Saved placement and transient scroll offset are deliberately separate. */
final class MapFollowPosition {
    private MapViewport viewport;
    private int baseX, baseY, width, height;
    private boolean following;
    private int scrollOrigin;
    private LauncherScrollState.Snapshot scroll;

    MapFollowPosition(MapViewport viewport) { this.viewport = viewport; }
    void viewport(MapViewport value) { viewport = value; }

    void geometry(int x, int y, int w, int h) {
        width = clamp(w, Math.min(360, viewport.width()), Math.min(1000, viewport.width()));
        height = clamp(h, Math.min(240, viewport.height()), Math.min(560, viewport.height()));
        baseX = clamp(x, viewport.left, viewport.right - width); baseY = clamp(y, viewport.top, viewport.bottom - height);
    }
    void follow(boolean value, LauncherScrollState.Snapshot snapshot) { following = value; scroll = snapshot; }
    void scrollOrigin(int value) { scrollOrigin = Math.max(0, Math.min(1_000_000, value)); }
    int baseX() { return baseX; }
    int baseY() { return baseY; }
    int x() {
        long result = baseX - (following && scroll != null ? (long)scroll.scrollX - scrollOrigin : 0);
        return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, result));
    }
    int y() { return baseY; }
    int width() { return width; }
    int height() { return height; }
    boolean inViewport() { return x() < viewport.right && (long)x() + width > viewport.left; }
    boolean touchable() { return !following; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
