package com.local.diplay.clusterbridge;

/** Retain the latest activity across empty queries; an idle page emits no new event. */
public final class ForegroundActivityState {
    private long queriedWall = -1, eventWall = -1;
    private String packageName, className;

    public long queryStart(long wall, long elapsed) {
        if (queriedWall > wall) clear();
        long boot = Math.max(0, wall - elapsed - 1000);
        return queriedWall < 0 ? boot : Math.max(boot, queriedWall - 1000);
    }

    public void event(int type, String eventPackage, String eventClass, long time) {
        if ((type != 1 && type != 2) || time < eventWall) return;
        eventWall = time;
        if (type == 1) { packageName = eventPackage; className = eventClass; }
        else if (packageName != null && packageName.equals(eventPackage)
                && (eventClass == null || eventClass.equals(className))) {
            packageName = null; className = null;
        }
    }

    public void queried(long wall) { queriedWall = wall; }
    public String packageName() { return packageName; }
    public String className() { return className; }
    public void clear() { queriedWall = -1; eventWall = -1; packageName = null; className = null; }
}
