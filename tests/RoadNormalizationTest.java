package com.local.diplay.clusterbridge;

/** Widget availability, log fallback, and emitted text must agree after cleanup. */
public final class RoadNormalizationTest {
    private static final long BASE = 1792418400000L;
    private static int checks;

    private static void eq(Object actual, Object expected) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }

    private static void frame(NavigationLogState state, long offset, String kind, String... parameters) {
        long wall = BASE + offset;
        String prefix = String.format(java.util.Locale.ROOT, "%d.%03d 10089 3441 6987 I xcertplay-usb: ", wall / 1000, wall % 1000);
        state.accept(prefix + "IAP2 RX [wired] 0x" + kind + " Route", 10089, wall);
        for (String parameter : parameters) state.accept(prefix + parameter, 10089, wall);
        state.accept(prefix + "raw-body=00 06", 10089, wall);
    }

    private static NavigationLogState matchedLog() {
        NavigationLogState state = new NavigationLogState();
        frame(state, 0, "5202", "[0] param: u16=42", "[1] param: u16=4",
            "[2] param: \"日志回退路\"", "[3] param: u8=1");
        frame(state, 10, "5201", "[0] param: u16=42", "[1] param: u8=1",
            "[13] param: u16=4", "[10] param: u32=120");
        eq(state.road("turn_left", 120, BASE + 20), "日志回退路");
        return state;
    }

    // Exercise BridgeService's actual widget-first / matched-log-fallback condition
    // with a real parsed log session; no invented fallback text or stale route.
    private static String selectedRoad(String widgetRoad, NavigationLogState logs) {
        String candidate = Protocol.roadName(widgetRoad);
        if (candidate.isEmpty()) candidate = logs.road("turn_left", 120, BASE + 20);
        return candidate;
    }

    public static void main(String[] args) {
        NavigationLogState logs = matchedLog();
        for (String absent : new String[]{null, "", "\u200b\u2060", "\u200e\u200f\u202a\u2069",
                "\u0000\u0007\u001b", " \u00a0\u2005\u202f\u3000\t\r\n", "\u200b NULL \u2060"}) {
            eq(Protocol.roadName(absent), "");
            eq(Protocol.clusterRoad(absent), "道路名称未提供");
            eq(selectedRoad(absent, logs), "日志回退路");
            NavigationDelivery waiting = new NavigationDelivery();
            eq(waiting.next("turn_left", 120, absent, 1000), NavigationDelivery.Action.WAIT);
            eq(waiting.retryDelay(1000), 1500L);
            eq(waiting.next("turn_left", 120, selectedRoad(absent, logs), 1100), NavigationDelivery.Action.SEND);
        }

        String expected = "三环线 卓刀泉立交";
        for (String separator : new String[]{" ", "  ", "\u2005", "\u00a0", "\u202f", "\u3000", "\t\r\n"}) {
            String input = "\u200e 三环线" + separator + "卓刀泉立交 \u200f";
            eq(Protocol.roadName(input), expected);
            eq(Protocol.clusterRoad(input), expected);
            eq(selectedRoad(input, logs), expected);
            eq(Protocol.roadName(Protocol.roadName(input)), expected);
        }
        eq(Protocol.roadName("中\u0000\u200b山路"), "中山路");
        eq(Protocol.roadName("Main Street / A4"), "Main Street / A4");
        eq(Protocol.roadName("Nullarbor Road"), "Nullarbor Road");

        // A literal question mark or replacement character is evidence, not proof
        // that the road is absent; retain it and let a later road replace it.
        for (String badLooking : new String[]{"三环线 ?卓刀泉立交", "三环线 \ufffd卓刀泉立交"}) {
            eq(Protocol.roadName(badLooking), badLooking);
            eq(Protocol.clusterRoad(badLooking), badLooking);
            eq(selectedRoad(badLooking, logs), badLooking);
            NavigationDelivery delivery = new NavigationDelivery();
            eq(delivery.next("turn_left", 120, badLooking, 1000), NavigationDelivery.Action.SEND);
            delivery.sent("turn_left", 120, badLooking, 1000);
            eq(delivery.next("turn_left", 120, "珞瑜路", 1001), NavigationDelivery.Action.WAIT);
            eq(delivery.next("turn_left", 120, "珞瑜路", 1500), NavigationDelivery.Action.SEND);
        }

        eq(Protocol.roadDiagnostic("?"), "utf16=1 utf8=1 special=[U+003F] count=1");
        eq(Protocol.roadDiagnostic("\ud800"), "utf16=1 utf8=1 special=[U+D800] count=1");
        eq(Protocol.roadDiagnostic("\udc00"), "utf16=1 utf8=1 special=[U+DC00] count=1");
        // A valid supplementary CJK character is a surrogate pair, not two errors.
        eq(Protocol.roadDiagnostic("\ud840\udc00"), "utf16=2 utf8=4 special=[] count=0");
        eq(Protocol.roadDiagnostic("中?\ufffd\ud800路\udc00\ud840\udc00"),
            "utf16=8 utf8=16 special=[U+003F,U+FFFD,U+D800,U+DC00] count=4");
        eq(Protocol.roadDiagnostic("??????????"),
            "utf16=10 utf8=10 special=[U+003F,U+003F,U+003F,U+003F,U+003F,U+003F,U+003F,U+003F] count=10");
        String diagnostic = Protocol.roadDiagnostic("三环线 ?卓刀泉立交");
        eq(diagnostic.contains("三环线"), false);
        eq(diagnostic.contains("卓刀泉立交"), false);
        System.out.println("Road normalization tests passed (" + checks + " checks: shared cleanup, real log fallback, bounded wait, literal evidence, following road, private diagnostics)");
    }
}
