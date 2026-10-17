package com.local.diplay.clusterbridge;

/** Separate a granted permission from process groups, and cached data from another boot. */
public final class LogAccess {
    public static Boolean hasLogGroup(String processStatus) {
        if (processStatus == null) return null;
        for (String line : processStatus.split("\n")) {
            if (!line.startsWith("Groups:")) continue;
            for (String group : line.substring(7).trim().split("\\s+")) if (group.equals("1007")) return true;
            return false;
        }
        return null;
    }

    public static boolean sameCacheEpoch(int savedUid, int sourceUid, int savedBoot, int currentBoot) {
        return sourceUid >= 0 && savedUid == sourceUid && currentBoot >= 0 && savedBoot == currentBoot;
    }
}
