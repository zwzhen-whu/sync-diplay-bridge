package com.local.diplay.clusterbridge;

import java.nio.charset.StandardCharsets;

/** Display choices must not change source matching or bypass delivery bounds. */
public final class RoadDisplayModeTest {
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

    public static void main(String[] args) {
        String dual = "八一路 洪山侧路";
        eq(Protocol.clusterRoad(dual), dual);
        eq(Protocol.clusterRoad(dual, Protocol.ROAD_ORIGINAL), dual);
        eq(Protocol.clusterRoad(dual, null), dual);
        eq(Protocol.clusterRoad(dual, "unknown"), dual);
        eq(Protocol.roadDisplayMode(null), Protocol.ROAD_ORIGINAL);
        eq(Protocol.roadDisplayMode("unknown"), Protocol.ROAD_ORIGINAL);
        eq(Protocol.roadDisplayMode(Protocol.ROAD_ORIGINAL), Protocol.ROAD_ORIGINAL);
        eq(Protocol.roadDisplayMode(Protocol.ROAD_CLEAN), Protocol.ROAD_CLEAN);
        eq(Protocol.roadDisplayMode("first"), Protocol.ROAD_CLEAN);
        eq(Protocol.roadDisplayMode("comma"), Protocol.ROAD_CLEAN);
        for (String separator : new String[]{" ", "   ", "\u2005", "\u00a0", "\u202f", "\u3000", "\t\r\n"}) {
            String input = "\u200e 八一路" + separator + "洪山侧路 \u2060";
            eq(Protocol.clusterRoad(input, Protocol.ROAD_ORIGINAL), dual);
            eq(Protocol.clusterRoad(input, Protocol.ROAD_CLEAN), dual);
            // Legacy settings adopt the new rule and retain both road names.
            eq(Protocol.clusterRoad("去往" + separator + input, "comma"), dual);
            eq(Protocol.clusterRoad("去往" + separator + input, "first"), dual);
        }
        eq(Protocol.clusterRoad("三环线 卓刀泉立交", Protocol.ROAD_CLEAN), "三环线 卓刀泉立交");
        eq(dual.getBytes(StandardCharsets.UTF_8).length, 22);
        eq(Protocol.clusterRoad(dual, Protocol.ROAD_CLEAN).getBytes(StandardCharsets.UTF_8).length, 22);
        eq(Protocol.clusterRoad("去往 八一路 洪山侧路", "comma"), dual);

        // Preserve every later road on each update, regardless of corruption.
        for (String next : new String[]{"洪山侧路", "东湖路", "珞狮路"}) {
            eq(Protocol.clusterRoad("去往 八一路 " + next, Protocol.ROAD_CLEAN), "八一路 " + next);
        }
        for (String mode : new String[]{Protocol.ROAD_ORIGINAL, Protocol.ROAD_CLEAN, "first", "comma"}) {
            eq(Protocol.clusterRoad("珞瑜路", mode), "珞瑜路");
            eq(Protocol.clusterRoad("东湖路辅路", mode), "东湖路辅路");
            for (String absent : new String[]{null, "", " null ", "\u200b\u00a0\u2060"}) {
                eq(Protocol.clusterRoad(absent, mode), "道路名称未提供");
                NavigationDelivery waiting = new NavigationDelivery();
                // BridgeService keeps a missing source empty until the bounded
                // wait completes; the display placeholder is not a valid source.
                String candidate = Protocol.roadName(absent);
                String display = Protocol.clusterRoad(candidate, mode);
                String deliveryRoad = candidate.isEmpty() ? "" : display;
                eq(waiting.next("turn_right", 600, deliveryRoad, 1000), NavigationDelivery.Action.WAIT);
                eq(waiting.retryDelay(1000), 1500L);
                eq(waiting.next("turn_right", 600, deliveryRoad, 2500), NavigationDelivery.Action.SEND);
            }
        }

        eq(Protocol.clusterRoad("现在 进入 八一路 洪山侧路", Protocol.ROAD_CLEAN), dual);
        eq(Protocol.clusterRoad("200米后 右转 进入 八一路 洪山侧路", Protocol.ROAD_CLEAN), dual);
        // Latest requested behavior: remove the prompt and keep A, the space, and B.
        eq(Protocol.clusterRoad("去往 A B", Protocol.ROAD_CLEAN), "A B");
        eq(Protocol.clusterRoad("去往 A B C", Protocol.ROAD_CLEAN), "A B C");
        eq(Protocol.clusterRoad("去往 八一路 洪山侧路", Protocol.ROAD_CLEAN), dual);
        eq(Protocol.clusterRoad("去往\u2005八一路\u2005洪山侧路", Protocol.ROAD_CLEAN), dual);
        eq(Protocol.clusterRoad("现在 请 去往 八一路 洪山侧路", Protocol.ROAD_CLEAN), dual);
        eq(Protocol.clusterRoad("去往 八一路 洪山侧路", Protocol.ROAD_ORIGINAL), "去往 八一路 洪山侧路");
        eq(Protocol.clusterRoad("去往", Protocol.ROAD_CLEAN), "道路名称未提供");
        eq(Protocol.clusterRoad("请 去往", Protocol.ROAD_CLEAN), "道路名称未提供");
        eq(Protocol.clusterRoad("进入八一路 洪山侧路", Protocol.ROAD_CLEAN), "进入八一路 洪山侧路");
        // Do not remove words inside the road content or shorten English names.
        eq(Protocol.clusterRoad("去往 八一路 进入 洪山侧路", Protocol.ROAD_CLEAN), "八一路 进入 洪山侧路");
        eq(Protocol.clusterRoad("Main Street", Protocol.ROAD_CLEAN), "Main Street");
        eq(Protocol.clusterRoad("去往 Main Street Second Avenue", Protocol.ROAD_CLEAN), "Main Street Second Avenue");
        eq(Protocol.clusterRoad("Main Street", Protocol.ROAD_ORIGINAL), "Main Street");
        // Formatting cannot repair text that was already corrupted upstream.
        eq(Protocol.clusterRoad("八?路 洪山侧路", Protocol.ROAD_CLEAN), "八?路 洪山侧路");
        eq(Protocol.clusterRoad("八\ufffd路 洪山侧路", Protocol.ROAD_CLEAN), "八\ufffd路 洪山侧路");

        NavigationLogState logs = new NavigationLogState();
        frame(logs, 0, "5202", "[0] param: u16=42", "[1] param: u16=4",
            "[2] param: \"" + dual + "\"", "[3] param: u8=1");
        frame(logs, 10, "5201", "[0] param: u16=42", "[1] param: u8=1",
            "[13] param: u16=4", "[10] param: u32=120");
        for (String mode : new String[]{Protocol.ROAD_ORIGINAL, Protocol.ROAD_CLEAN, "first", "comma"}) {
            String candidate = logs.road("turn_left", 120, BASE + 20);
            Protocol.clusterRoad(candidate, mode);
            eq(logs.road("turn_left", 120, BASE + 20), dual);
            eq(logs.road("turn_right", 120, BASE + 20), "");
            eq(logs.road("turn_left", 130, BASE + 20), "");
        }

        // Switching the choice changes the actual outgoing identity, with the
        // existing 500ms limit. No navigation reset or extra clear is needed.
        NavigationDelivery delivery = new NavigationDelivery();
        long now = 1000;
        for (String mode : new String[]{Protocol.ROAD_ORIGINAL, Protocol.ROAD_CLEAN, Protocol.ROAD_ORIGINAL}) {
            String display = Protocol.clusterRoad("去往 " + dual, mode);
            eq(delivery.next("turn_left", 120, display, now), NavigationDelivery.Action.SEND);
            delivery.sent("turn_left", 120, display, now);
            eq(delivery.next("turn_left", 120, "另一条路", now + 1), NavigationDelivery.Action.WAIT);
            eq(delivery.retryDelay(now + 1), 499L);
            now += 500;
        }
        delivery.reset();
        String clean = Protocol.clusterRoad("去往 " + dual, Protocol.ROAD_CLEAN);
        eq(delivery.next("turn_right", 600, clean, 1000), NavigationDelivery.Action.SEND);
        delivery.sent("turn_right", 600, clean, 1000);
        String changedSecondRoad = Protocol.clusterRoad("去往 八一路 东湖路", Protocol.ROAD_CLEAN);
        eq(delivery.next("turn_right", 600, changedSecondRoad, 1500), NavigationDelivery.Action.SEND);
        delivery.sent("turn_right", 600, changedSecondRoad, 1500);
        eq(delivery.next("turn_right", 600, clean, 2000), NavigationDelivery.Action.SEND);
        delivery.sent("turn_right", 600, clean, 2000);
        // A new single road or changed direction/distance still causes output.
        eq(delivery.next("turn_right", 600, Protocol.clusterRoad("珞瑜路", Protocol.ROAD_CLEAN), 2500), NavigationDelivery.Action.SEND);
        delivery.sent("turn_right", 600, "珞瑜路", 2500);
        eq(delivery.next("turn_left", 600, "珞瑜路", 3000), NavigationDelivery.Action.SEND);
        delivery.sent("turn_left", 600, "珞瑜路", 3000);
        eq(delivery.next("turn_left", 590, "珞瑜路", 3500), NavigationDelivery.Action.SEND);
        System.out.println("Road display mode tests passed (" + checks + " checks: prompt-only removal, all road names, legacy migration, Unicode spaces, source isolation, following road, delivery limits)");
    }
}
