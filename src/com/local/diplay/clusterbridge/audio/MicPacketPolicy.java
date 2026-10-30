package com.local.diplay.clusterbridge.audio;

/** Audited, opt-in PCM packet sizing. Does not alter sample rate, routing or encoded audio. */
public final class MicPacketPolicy {
    public static final String REVISION = "wired-mic-packet-2";
    public static final long LEASE_MS = 15000;
    private MicPacketPolicy() { }

    public static int frameMillis(boolean wired, String codec, String audioType,
            int rate, int channels, int original, boolean explicitFrame) {
        boolean input = "telephony".equals(audioType) || "speechrecognition".equals(audioType)
            || "default".equals(audioType) || "compatibility".equals(audioType);
        return wired && input && "LPCM".equals(codec) && rate == 48000 && channels == 1
            && original == 20 && !explicitFrame ? 10 : original;
    }
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
