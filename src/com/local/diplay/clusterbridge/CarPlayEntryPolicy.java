package com.local.diplay.clusterbridge;

/** One optional entry request during a short, confirmed connection window. */
public final class CarPlayEntryPolicy {
    public static final String SOURCE = "com.shihab.diplay";
    public static final String ENTRY = "com.shilapi.xcertplay.DiPlayActivity";
    public static final String PLAYER = "com.shilapi.xcertplay.CarPlayHostActivity";
    private long connectedAt = -1, candidateAt = -1;
    private boolean connected, finished;

    public void connection(boolean value, long now) {
        if (!value) { connectedAt = -1; candidateAt = -1; finished = false; }
        else if (!connected) { connectedAt = now; candidateAt = -1; finished = false; }
        connected = value;
    }

    public boolean watching(long now) {
        if (connectedAt >= 0 && (now < connectedAt || now - connectedAt > 30000)) finished = true;
        return connected && !finished && now - connectedAt >= 3000;
    }

    public boolean request(long now, String foregroundPackage, String foregroundClass) {
        if (!watching(now)) return false;
        if (foregroundPackage == null || foregroundClass == null) { candidateAt = -1; return false; }
        if (SOURCE.equals(foregroundPackage) && PLAYER.equals(foregroundClass)) {
            // First-boot startup can return to the entry activity several
            // seconds AFTER the session and its video activity become active.
            candidateAt = -1; return false;
        }
        if (SOURCE.equals(foregroundPackage) && ENTRY.equals(foregroundClass)) {
            if (candidateAt < 0) candidateAt = now;
            if (now - candidateAt < 2000) return false;
            finished = true; return true;
        }
        candidateAt = -1;
        // The bridge may be open while the user enables the option. Every other
        // activity, including DiPlay's other screens, ends this connection's window.
        if (!"com.local.diplay.clusterbridge".equals(foregroundPackage)) finished = true;
        return false;
    }

    public void unknownForeground() { candidateAt = -1; }
}
