package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit phone-menu commands emitted by the verified OEM instrument process. */
public final class InstrumentCallInput {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+(\\d+)\\s+I\\s+InsClusterController\\s*:\\s*(.*)$");
    private static final Pattern INCOMING = Pattern.compile("hqj incommingByte is ([12])");
    private static final Pattern END = Pattern.compile("hqj endByte is = ([1-9]\\d{0,2})");
    public final long wall;
    public final boolean incomingOnly;
    public final MediaKeyPolicy.Action action;
    private InstrumentCallInput(long wall, boolean incomingOnly, MediaKeyPolicy.Action action) {
        this.wall = wall; this.incomingOnly = incomingOnly; this.action = action;
    }
    public static InstrumentCallInput parse(String line, int trustedUid, long enabledAt, long now) {
        if (line == null || line.length() > 512 || trustedUid <= 0) return null;
        Matcher log = LINE.matcher(line); if (!log.matches()) return null;
        try {
            if (Integer.parseInt(log.group(3)) != trustedUid) return null;
            long wall = Long.parseLong(log.group(1)) * 1000 + Long.parseLong((log.group(2) + "000").substring(0, 3));
            if (wall < enabledAt || wall > now || now - wall > 1000) return null;
            Matcher incoming = INCOMING.matcher(log.group(6));
            if (incoming.matches()) return new InstrumentCallInput(wall, true,
                incoming.group(1).equals("1") ? MediaKeyPolicy.Action.ANSWER : MediaKeyPolicy.Action.END);
            if (END.matcher(log.group(6)).matches()) return new InstrumentCallInput(wall, false, MediaKeyPolicy.Action.END);
        } catch (NumberFormatException ignored) { }
        return null;
    }
    public int key(MediaKeyPolicy.Snapshot call, long callWall) {
        if (callWall <= 0 || callWall > wall || call == null || call.pid <= 0 || call.status <= 0 || call.status > 5
            || incomingOnly && call.status != 2) return -1;
        int key = action == MediaKeyPolicy.Action.ANSWER ? MediaKeyPolicy.PREVIOUS : MediaKeyPolicy.NEXT;
        return MediaKeyPolicy.action(key, call.status) == action ? key : -1;
    }
}
