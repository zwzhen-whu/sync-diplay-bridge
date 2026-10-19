package com.local.diplay.clusterbridge;

/** Coalesce transport callbacks without delaying valid instructions indefinitely. */
public final class NavigationDelivery {
    public enum Action { NONE, WAIT, SEND }
    public static final long SYNC_WAIT_MS = 1500, MIN_INTERVAL_MS = 500, HEARTBEAT_MS = 1000;
    private String lastIdentity;
    private long lastSent = -1, missingSince = -1, retryAt = -1;

    public Action next(String turn, int meters, String rawRoad, long now) {
        retryAt = -1;
        if (lastSent > now || missingSince > now) reset();
        if (Protocol.roadName(rawRoad).isEmpty()) {
            if (missingSince < 0) missingSince = now;
            if (now - missingSince < SYNC_WAIT_MS) {
                retryAt = missingSince + SYNC_WAIT_MS;
                return Action.WAIT;
            }
        } else missingSince = -1;
        if (lastSent >= 0 && now - lastSent < MIN_INTERVAL_MS) {
            retryAt = lastSent + MIN_INTERVAL_MS;
            return Action.WAIT;
        }
        if (identity(turn, meters, rawRoad).equals(lastIdentity) && now - lastSent < HEARTBEAT_MS) return Action.NONE;
        return Action.SEND;
    }

    public void sent(String turn, int meters, String rawRoad, long now) {
        lastIdentity = identity(turn, meters, rawRoad); lastSent = now; retryAt = -1;
    }
    public long retryDelay(long now) { return retryAt < 0 ? -1 : Math.max(1, retryAt - now); }
    public void reset() { lastIdentity = null; lastSent = missingSince = retryAt = -1; }
    private String identity(String turn, int meters, String road) {
        return turn + "\u0001" + meters + "\u0001" + Protocol.clusterRoad(road);
    }
}
