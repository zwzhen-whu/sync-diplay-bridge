package com.local.diplay.clusterbridge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional fallback for DiPlay's existing iAP2 navigation diagnostics. */
public final class NavigationLogState {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(\\S+)\\s*:\\s?(.*)$");
    private static final Pattern FRAME = Pattern.compile("IAP2 RX \\[([^\\]]+)\\] 0x(5201|5202)\\b.*");
    private static final Pattern PARAM = Pattern.compile("\\s*\\[(\\d+)\\] param: (.*)");
    private static final Pattern NUMBER = Pattern.compile("u(?:8|16|32)=(\\d+)\\b.*");
    private static final long ROUTE_TIMEOUT = 15000;
    private final Map<String, Frame> pending = new HashMap<>();
    private final LinkedHashMap<String, Maneuver> maneuvers = new LinkedHashMap<>();
    private Route route;

    private static final class Frame {
        String session; int kind, pid; long wall;
        final Map<Integer, String> parameters = new HashMap<>();
        String prefix() { return pid + ":" + session + ":" + number(parameters.get(0)); }
    }
    private static final class Maneuver {
        int type; String description, road; long wall;
    }
    private static final class Route {
        String prefix; int index, distance; long wall;
    }

    /** Return true only when a complete, trusted navigation frame changed the cache. */
    public synchronized boolean accept(String line, int sourceUid, long nowWall) {
        if (line == null || line.length() > 4096) return false;
        Matcher parsed = LINE.matcher(line);
        if (!parsed.matches()) return false;
        try {
            if (Integer.parseInt(parsed.group(3)) != sourceUid || !"xcertplay-usb".equals(parsed.group(6))) return false;
            long wall = Long.parseLong(parsed.group(1)) * 1000 + Long.parseLong((parsed.group(2) + "000").substring(0, 3));
            if (wall > nowWall + 1000) return false;
            int pid = Integer.parseInt(parsed.group(4));
            String thread = pid + ":" + parsed.group(5);
            String message = parsed.group(7);
            if (message.startsWith("IAP2 RX")) {
                pending.remove(thread);
                Matcher frame = FRAME.matcher(message);
                if (frame.matches()) {
                    if (pending.size() >= 8) pending.clear();
                    Frame next = new Frame(); next.session = frame.group(1); next.kind = Integer.parseInt(frame.group(2), 16); next.pid = pid; next.wall = wall;
                    pending.put(thread, next);
                }
                return false;
            }
            Frame frame = pending.get(thread);
            if (frame == null) return false;
            Matcher parameter = PARAM.matcher(message);
            if (parameter.matches()) {
                if (frame.parameters.size() >= 32) { pending.remove(thread); return false; }
                int id = Integer.parseInt(parameter.group(1));
                if (frame.parameters.put(id, parameter.group(2)) != null) pending.remove(thread);
            } else if (message.trim().startsWith("raw-body=")) {
                pending.remove(thread); return commit(frame, nowWall);
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private boolean commit(Frame frame, long nowWall) {
        if (typedNumber(frame.parameters.get(0), 16) == null) return false;
        String prefix = frame.prefix();
        if (frame.kind == 0x5202) {
            Integer index = typedNumber(frame.parameters.get(1), 16), type = typedNumber(frame.parameters.get(3), 8);
            if (index == null || type == null) return false;
            if (index == 0) {
                maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(prefix + ":") && e.getValue().wall <= frame.wall);
                if (route != null && route.prefix.equals(prefix) && route.wall <= frame.wall) route = null;
            }
            String key = prefix + ":" + index;
            Maneuver previous = maneuvers.get(key);
            if (previous != null && previous.wall > frame.wall) return false;
            Maneuver next = new Maneuver(); next.type = type; next.wall = frame.wall;
            next.description = quoted(frame.parameters.get(2)); next.road = quoted(frame.parameters.get(4));
            maneuvers.put(key, next);
            while (maneuvers.size() > 64) maneuvers.remove(maneuvers.keySet().iterator().next());
            return true;
        }
        Integer state = typedNumber(frame.parameters.get(1), 8);
        if (route != null && route.wall > frame.wall) return false;
        if (state != null && (state == 0 || state == 2)) {
            if (route != null && route.prefix.equals(prefix)) route = null;
            maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(prefix + ":") && e.getValue().wall <= frame.wall);
            return true;
        }
        if (nowWall - frame.wall > ROUTE_TIMEOUT || state == null || state != 1) return false;
        Integer index = typedNumber(frame.parameters.get(13), 16), distance = typedNumber(frame.parameters.get(10), 32);
        // The log must represent one active maneuver, not an unparsed list or a guess.
        if (index == null || distance == null) { route = null; return true; }
        Route next = new Route(); next.prefix = prefix; next.index = index; next.distance = distance; next.wall = frame.wall;
        route = next; return true;
    }

    public synchronized String road(String widgetTurn, Integer widgetMeters, long nowWall) {
        if (route == null || widgetTurn == null || widgetMeters == null || nowWall < route.wall || nowWall - route.wall > ROUTE_TIMEOUT) return "";
        Maneuver maneuver = maneuvers.get(route.prefix + ":" + route.index);
        if (maneuver == null || maneuver.wall > route.wall || !widgetTurn.equals(Protocol.turnForAppleType(maneuver.type))) return "";
        if (widgetMeters != Protocol.roundedWidgetMeters(route.distance)) return "";
        String road = Protocol.roadName(maneuver.road);
        return road.isEmpty() ? Protocol.roadName(maneuver.description) : road;
    }

    public synchronized void clear() { pending.clear(); maneuvers.clear(); route = null; }

    /** Persist parsed maneuver records, never raw system logs or an active route decision. */
    public synchronized String savedManeuvers() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Maneuver> entry : maneuvers.entrySet()) {
            Maneuver m = entry.getValue();
            text.append(encoded(entry.getKey())).append('\t').append(m.type).append('\t').append(m.wall)
                .append('\t').append(encoded(m.description)).append('\t').append(encoded(m.road)).append('\n');
        }
        return text.toString();
    }
    public synchronized void restoreManeuvers(String text, long nowWall) {
        if (text == null || text.length() > 100000) return;
        for (String line : text.split("\n")) {
            if (maneuvers.size() >= 64) break;
            String[] values = line.split("\t", -1);
            if (values.length != 5) continue;
            try {
                long wall = Long.parseLong(values[2]);
                if (wall > nowWall || nowWall - wall > 86400000L) continue;
                String key = decoded(values[0]); if (key.length() > 200) continue;
                Maneuver next = new Maneuver(); next.type = Integer.parseInt(values[1]); next.wall = wall;
                next.description = decoded(values[3]); next.road = decoded(values[4]);
                if (next.description.length() > 512 || next.road.length() > 512) continue;
                maneuvers.put(key, next);
            } catch (IllegalArgumentException ignored) { }
        }
    }
    private static String encoded(String text) { return Base64.getEncoder().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)); }
    private static String decoded(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }

    private static Integer number(String value) {
        if (value == null) return null;
        Matcher number = NUMBER.matcher(value);
        if (!number.matches()) return null;
        try { return Integer.valueOf(number.group(1)); } catch (NumberFormatException e) { return null; }
    }
    private static String quoted(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') return "";
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i >= value.length() - 1) return "";
                char escaped = value.charAt(i);
                if (escaped == '\\' || escaped == '"') c = escaped;
                else return "";
            }
            result.append(c);
        }
        return result.toString();
    }

    private static Integer typedNumber(String value, int bits) {
        if (value == null || !value.startsWith("u" + bits + "=")) return null;
        Integer result = number(value);
        if (result == null || (bits < 32 && result >= (1 << bits))) return null;
        return result;
    }
}
