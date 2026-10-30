package com.local.diplay.clusterbridge.homemap;

import java.util.LinkedHashMap;
import java.util.Map;

/** Only appearance and enablement can cross from the retired independent app. */
final class MapMigrationPolicy {
    static final String OWNER = "com.local.diplay.clusterbridge";
    static final String REVISION = "bridge-decoder-only-1";
    static Map<String, Object> appearance(Map<String, String> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        int width = number(values, "width", 520, 360, 1000);
        int height = number(values, "height", 340, 240, 560);
        result.put("width", width); result.put("height", height);
        // Preserve position until the UI process knows this vehicle's actual display bounds.
        result.put("x", number(values, "x", 24, 0, 1_000_000));
        result.put("y", number(values, "y", 278, 0, 1_000_000));
        result.put("feather_percent", number(values, "feather_percent", 100, 0, 200));
        result.put("enabled", Boolean.parseBoolean(values.get("enabled")));
        return result;
    }
    private static int number(Map<String, String> values, String name, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(values.get(name)))); }
        catch (RuntimeException ignored) { return Math.max(min, Math.min(max, fallback)); }
    }
    static boolean ownsRuntime(String revision, String owner, int uid, int expectedUid) {
        return expectedUid >= 10000 && REVISION.equals(revision) && OWNER.equals(owner) && uid == expectedUid;
    }
}
