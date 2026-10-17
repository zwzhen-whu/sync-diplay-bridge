package com.local.diplay.clusterbridge;

/** Bounded startup recovery, confirmed by the foreground player rather than click dispatch. */
public final class CarPlayEntryPolicy {
    public static final String SOURCE = "com.shihab.diplay";
    public static final String ENTRY = "com.shilapi.xcertplay.DiPlayActivity";
    public static final String PLAYER = "com.shilapi.xcertplay.CarPlayHostActivity";
    public static final long WINDOW_MS = 90000, RETRY_MS = 8000, SETTLE_MS = 10000;
    public static final int MAX_ATTEMPTS = 3;
    private long connectedAt = -1, candidateAt = -1, playerAt = -1, lastAttemptAt = -1;
    private boolean connected, finished, confirmed;
    private int attempts;

    public void connection(boolean value, long now) {
        if (!value || !connected) {
            connectedAt = value ? now : -1; candidateAt = playerAt = lastAttemptAt = -1;
            finished = confirmed = false; attempts = 0;
        }
        connected = value;
    }

    public boolean watching(long now) {
        if (connectedAt >= 0 && (now < connectedAt || now - connectedAt > WINDOW_MS)) finished = true;
        return connected && !finished && now - connectedAt >= 3000;
    }

    public boolean request(long now, String foregroundPackage, String foregroundClass) {
        if (!watching(now)) return false;
        if (foregroundPackage == null || foregroundClass == null) { unknownForeground(); return false; }
        if (SOURCE.equals(foregroundPackage) && PLAYER.equals(foregroundClass)) {
            // First-boot startup can return to the entry activity several
            // seconds AFTER the session and its video activity become active.
            candidateAt = -1;
            if (playerAt < 0) playerAt = now;
            if (now - playerAt >= SETTLE_MS) confirmed = true;
            return false;
        }
        if (SOURCE.equals(foregroundPackage) && ENTRY.equals(foregroundClass)) {
            playerAt = -1; confirmed = false;
            if (candidateAt < 0) candidateAt = now;
            if (now - candidateAt < 2000 || attempts >= MAX_ATTEMPTS
                || (lastAttemptAt >= 0 && now - lastAttemptAt < RETRY_MS)) return false;
            attempts++; lastAttemptAt = now; candidateAt = now;
            return true;
        }
        // Boot launchers and permission screens are not evidence of a failed
        // connection. Pause here; never request a jump while another page is front.
        unknownForeground();
        return false;
    }

    public void unknownForeground() { candidateAt = playerAt = -1; confirmed = false; }
    public int attempts() { return attempts; }
    public boolean confirmed() { return confirmed; }
}
