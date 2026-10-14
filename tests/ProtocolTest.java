package com.local.diplay.clusterbridge;

public final class ProtocolTest {
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        eq(Protocol.meters("230 米"), 230); eq(Protocol.meters("1.2 km"), 1200);
        eq(Protocol.meters("1,2 km"), 1200); eq(Protocol.meters("١٫٢ كم"), 1200);
        eq(Protocol.meters("١٢٠ م"), 120); eq(Protocol.meters("２.５ 公里"), 2500);
        eq(Protocol.meters("0 m"), 0); eq(Protocol.meters("CarPlay"), null);
        eq(Protocol.meters("-10 米"), null); eq(Protocol.meters("1.2 miles"), null);
        eq(Protocol.meters("999999999999999 km"), null); eq(Protocol.meters("1,234,567 m"), null);
        eq(Protocol.knownTurn(null), false); eq(Protocol.knownTurn("unknown"), false);
        eq(Protocol.knownTurn("turn_front"), true); eq(Protocol.knownTurn("turn_ring"), true);
        eq(Protocol.song("♪ 歌名 · 艺术家").text, "歌名 · 艺术家");
        eq(Protocol.song("♪ 歌名").playing, true); eq(Protocol.song("❚❚ 歌名").playing, false);
        eq(Protocol.song("未知状态 歌名"), null); eq(Protocol.song("♪ "), null);
        eq(Protocol.clusterRoad(null), "道路名称未提供"); eq(Protocol.clusterRoad(""), "道路名称未提供");
        eq(Protocol.clusterRoad(" \u00a0\u3000\u0000"), "道路名称未提供"); eq(Protocol.clusterRoad(" NULL "), "道路名称未提供");
        eq(Protocol.clusterRoad(" 进入 中山路 "), "进入 中山路"); eq(Protocol.clusterRoad("Nullarbor Road"), "Nullarbor Road");
        eq(Protocol.turnForAppleType(1), "turn_left"); eq(Protocol.turnForAppleType(47), "turn_left_back");
        eq(Protocol.turnForAppleType(255), null); eq(Protocol.roundedWidgetMeters(46), 50);
        eq(Protocol.roundedWidgetMeters(1), 0); eq(Protocol.roundedWidgetMeters(1234), 1200);
        eq(Protocol.roundedWidgetMeters(15750), 16000);
        System.out.println("Protocol tests passed (distance locales, invalid data, turns, playback prefixes)");
    }
}
