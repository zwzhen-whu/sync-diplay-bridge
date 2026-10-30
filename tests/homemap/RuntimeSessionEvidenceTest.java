package com.local.diplay.clusterbridge.homemap;

public final class RuntimeSessionEvidenceTest {
    public static void main(String[] args) {
        String start = "phase=IDLE start transport=WIRED\n";
        String active = "AirPlay session active\n";
        String video = "Video: video stats rx=30.0fps shown=30.0fps\n";
        check(!RuntimeSessionEvidence.connected(""));
        check(!RuntimeSessionEvidence.connected(start));
        check(!RuntimeSessionEvidence.connected(start + active));
        check(!RuntimeSessionEvidence.connected(active + start));
        check(!RuntimeSessionEvidence.connected(active + start + video));
        check(RuntimeSessionEvidence.connected(video));
        check(!RuntimeSessionEvidence.connected(active + "teardown begin transport=WIRED\n"));
        check(!RuntimeSessionEvidence.connected(active + "AirPlay session ended\n"));
        check(RuntimeSessionEvidence.connected(active + start + active + video));
        System.out.println("RuntimeSessionEvidenceTest: 9 checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Session evidence mismatch"); }
}
