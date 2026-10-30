package com.local.diplay.clusterbridge.homemap;

public final class MapViewportTest {
    private static int checks;
    public static void main(String[] args) {
        int[][] screens = {{1920,720},{1280,720},{1920,1080},{2560,1440},{800,480},{320,200},{720,1280}};
        for (int[] size : screens) {
            MapViewport screen = MapViewport.screen(size[0], size[1]);
            MapFollowPosition pos = new MapFollowPosition(screen);
            pos.geometry(Integer.MAX_VALUE, Integer.MAX_VALUE, 600, 340);
            equal(size[0], pos.baseX() + pos.width(), "right edge on " + size[0]);
            equal(size[1], pos.baseY() + pos.height(), "bottom edge on " + size[1]);
            check(pos.width() <= size[0] && pos.height() <= size[1], "small screen contains entire card");
            check(pos.width() > 0 && pos.height() > 0, "positive card dimensions");
            pos.geometry(-500, -500, 600, 340);
            equal(0, pos.baseX(), "left edge"); equal(0, pos.baseY(), "top edge");
            check(pos.inViewport(), "fixed card visible");
            pos.follow(true, new LauncherScrollState.Snapshot(1, size[0], size[0], 1));
            check(!pos.inViewport(), "follow clipping uses actual screen width");
            pos.follow(false, null); check(pos.inViewport(), "disable restores position on every screen");
        }
        MapViewport full = MapViewport.screen(1920, 720);
        MapFollowPosition pos = new MapFollowPosition(full);
        pos.geometry(113, 9999, 600, 340);
        equal(380, pos.baseY(), "720 screen no longer stops at old y=278");
        pos.viewport(MapViewport.screen(1920, 1080)); pos.geometry(113, 9999, 600, 340);
        equal(740, pos.baseY(), "1080 screen may drag below old 618 bottom boundary");

        MapViewport bars = full.visibleFrame(80, 40, 1860, 618);
        pos.viewport(bars); pos.geometry(-1, -1, 600, 340);
        equal(80, pos.baseX(), "optional left system bar"); equal(40, pos.baseY(), "optional top system bar");
        pos.geometry(9999, 9999, 600, 340);
        equal(1260, pos.baseX(), "optional right system bar"); equal(278, pos.baseY(), "optional bottom bar reported by OS");
        pos.viewport(full); pos.geometry(9999, 9999, 600, 340);
        equal(1320, pos.baseX(), "disable bar avoidance restores full right extent");
        equal(380, pos.baseY(), "disable bar avoidance restores full bottom extent");
        check(full.same(full.visibleFrame(0,0,0,0)), "empty OEM frame falls back to physical display");
        check(full.same(full.visibleFrame(2000,800,3000,900)), "offscreen OEM frame ignored");
        check(full.same(full.visibleFrame(-10000,-10000,10000,10000)), "no-limits frame clipped to physical display");
        check(!full.same(bars), "bar change triggers relayout");
        check(!full.same(null), "first viewport is a change");
        pos.viewport(MapViewport.screen(320,200)); pos.geometry(113,174,1000,560);
        equal(320, pos.width(), "oversize request fits narrow screen"); equal(200, pos.height(), "oversize request fits short screen");
        pos.viewport(full); pos.geometry(113,174,1000,560);
        equal(1000, pos.width(), "requested width can recover after display grows");
        equal(560, pos.height(), "requested height can recover after display grows");
        pos.geometry(113,174,600,340);
        equal(113, pos.baseX(), "existing owner x retained"); equal(174, pos.baseY(), "existing owner y retained");
        boolean invalid = false;
        try { MapViewport.screen(0,720); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "invalid metrics never turn into an invented fixed-resolution display");
        System.out.println("MapViewportTest: " + checks + " checks passed");
    }
    private static void equal(int expected, int actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
}
