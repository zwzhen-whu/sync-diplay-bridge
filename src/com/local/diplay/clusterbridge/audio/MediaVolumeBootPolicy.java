package com.local.diplay.clusterbridge.audio;

/** One restore per complete Android boot, after the startup volume has settled. */
public final class MediaVolumeBootPolicy {
    public static final int OFF = 0, REMEMBER = 1, FIXED = 2;
    public static final long BOOT_WINDOW = 180000, STARTUP_DELAY = 0, STABLE_DELAY = 200;
    private final long started;
    private long stable;
    private int lastLevel = -1, lastMaximum = -1;
    private boolean pending;
    public MediaVolumeBootPolicy(int mode, int boot, int handledBoot, long now) {
        started = stable = now;
        pending = (mode == REMEMBER || mode == FIXED) && boot >= 0 && boot != handledBoot && now >= 0 && now <= BOOT_WINDOW;
    }
    public boolean pending() { return pending; }
    public boolean ready(int level, int maximum, boolean normalAudio, long now) {
        if (!pending) return false;
        if (now < started || now > BOOT_WINDOW) { pending = false; return false; }
        if (maximum <= 0 || level < 0 || level > maximum || !normalAudio) { stable = now; return false; }
        if (level != lastLevel || maximum != lastMaximum) { lastLevel = level; lastMaximum = maximum; stable = now; }
        return now - started >= STARTUP_DELAY && now - stable >= STABLE_DELAY;
    }
    public void finish() { pending = false; }
    public static int scale(int level, int savedMaximum, int maximum) {
        if (level < 0 || savedMaximum <= 0 || maximum <= 0) return -1;
        return (int)Math.min(maximum, (Math.min(level, savedMaximum) * (long)maximum + savedMaximum / 2) / savedMaximum);
    }
}
