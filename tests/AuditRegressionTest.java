package com.local.diplay.clusterbridge;

/** Offline regressions for startup races and differences between phones/DiPlay traces. */
public final class AuditRegressionTest {
    private static final long BASE = 1791975600000L;
    private static void eq(Object value, Object expected) {
        if (expected == null ? value != null : !expected.equals(value)) throw new AssertionError(value + " != " + expected);
    }
    private static void frame(NavigationLogState state, long offset, String kind, String... parameters) {
        long time = BASE + offset;
        String prefix = String.format(java.util.Locale.ROOT, "%d.%03d 10087 6720 7784 I xcertplay-usb: ", time / 1000, time % 1000);
        state.accept(prefix + "IAP2 RX [wired] 0x" + kind + " Message", 10087, time);
        for (String parameter : parameters) state.accept(prefix + parameter, 10087, time);
        state.accept(prefix + "raw-body=00 06", 10087, time);
    }
    private static void maneuver(NavigationLogState state, long offset, String description) {
        frame(state, offset, "5202", "[0] Identifier: u16=42", "[1] ManeuverIndex: u16=4",
            "[2] Description: \"" + description + "\"", "[3] ManeuverType: u8=1");
    }
    private static void route(NavigationLogState state, long offset, String list) {
        frame(state, offset, "5201", "[0] Identifier: u16=42", "[1] State: u8=1", "[13] CurrentList: " + list, "[10] Distance: u32=46");
    }
    public static void main(String[] args) {
        CarPlayEntryPolicy boot = new CarPlayEntryPolicy(); boot.connection(true, 0);
        eq(boot.request(3000, "com.android.launcher", "Home"), false);
        eq(boot.request(35000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(boot.request(37000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        // A successful click can still be followed by DiPlay returning to its entry.
        eq(boot.request(38000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(boot.request(41000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(boot.request(45000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(boot.request(46000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(boot.request(53000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(boot.request(61000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(boot.attempts(), 3);
        eq(boot.request(62000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(boot.request(72000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(boot.confirmed(), true);
        eq(boot.request(75000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        boot.connection(false, 80000); boot.connection(true, 81000);
        eq(boot.request(84000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(boot.request(86000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        // Neither failed clicks nor a permanently frontmost other app cause unbounded jumps.
        CarPlayEntryPolicy noClick = new CarPlayEntryPolicy(); noClick.connection(true, 0);
        eq(noClick.request(3000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(noClick.request(5000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(noClick.request(13000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(noClick.request(21000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(noClick.request(29000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        CarPlayEntryPolicy other = new CarPlayEntryPolicy(); other.connection(true, 0);
        for (int t = 3000; t < 90000; t += 1000) eq(other.request(t, "com.other", "App"), false);
        eq(other.request(91000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        CarPlayEntryPolicy late = new CarPlayEntryPolicy(); late.connection(true, 0);
        eq(late.request(3000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(late.request(13000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(late.confirmed(), true);
        eq(late.request(35000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(late.request(37000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);

        ForegroundActivityState fg = new ForegroundActivityState();
        fg.event(1, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY, BASE);
        fg.queried(BASE, 1000);
        fg.queryStart(BASE + 3600000, 2000); // NTP forward jump, common early in boot
        eq(fg.packageName(), null);
        eq(LogAccess.hasLogGroup("Name: app\nGroups:\t1007 3003\n"), true);
        eq(LogAccess.hasLogGroup("Groups: 3003 10070\n"), false);
        eq(LogAccess.hasLogGroup("not readable"), null);
        eq(LogAccess.sameCacheEpoch(10087, 10087, 12, 12), true);
        eq(LogAccess.sameCacheEpoch(10087, 10087, 12, 13), false);
        eq(LogAccess.sameCacheEpoch(10087, 10089, 12, 12), false);
        eq(LogAccess.sameCacheEpoch(10087, 10087, -1, -1), false);

        for (String list : new String[]{"u16=4", "u32=262149 (0x00 04 00 05)",
            "u64=1125921382072327 (0x00 04 00 05 00 06 00 07)", "bytes=6 [00 04 00 05 00 06]", "[0x0004, 0x0005]"}) {
            NavigationLogState state = new NavigationLogState();
            maneuver(state, 0, "现在\\t进入 中山路"); route(state, 1000, list);
            eq(state.road("turn_left", 50, BASE + 1001), "现在 进入 中山路");
            eq(state.road("turn_right", 50, BASE + 1001), "");
            eq(state.road("turn_left", 60, BASE + 1001), "");
            // Partial route and partial maneuver updates retain fields from this active route.
            frame(state, 2000, "5201", "[0] Identifier: u16=42", "[10] Distance: u32=154");
            eq(state.road("turn_left", 150, BASE + 2001), "现在 进入 中山路");
            frame(state, 2100, "5202", "[0] Identifier: u16=42", "[1] Index: u16=4", "[3] Type: u8=1");
            eq(state.road("turn_left", 150, BASE + 2101), "现在 进入 中山路");
            frame(state, 2200, "5202", "[0] Identifier: u16=42", "[1] Index: u16=4", "[4] AfterRoad: \"更新道路\"");
            eq(state.road("turn_left", 150, BASE + 2201), "更新道路");
            frame(state, 2300, "5201", "[10] Distance: u32=154");
            eq(state.road("turn_left", 150, BASE + 2301), "更新道路");
            frame(state, 2400, "5201", "[0] Identifier: u16=42", "[1] State: u8=1", "[6] Duration: u64=300");
            eq(state.road("turn_left", 150, BASE + 2401), "更新道路");
            frame(state, 3000, "5201", "[0] Identifier: u16=42", "[1] State: u8=0");
            eq(state.road("turn_left", 50, BASE + 3001), "");
            frame(state, 4000, "5201", "[0] Identifier: u16=42", "[10] Distance: u32=54");
            eq(state.road("turn_left", 50, BASE + 4001), ""); // cannot resurrect an ended route
        }
        NavigationLogState ordered = new NavigationLogState();
        route(ordered, 0, "u16=4"); maneuver(ordered, 500, "稍后到达的导航说明");
        eq(ordered.road("turn_left", 50, BASE + 501), "稍后到达的导航说明");
        frame(ordered, 1000, "5201", "[0] ID: u16=42", "[3] CurrentRoad: \"当前道路\"");
        eq(ordered.road("turn_left", 50, BASE + 1001), "当前道路");
        ordered.accept(String.format(java.util.Locale.ROOT, "%d.500 10087 6720 7784 I xcertplay-usb: IAP2 CLOSE [wired]", (BASE + 1000) / 1000), 10087, BASE + 1500);
        eq(ordered.road("turn_left", 50, BASE + 1501), "");
        for (String invalid : new String[]{"u32=4", "bytes=3 [00 04 00]", "bytes=4 [00 04]", "[0x0004, garbage]", "bytes=26 [00 04 ...]"}) {
            route(ordered, 2000, invalid); eq(ordered.road("turn_left", 50, BASE + 2001), "");
        }
        NavigationLogState clock = new NavigationLogState();
        maneuver(clock, 10000, "回拨前"); route(clock, 11000, "u16=4");
        maneuver(clock, 1000, "回拨后"); route(clock, 2000, "u16=4");
        eq(clock.road("turn_left", 50, BASE + 2001), "回拨后");
        NavigationLogState first = new NavigationLogState();
        frame(first, 0, "5201", "[0] param: u16=42", "[1] param: u8=1", "[13] param: u16=0", "[10] param: u32=46");
        frame(first, 100, "5202", "[0] param: u16=42", "[1] param: u16=1", "[2] param: \"下一路口\"", "[3] param: u8=1");
        frame(first, 500, "5202", "[0] param: u16=42", "[1] param: u16=0", "[2] param: \"首个路口\"", "[3] param: u8=1");
        eq(first.road("turn_left", 50, BASE + 501), "首个路口");
        frame(first, 600, "5202", "[0] param: u16=42", "[1] param: u16=0", "[2] param: \"首个路口更新\"");
        eq(first.road("turn_left", 50, BASE + 601), "首个路口更新");
        frame(first, 1000, "5201", "[0] param: u16=42", "[1] param: u8=1", "[13] param: u16=1", "[10] param: u32=46");
        eq(first.road("turn_left", 50, BASE + 1001), "下一路口");
        eq(Protocol.meters("1,2 км"), 1200); eq(Protocol.meters("200 公尺"), 200);
        eq(Protocol.meters("\u061c١٫٢\u2069 كم"), 1200);
        System.out.println("Audit regressions passed (boot transients/retries/confirmation, clock jumps, log groups, boot cache, named fields, u16 lists, partial frames, delayed text)");
    }
}
