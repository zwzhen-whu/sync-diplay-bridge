package com.local.diplay.clusterbridge.homemap;

/** Physical-pixel drag bounds; no vehicle resolution or reserved climate-bar height. */
final class MapViewport {
    final int left, top, right, bottom;
    MapViewport(int left, int top, int right, int bottom) {
        if (left < 0 || top < 0 || right <= left || bottom <= top)
            throw new IllegalArgumentException("Invalid map viewport");
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    static MapViewport screen(int width, int height) { return new MapViewport(0, 0, width, height); }
    MapViewport visibleFrame(int l, int t, int r, int b) {
        l = Math.max(left, l); t = Math.max(top, t); r = Math.min(right, r); b = Math.min(bottom, b);
        return r > l && b > t ? new MapViewport(l, t, r, b) : this;
    }
    int width() { return right - left; }
    int height() { return bottom - top; }
    boolean same(MapViewport other) {
        return other != null && left == other.left && top == other.top && right == other.right && bottom == other.bottom;
    }
}
