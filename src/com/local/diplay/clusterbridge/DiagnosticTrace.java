package com.local.diplay.clusterbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Allow only fresh navigation parameters from the current source UID. Never copy call frames. */
public final class DiagnosticTrace {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(\\S+)\\s*:\\s?(.*)$");
    private static final Pattern FRAME = Pattern.compile("IAP2 RX \\[[^\\]]+\\] 0x(5201|5202)\\b.*");
    private static final Pattern PARAM = Pattern.compile("\\s*\\[(\\d+)\\] [^:\\r\\n]{1,80}: (.*)");
    private final Map<String, Integer> pending = new HashMap<>();
    private final long startWall;
    public DiagnosticTrace(long startWall) { this.startWall = startWall; }

    public synchronized String accept(String line, int uid, long nowWall) {
        if (line == null || line.length() > 4096 || uid < 0) return null;
        Matcher parsed = LINE.matcher(line);
        if (!parsed.matches() || !"xcertplay-usb".equals(parsed.group(6))) return null;
        try {
            if (Integer.parseInt(parsed.group(3)) != uid) return null;
            long wall = Long.parseLong(parsed.group(1)) * 1000 + Long.parseLong((parsed.group(2) + "000").substring(0, 3));
            if (wall < startWall || wall > nowWall + 1000) return null;
            String thread = parsed.group(4) + ":" + parsed.group(5), message = parsed.group(7);
            if (message.startsWith("IAP2 RX")) {
                pending.remove(thread);
                Matcher frame = FRAME.matcher(message);
                if (!frame.matches()) return null;
                if (pending.size() >= 8) pending.clear();
                int kind = Integer.parseInt(frame.group(1), 16); pending.put(thread, kind);
                return "wall=" + wall + " thread=" + thread + " RX=0x" + frame.group(1);
            }
            Integer kind = pending.get(thread);
            if (kind == null) return null;
            if (message.trim().startsWith("raw-body=")) {
                pending.remove(thread);
                return "wall=" + wall + " thread=" + thread + " frame_end (raw bytes omitted)";
            }
            Matcher parameter = PARAM.matcher(message);
            if (!parameter.matches()) { pending.remove(thread); return null; }
            int id = Integer.parseInt(parameter.group(1));
            if (id == 0) return "wall=" + wall + " thread=" + thread + " navigation_id=[omitted]";
            boolean allowed = kind == 0x5201 ? id == 1 || id == 3 || id == 10 || id == 13
                : id == 1 || id == 2 || id == 3 || id == 4;
            return allowed ? "wall=" + wall + " thread=" + thread + " nav_param[" + id + "]=" + DiagnosticReport.clean(parameter.group(2), 512) : null;
        } catch (NumberFormatException ignored) { return null; }
    }
}
