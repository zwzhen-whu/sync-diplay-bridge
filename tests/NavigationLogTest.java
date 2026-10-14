package com.local.diplay.clusterbridge;

/** Fixtures follow the actual Android 8.1 / DiPlay 0.2.19 diagnostic format. */
public final class NavigationLogTest {
    private static final long BASE = 1791975600000L;
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }
    private static String line(long time, int uid, int pid, int tid, String tag, String message) {
        return String.format(java.util.Locale.ROOT, "         %d.%03d %d %d %d I %s: %s", time / 1000, time % 1000, uid, pid, tid, tag, message);
    }
    private static void frame(NavigationLogState state, long time, int uid, int pid, String kind, String... parameters) {
        state.accept(line(time, uid, pid, 7784, "xcertplay-usb", "IAP2 RX [wired] 0x" + kind + " Message frame=74B body=7 params"), 10087, time);
        for (String parameter : parameters) state.accept(line(time, uid, pid, 7784, "xcertplay-usb", parameter), 10087, time);
        state.accept(line(time, uid, pid, 7784, "xcertplay-usb", "  raw-body=00 06"), 10087, time);
    }
    private static void maneuver(NavigationLogState state, long time, int index, String text) {
        frame(state, time, 10087, 6720, "5202", "  [0] param: u16=42 (0x00 2a)", "  [1] param: u16=" + index + " (bytes)",
            "  [2] param: \"" + text + "\"", "  [3] param: u8=1 (0x01)");
    }
    private static void route(NavigationLogState state, long time, int index, int distance) {
        frame(state, time, 10087, 6720, "5201", "  [0] param: u16=42 (0x00 2a)", "  [1] param: u8=1 (0x01)",
            "  [13] param: u16=" + index + " (bytes)", "  [10] param: u32=" + distance + " (bytes)");
    }
    public static void main(String[] args) {
        NavigationLogState state = new NavigationLogState();
        maneuver(state, BASE, 3, "现在 进入 中山路"); route(state, BASE + 1000, 3, 46);
        eq(state.road("turn_left", 50, BASE + 1001), "现在 进入 中山路");
        String saved = state.savedManeuvers(); NavigationLogState restored = new NavigationLogState();
        restored.restoreManeuvers(saved, BASE + 1001);
        eq(restored.road("turn_left", 50, BASE + 1001), "");
        route(restored, BASE + 1100, 3, 46); eq(restored.road("turn_left", 50, BASE + 1101), "现在 进入 中山路");
        NavigationLogState expired = new NavigationLogState(); expired.restoreManeuvers(saved, BASE + 86400001);
        route(expired, BASE + 86401000, 3, 46); eq(expired.road("turn_left", 50, BASE + 86401001), "");
        eq(state.road("turn_right", 50, BASE + 1001), ""); eq(state.road("turn_left", 60, BASE + 1001), "");
        eq(state.road(null, 50, BASE + 1001), ""); eq(state.road("turn_left", null, BASE + 1001), "");
        eq(state.road("turn_left", 50, BASE + 16001), "");
        route(state, BASE + 2000, 4, 46); eq(state.road("turn_left", 50, BASE + 2001), "");
        frame(state, BASE + 2500, 10087, 6720, "5202", "[0] param: u16=42", "[1] param: u16=4", "[2] param: \"导航说明\"", "[3] param: u8=1", "[4] param: \"真正路名\"");
        route(state, BASE + 3000, 4, 46); eq(state.road("turn_left", 50, BASE + 3001), "真正路名");
        frame(state, BASE + 4000, 10087, 6720, "5201", "[0] param: u16=42", "[1] param: u8=0");
        eq(state.road("turn_left", 50, BASE + 4001), "");
        route(state, BASE + 5000, 4, 46); eq(state.road("turn_left", 50, BASE + 5001), "");
        maneuver(state, BASE + 6000, 4, "新路线"); route(state, BASE + 7000, 4, 46);
        // Same tag from another UID cannot change the trusted description.
        frame(state, BASE + 7500, 10078, 6720, "5202", "[0] param: u16=42", "[1] param: u16=4", "[2] param: \"错误道路\"", "[3] param: u8=1");
        eq(state.road("turn_left", 50, BASE + 7501), "新路线");
        // Multi-index lists and a new process with no matching maneuver fail closed.
        frame(state, BASE + 8000, 10087, 6720, "5201", "[0] param: u16=42", "[1] param: u8=1", "[13] param: u32=4", "[10] param: u32=46");
        eq(state.road("turn_left", 50, BASE + 8001), "");
        frame(state, BASE + 8500, 10087, 9999, "5201", "[0] param: u16=42", "[1] param: u8=1", "[13] param: u16=4", "[10] param: u32=46");
        eq(state.road("turn_left", 50, BASE + 8501), "");
        state.clear(); eq(state.road("turn_left", 50, BASE + 9000), "");
        // Incomplete frame is not committed until raw-body ends the record.
        state.accept(line(BASE,10087,6720,7784,"xcertplay-usb","IAP2 RX [wired] 0x5202 Message"),10087,BASE);
        state.accept(line(BASE,10087,6720,7784,"xcertplay-usb","[0] param: u16=42"),10087,BASE);
        route(state, BASE+1000,3,46); eq(state.road("turn_left",50,BASE+1001),"");
        System.out.println("Navigation log tests passed (real format, UID/process/index matching, distance/turn, expiry, route end, malformed lists)");
    }
}
