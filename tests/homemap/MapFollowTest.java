package com.local.diplay.clusterbridge.homemap;

public final class MapFollowTest {
    private static int checks;
    private static final String PKG = LauncherScrollState.PACKAGE;
    private static final String VIEW = LauncherScrollState.VIEW_ID;
    private static final String CLASS = LauncherScrollState.VIEW_CLASS;

    public static void main(String[] args) {
        sourceAndOrdering(); initialPosition(); geometryAndToggle(); frameCoalescing(); stableDisplay();
        System.out.println("MapFollowTest: " + checks + " checks passed");
    }
    private static void sourceAndOrdering() {
        LauncherScrollState state = new LauncherScrollState();
        check(state.latest() == null, "new observer has no invented offset");
        check(!state.accept("other.app", VIEW, CLASS, 2, 200, 2000, 1), "foreign package");
        check(!state.accept(PKG, PKG + ":id/rvCardContainer", CLASS, 2, 200, 2000, 1), "nested card list");
        check(!state.accept(PKG, VIEW, "android.widget.ScrollView", 2, 200, 2000, 1), "wrong class");
        check(!state.accept(PKG, null, CLASS, 2, 200, 2000, 1), "no source ID");
        check(!state.accept(PKG, VIEW, CLASS, -1, 200, 2000, 1), "invalid window");
        check(!state.accept(PKG, VIEW, CLASS, 2, -1, 2000, 1), "invalid offset");
        check(!state.accept(PKG, VIEW, CLASS, 2, 2001, 2000, 1), "beyond source range");
        check(!state.accept(PKG, VIEW, CLASS, 2, 0, -1, 1), "invalid range");
        check(!state.accept(PKG, VIEW, CLASS, 2, 0, Integer.MAX_VALUE, 1), "unreasonable source range");
        check(!state.accept(PKG, VIEW, CLASS, 2, 0, 2000, -1), "invalid time");
        check(state.accept(PKG, VIEW, CLASS, 2, 300, 2000, 10), "first real event");
        check(!state.accept(PKG, VIEW, CLASS, 2, 300, 2000, 11), "duplicate needs no window update");
        check(state.latest().at == 11, "duplicate advances ordering watermark");
        check(!state.accept(PKG, VIEW, CLASS, 2, 0, 2000, 10), "stale event cannot move map back");
        equal(300, state.latest().scrollX, "last accepted position retained");
        check(!state.accept("other.app", VIEW, CLASS, 3, 0, 2000, 100), "foreign event cannot replace owner");
        check(state.accept(PKG, VIEW, CLASS, 3, 0, 1500, 12), "new launcher window");
        equal(3, state.latest().windowId, "new window identity");
        check(state.accept(PKG, VIEW, CLASS, 3, 0, 0, 13), "non-scrolling desktop remains at zero");
        state.clear(); check(state.latest() == null, "disconnect clears cached source");
        check(state.accept(PKG, VIEW, CLASS, 1, 0, 0, 0), "fresh lifetime accepts its own clock");
    }
    private static void initialPosition() {
        LauncherScrollState state = new LauncherScrollState();
        check(state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 800, 1920, 4200, 10), "recover already-scrolled 8.1 desktop");
        equal(800, state.latest().scrollX, "drawing rectangle preserves nonzero initial scroll");
        equal(2280, state.latest().maxScrollX, "range from un-clipped drawing widths");
        check(!state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 0, 0, 4200, 11), "unlaid-out viewport");
        check(!state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 0, 1920, 1000, 11), "invalid content width");
        check(!state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 800, 1920, 1920, 11), "clipped screen bounds must not become an offset");
        check(!state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 800, 1, Integer.MAX_VALUE, 11), "drawing bounds range is bounded");
        check(state.accept(PKG, VIEW, CLASS, 7, 900, 2280, 20), "scroll events take over initial snapshot");
        check(!state.acceptDrawingBounds(PKG, VIEW, CLASS, 7, 800, 1920, 4200, 19), "older snapshot cannot override event");
        equal(900, state.latest().scrollX, "newest offset wins");
    }
    private static void geometryAndToggle() {
        MapFollowPosition pos = new MapFollowPosition(MapViewport.screen(1920, 618));
        pos.geometry(113, 174, 600, 340);
        pos.follow(false, snapshot(200)); equal(113, pos.x(), "default off keeps saved position");
        check(pos.touchable(), "off retains drag and tap");
        pos.follow(true, snapshot(200)); equal(-87, pos.x(), "map follows left without edge clamp");
        equal(174, pos.y(), "horizontal scroll preserves height placement");
        check(pos.inViewport(), "partially visible map remains visible");
        check(!pos.touchable(), "follow mode passes touch to desktop");
        equal(113, pos.baseX(), "scroll does not overwrite saved x");
        equal(174, pos.baseY(), "scroll does not overwrite saved y");
        pos.follow(true, snapshot(713)); check(!pos.inViewport(), "fully offscreen map hides at exact edge");
        pos.follow(true, snapshot(712)); check(pos.inViewport(), "last visible pixel is retained");
        pos.follow(true, snapshot(1500)); check(!pos.inViewport(), "later pages remain hidden");
        pos.follow(true, snapshot(0)); equal(113, pos.x(), "returning page restores original placement");
        for (int i = 0; i < 20; i++) {
            pos.follow(true, snapshot(500)); pos.follow(false, snapshot(500));
        }
        equal(113, pos.baseX(), "toggle cycles cannot accumulate drift");
        equal(113, pos.x(), "disable restores exact fixed x");
        check(pos.touchable(), "disable restores manual placement");
        pos.follow(true, null); equal(113, pos.x(), "missing source keeps fixed fallback");
        check(!pos.touchable(), "waiting observer still lets desktop receive a first swipe");
        pos.follow(true, snapshot(500));
        pos.geometry(pos.baseX(), pos.baseY(), 700, 400);
        equal(-387, pos.x(), "resize uses saved x rather than transient negative x");
        equal(113, pos.baseX(), "resize while scrolling preserves saved x");
        equal(700, pos.width(), "width changes in place"); equal(400, pos.height(), "height changes in place");
        pos.geometry(1800, 600, 1000, 560);
        equal(920, pos.baseX(), "saved position clamped to new size"); equal(58, pos.baseY(), "climate area remains clear");
        pos.geometry(-100, -100, 1, 1);
        equal(0, pos.baseX(), "minimum x"); equal(0, pos.baseY(), "minimum y");
        equal(360, pos.width(), "minimum width"); equal(240, pos.height(), "minimum height");
    }
    private static void frameCoalescing() {
        FakeFrames port = new FakeFrames(); MapFollowFrame frames = new MapFollowFrame(port);
        equal(0, port.posts, "no idle animation");
        for (int i = 0; i < 1000; i++) { port.latest = i; frames.request(); }
        equal(1, port.posts, "burst coalesced into one display frame"); equal(0, port.applies, "no synchronous layout per event");
        port.flush(); equal(1, port.applies, "one layout after display frame"); equal(999, port.applied, "only newest position rendered");
        check(port.queued == null, "frame callback does not schedule another frame");
        frames.request(); port.latest = 1200; port.flush();
        equal(2, port.posts, "next event schedules one new frame"); equal(1200, port.applied, "new source reflected");
        frames.request(); Runnable stale = port.queued; frames.close();
        equal(1, port.removes, "pending frame removed on map shutdown"); check(port.queued == null, "shutdown clears queue");
        stale.run(); frames.request(); equal(2, port.applies, "late callback cannot access destroyed window");
        equal(3, port.posts, "closed coalescer never schedules more work");
    }
    private static void stableDisplay() {
        MapDisplayLease lease = new MapDisplayLease(); FakeDisplay display = new FakeDisplay();
        MapFollowPosition pos = new MapFollowPosition(MapViewport.screen(1920, 618)); pos.geometry(113, 174, 600, 340);
        lease.update(true, true, display);
        boolean visitedHidden = false, returnedVisible = false;
        for (int i = 0; i < 4000; i++) {
            int scroll = i < 2000 ? i : 3999 - i;
            pos.follow(true, snapshot(scroll));
            lease.update(true, true, display); // viewport clipping is deliberately not the display lease input
            if (!pos.inViewport()) visitedHidden = true;
            if (visitedHidden && pos.inViewport()) returnedVisible = true;
        }
        check(visitedHidden && returnedVisible, "page sequence covers offscreen and return");
        equal(1, display.created, "scroll does not recreate virtual display");
        equal(0, display.released, "scroll does not unplug CarPlay map stream");
        equal(0, display.changes, "scroll does not detach or rebind map output");
        lease.update(true, false, display); equal(1, display.changes, "existing CarPlay foreground pause retained");
        lease.update(true, true, display); equal(2, display.changes, "existing home return retained");
        lease.close(display); equal(1, display.released, "normal shutdown releases once");
    }
    private static LauncherScrollState.Snapshot snapshot(int x) { return new LauncherScrollState.Snapshot(1, x, 4000, 1); }
    private static final class FakeFrames implements MapFollowFrame.Port {
        int posts, removes, applies, latest, applied; Runnable queued;
        public void post(Runnable frame) { posts++; if (queued != null) throw new AssertionError("multiple pending frames"); queued = frame; }
        public void remove(Runnable frame) { removes++; if (queued == frame) queued = null; }
        public void apply() { applies++; applied = latest; }
        void flush() { Runnable next = queued; queued = null; if (next != null) next.run(); }
    }
    private static final class FakeDisplay implements MapDisplayLease.Port {
        int created, released, changes;
        public boolean create(boolean rendering) { created++; return true; }
        public void render(boolean rendering) { changes++; }
        public void release() { released++; }
    }
    private static void equal(int expected, int actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
}
