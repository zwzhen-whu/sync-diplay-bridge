package com.local.diplay.clusterbridge.audio;

/** Pure identity and lease checks for the one audited CarPlay audio path. */
public final class CallAudioPolicy {
    public static final String REVISION = "call-volume-3";
    public static final long LEASE_MS = 15000;
    private CallAudioPolicy() { }

    public static boolean lease(boolean enabled, long expires, long now, int pid, int expectedPid) {
        return enabled && pid > 0 && pid == expectedPid && now >= 0
            && expires > now && expires - now <= LEASE_MS;
    }

    public static boolean owns(String revision, String helper, int uid, int expectedUid,
            int helperCode, int expectedCode, String start, String expectedStart) {
        return REVISION.equals(revision) && "com.local.diplay.clusterbridge".equals(helper)
            && expectedUid >= 10000 && uid == expectedUid && helperCode == expectedCode
            && start != null && start.matches("[0-9]+") && start.equals(expectedStart);
    }
}
