package com.local.diplay.clusterbridge;

public final class RoadSyncTest {
    private static final long BASE = 1792300000000L;
    private static void eq(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    private static void frame(NavigationLogState state, long offset, String kind, String... params) {
        long wall = BASE + offset;
        String prefix = String.format(java.util.Locale.ROOT, "%d.%03d 10089 3441 6987 I xcertplay-usb: ", wall / 1000, wall % 1000);
        state.accept(prefix + "IAP2 RX [wired] 0x" + kind + " Route", 10089, wall);
        for (String param : params) state.accept(prefix + param, 10089, wall);
        state.accept(prefix + "raw-body=00 06", 10089, wall);
    }
    private static void detail(NavigationLogState state, long offset, int id, int index, String text) {
        frame(state, offset, "5202", "[0] param: u16=" + id, "[1] param: u16=" + index,
            "[2] param: \"" + text + "\"", "[3] param: u8=1");
    }
    private static void route(NavigationLogState state, long offset, int id, int index, int distance) {
        frame(state, offset, "5201", "[0] param: u16=" + id, "[1] param: u8=1",
            "[13] param: u16=" + index, "[10] param: u32=" + distance);
    }
    public static void main(String[] args) {
        eq(Protocol.clusterRoad("中山路\u2005\u00a0\u202f东湖路"), "中山路 东湖路");
        eq(Protocol.clusterRoad("\u200e中山路\u3000东湖路\u200f"), "中山路 东湖路");
        eq(Protocol.clusterRoad("中\u200b山路\t\r\n东湖路"), "中山路 东湖路");
        eq(Protocol.clusterRoad("问号?和替换符\ufffd"), "问号?和替换符\ufffd");
        eq(Protocol.clusterRoad("\u200b\u2060\u00a0"), "道路名称未提供");
        eq(Protocol.clusterRoad("Main Street / A4"), "Main Street / A4");
        eq(Protocol.roadDiagnostic("中山路\u2005东湖路"), "utf16=7 utf8=21 special=[U+2005] count=1");
        eq(Protocol.roadDiagnostic("\ufffd\ue001\ud800"), "utf16=3 utf8=7 special=[U+FFFD,U+E001,U+D800] count=3");

        NavigationLogState state = new NavigationLogState();
        detail(state, 0, 42, 4, "中山路"); route(state, 10, 42, 4, 120);
        eq(state.road("turn_left", 120, BASE + 11), "中山路");
        route(state, 100, 42, 4, 110);
        // Real log update arrives before the throttled widget's previous snapshot.
        eq(state.road("turn_left", 120, BASE + 500), "中山路");
        eq(state.road("turn_left", 110, BASE + 500), "中山路");
        eq(state.road("turn_left", 130, BASE + 500), "");
        eq(state.road("turn_right", 120, BASE + 500), "");
        eq(state.road("turn_left", 120, BASE + 1601), "");
        detail(state, 1700, 42, 5, "东湖路"); route(state, 1710, 42, 5, 100);
        eq(state.road("turn_left", 110, BASE + 1800), "中山路");
        eq(state.road("turn_left", 100, BASE + 1800), "东湖路");
        frame(state, 1900, "5201", "[0] param: u16=42", "[1] param: u8=0");
        eq(state.road("turn_left", 110, BASE + 1901), "");
        eq(state.road("turn_left", 100, BASE + 1901), "");
        detail(state, 2000, 42, 4, "旧会话"); route(state, 2010, 42, 4, 120);
        detail(state, 2100, 43, 4, "新会话"); route(state, 2110, 43, 4, 100);
        eq(state.road("turn_left", 120, BASE + 2200), "");
        eq(state.road("turn_left", 100, BASE + 2200), "新会话");

        NavigationDelivery delivery = new NavigationDelivery();
        eq(delivery.next("turn_left", 120, "", 0), NavigationDelivery.Action.WAIT);
        eq(delivery.retryDelay(0), 1500L);
        eq(delivery.next("turn_left", 110, "", 1400), NavigationDelivery.Action.WAIT);
        eq(delivery.retryDelay(1400), 100L); // New updates cannot extend the wait forever.
        eq(delivery.next("turn_left", 110, "", 1500), NavigationDelivery.Action.SEND);
        delivery.sent("turn_left", 110, "", 1500);
        eq(delivery.next("turn_left", 110, "东湖路", 1550), NavigationDelivery.Action.WAIT);
        eq(delivery.retryDelay(1550), 450L);
        eq(delivery.next("turn_left", 110, "东湖路", 2000), NavigationDelivery.Action.SEND);
        delivery.sent("turn_left", 110, "东湖路", 2000);
        eq(delivery.next("turn_left", 110, "东湖路", 2600), NavigationDelivery.Action.NONE);
        eq(delivery.next("turn_left", 110, "东湖路", 3000), NavigationDelivery.Action.SEND);
        delivery.reset();
        int sends = 0;
        for (long now = 0; now < 10000; now++) {
            if (delivery.next("turn_left", (int)now, "中山路", now) == NavigationDelivery.Action.SEND) {
                delivery.sent("turn_left", (int)now, "中山路", now); sends++;
            }
        }
        eq(sends, 20); // 10,000 changing callbacks produce at most 2 broadcasts/sec.
        delivery.reset();
        eq(delivery.retryDelay(10000), -1L);
        System.out.println("Road sync tests passed (Unicode spaces, bounded widget lag, session/end isolation, no blank flicker, rate/heartbeat bounds)");
    }
}
