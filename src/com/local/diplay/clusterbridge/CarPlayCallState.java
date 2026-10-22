package com.local.diplay.clusterbridge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ephemeral call metadata from complete, live DiPlay 0.2.19 diagnostic frames. */
public final class CarPlayCallState {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(\\S+)\\s*:\\s?(.*)$");
    private static final Pattern FRAME = Pattern.compile("IAP2 RX \\[([^\\]]{1,80})\\] 0x4155\\b.*\\bbody=(\\d{1,2}) params\\b.*");
    private static final Pattern PARAM = Pattern.compile("\\s*\\[(\\d+)\\] [^:\\r\\n]{1,80}: (.*)");
    private static final Pattern SCALAR = Pattern.compile("u8=([0-6]) \\(0x0[0-6]\\)");
    private static final Pattern CLOSE = Pattern.compile("IAP2 CLOSE \\[([^\\]]+)\\].*");
    private final Map<String, Frame> pending = new HashMap<>();
    private final LinkedHashMap<String, Call> calls = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> ended = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> closed = new LinkedHashMap<>();
    private long floor, latestWall, lastNow;
    private int sourcePid = -1, parsed;

    public static final class Call {
        public final String key, name, number;
        public final int status, pid;
        public final long activeSince, wall;
        Call(String key, String name, String number, int status, int pid, long activeSince, long wall) {
            this.key = key; this.name = name; this.number = number; this.status = status; this.pid = pid;
            this.activeSince = activeSince; this.wall = wall;
        }
        public int seconds(long elapsed) { return activeSince < 0 ? 0 : (int)Math.min(604800, Math.max(0, elapsed - activeSince) / 1000); }
        public String phase() { return status == 2 ? "来电" : status == 4 ? "通话中" : status == 5 ? "保持中" : "拨号中"; }
    }
    private static final class Frame {
        String session; int pid, count; long wall;
        final Map<Integer, String> values = new HashMap<>();
    }

    public synchronized void reset(long now) {
        pending.clear(); calls.clear(); ended.clear(); closed.clear(); sourcePid = -1; floor = now; latestWall = now; lastNow = now;
    }
    public synchronized int framesParsed() { return parsed; }
    public synchronized Call current() {
        for (int wanted : new int[]{2, 4, 5, 1, 3})
            for (Call call : calls.values()) if (call.status == wanted) return call;
        return null;
    }

    public synchronized boolean accept(String line, int uid, long now, long elapsed) {
        if (now < lastNow - 1000) reset(now);
        lastNow = now;
        if (line == null || line.length() > 4096) return false;
        Matcher log = LINE.matcher(line);
        if (!log.matches()) return false;
        try {
            if (Integer.parseInt(log.group(3)) != uid || !"xcertplay-usb".equals(log.group(6))) return false;
            long wall = Long.parseLong(log.group(1)) * 1000 + Long.parseLong((log.group(2) + "000").substring(0, 3));
            if (wall < floor || wall > now + 1000 || now - wall > 10000) return false;
            int pid = Integer.parseInt(log.group(4));
            String thread = pid + ":" + log.group(5), message = log.group(7);
            Matcher close = CLOSE.matcher(message);
            if (close.matches()) {
                String prefix = pid + ":" + close.group(1) + ":";
                if (closed.size() >= 8) closed.remove(closed.keySet().iterator().next());
                closed.put(prefix, Math.max(wall, closed.containsKey(prefix) ? closed.get(prefix) : 0));
                pending.entrySet().removeIf(e -> e.getValue().pid == pid && close.group(1).equals(e.getValue().session));
                return calls.entrySet().removeIf(e -> e.getKey().startsWith(prefix) && wall >= e.getValue().wall);
            }
            if (message.startsWith("IAP2 RX")) {
                pending.remove(thread);
                if (pid != sourcePid && wall >= latestWall) {
                    boolean hadCalls = !calls.isEmpty(); calls.clear(); ended.clear(); closed.clear(); pending.clear(); sourcePid = pid; latestWall = wall;
                    Matcher begin = FRAME.matcher(message);
                    if (begin.matches()) begin(thread, begin.group(1), Integer.parseInt(begin.group(2)), pid, wall);
                    return hadCalls;
                }
                if (pid != sourcePid) return false;
                Matcher begin = FRAME.matcher(message);
                if (begin.matches()) begin(thread, begin.group(1), Integer.parseInt(begin.group(2)), pid, wall);
                return false;
            }
            Frame frame = pending.get(thread);
            if (frame == null) return false;
            if (wall - frame.wall > 2000 || wall < frame.wall) { pending.remove(thread); return false; }
            Matcher parameter = PARAM.matcher(message);
            if (parameter.matches()) {
                if (frame.values.size() >= 20 || frame.values.put(Integer.parseInt(parameter.group(1)), parameter.group(2)) != null)
                    pending.remove(thread);
            } else if (message.trim().startsWith("raw-body=")) {
                pending.remove(thread); return commit(frame, elapsed);
            } else if (!message.trim().isEmpty()) pending.remove(thread);
        } catch (NumberFormatException ignored) { }
        return false;
    }
    private void begin(String thread, String session, int count, int pid, long wall) {
        if (count < 2 || count > 20) return;
        if (pending.size() >= 8) pending.clear();
        Frame frame = new Frame(); frame.session = session; frame.count = count; frame.pid = pid; frame.wall = wall; pending.put(thread, frame);
    }
    private boolean commit(Frame frame, long elapsed) {
        String prefix = frame.pid + ":" + frame.session + ":";
        if (frame.values.size() != frame.count || closed.containsKey(prefix) && frame.wall <= closed.get(prefix)) return false;
        Integer status = status(frame.values.get(2));
        if (status == null) return false;
        String remote = quoted(frame.values.get(0)), name = quoted(frame.values.get(1)), id = quoted(frame.values.get(4));
        if (frame.values.containsKey(0) && remote == null || frame.values.containsKey(1) && name == null || frame.values.containsKey(4) && id == null) return false;
        if (id == null || id.isEmpty()) id = remote;
        if (id == null || id.isEmpty()) return false;
        String key = frame.pid + ":" + frame.session + ":" + id;
        Call old = calls.get(key);
        if (old != null && frame.wall < old.wall || ended.containsKey(key) && frame.wall <= ended.get(key)) return false;
        parsed++; latestWall = Math.max(latestWall, frame.wall);
        if (status == 0 || status == 6) {
            if (ended.size() >= 16) ended.remove(ended.keySet().iterator().next());
            ended.put(key, frame.wall); return calls.remove(key) != null;
        }
        if (calls.size() >= 8 && old == null) return false;
        String number = remote == null || remote.isEmpty() ? old == null ? "" : old.number : remote;
        String display = name == null || name.isEmpty() ? old != null && !old.name.isEmpty() ? old.name : number : name;
        long since = old == null ? -1 : old.activeSince;
        if (since < 0 && (status == 4 || status == 5)) since = elapsed;
        calls.put(key, new Call(key, CallDisplayProtocol.text(display, 32), CallDisplayProtocol.text(number, 48), status, frame.pid, since, frame.wall));
        return true;
    }
    static Integer status(String value) {
        // Unknown-schema opaque NUL is formatted as an empty quoted string by DiPlay.
        if ("\"\"".equals(value)) return 0;
        Matcher scalar = SCALAR.matcher(value == null ? "" : value);
        if (!scalar.matches()) return null;
        int number = Integer.parseInt(scalar.group(1));
        return value.endsWith("0x0" + number + ")") ? number : null;
    }
    static String quoted(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return null;
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '"' || Character.isISOControl(c)) return null;
            if (c == '\\') {
                if (++i >= value.length() - 1) return null;
                char escape = value.charAt(i);
                switch (escape) {
                    case '\\': c = '\\'; break; case '"': c = '"'; break;
                    case 'r': c = '\r'; break; case 'n': c = '\n'; break; case 't': c = '\t'; break;
                    default: return null;
                }
            }
            result.append(c);
        }
        return result.toString().trim();
    }
}
