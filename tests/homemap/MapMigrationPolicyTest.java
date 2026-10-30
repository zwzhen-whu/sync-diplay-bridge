package com.local.diplay.clusterbridge.homemap;

import java.util.HashMap;
import java.util.Map;

public final class MapMigrationPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        Map<String, String> old = new HashMap<>();
        old.put("width", "600"); old.put("height", "340"); old.put("x", "113"); old.put("y", "174");
        old.put("feather_percent", "180"); old.put("enabled", "true"); old.put("phone", "private");
        Map<String, Object> result = MapMigrationPolicy.appearance(old);
        equal(600, result.get("width")); equal(340, result.get("height")); equal(113, result.get("x"));
        equal(174, result.get("y")); equal(180, result.get("feather_percent")); equal(true, result.get("enabled"));
        equal(6, result.size()); equal(false, result.containsKey("phone"));
        old.put("x", "1800"); old.put("y", "740");
        result = MapMigrationPolicy.appearance(old);
        equal(1800, result.get("x")); equal(740, result.get("y"));
        old.put("width", "9999"); old.put("height", "9999"); old.put("x", "9999"); old.put("y", "9999"); old.put("feather_percent", "9999");
        result = MapMigrationPolicy.appearance(old);
        equal(1000, result.get("width")); equal(560, result.get("height")); equal(9999, result.get("x"));
        equal(9999, result.get("y")); equal(200, result.get("feather_percent"));
        old.put("x", "2147483647"); old.put("y", "2147483647");
        result = MapMigrationPolicy.appearance(old);
        equal(1000000, result.get("x")); equal(1000000, result.get("y"));
        old.put("width", "bad"); old.put("height", "-10"); old.put("x", "-10"); old.put("feather_percent", "-10");
        result = MapMigrationPolicy.appearance(old);
        equal(520, result.get("width")); equal(240, result.get("height")); equal(0, result.get("x")); equal(0, result.get("feather_percent"));
        result = MapMigrationPolicy.appearance(new HashMap<String, String>());
        equal(520, result.get("width")); equal(340, result.get("height")); equal(100, result.get("feather_percent")); equal(false, result.get("enabled"));
        equal(true, MapMigrationPolicy.ownsRuntime("bridge-decoder-only-1", "com.local.diplay.clusterbridge", 10088, 10088));
        equal(false, MapMigrationPolicy.ownsRuntime("decoder-only-7", "com.local.diplay.homemap", 10073, 10088));
        equal(false, MapMigrationPolicy.ownsRuntime("bridge-decoder-only-1", "com.local.diplay.clusterbridge", 10073, 10088));
        equal(false, MapMigrationPolicy.ownsRuntime("bridge-decoder-only-1", "other", 10088, 10088));
        equal(false, MapMigrationPolicy.ownsRuntime("bridge-decoder-only-1", "com.local.diplay.clusterbridge", 0, 0));
        System.out.println("MapMigrationPolicyTest: " + checks + " checks passed");
    }
    private static void equal(Object expected, Object actual) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
