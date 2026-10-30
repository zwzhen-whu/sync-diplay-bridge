package com.local.diplay.clusterbridge.homemap;

/** The page visible when following is enabled is the origin, not the launcher's first page. */
final class MapFollowAnchor {
    static final String KEY = "follow_scroll_origin";
    private int origin;
    MapFollowAnchor(int saved) { origin = saved >= 0 && saved <= 1_000_000 ? saved : -1; }
    boolean capture(LauncherScrollState.Snapshot snapshot) {
        if (origin >= 0 || snapshot == null) return false;
        origin = snapshot.scrollX; return true;
    }
    boolean ready() { return origin >= 0; }
    int origin() { return Math.max(0, origin); }
}
