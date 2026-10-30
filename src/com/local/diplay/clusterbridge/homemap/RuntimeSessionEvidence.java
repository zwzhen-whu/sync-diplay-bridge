package com.local.diplay.clusterbridge.homemap;

/** A report from an older connection must not authorize attachment during a new USB handshake. */
final class RuntimeSessionEvidence {
    static boolean connected(String tail) {
        int boundary = -1;
        for (String marker : new String[]{"phase=IDLE start transport=", "teardown begin transport=",
                "stage=Failed", "AirPlay session ended", "Host started;"})
            boundary = Math.max(boundary, tail.lastIndexOf(marker));
        int active = tail.lastIndexOf("AirPlay session active");
        int picture = tail.lastIndexOf("Video: video stats rx=");
        if (active > boundary && picture > active) return true;
        // Long-lived sessions eventually rotate their original success line out of the bounded tail.
        return boundary < 0 && picture >= 0;
    }
}
