package com.local.diplay.clusterbridge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.ArrayDeque;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional fallback for DiPlay's existing iAP2 navigation diagnostics. */
public final class NavigationLogState {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+(\\S+)\\s*:\\s?(.*)$");
    private static final Pattern FRAME = Pattern.compile("IAP2 RX \\[([^\\]]+)\\] 0x(5201|5202)\\b.*");
    private static final Pattern PARAM = Pattern.compile("\\s*\\[(\\d+)\\] [^:\\r\\n]{1,80}: (.*)");
    private static final Pattern NUMBER = Pattern.compile("u(?:8|16|32)=(\\d+)\\b.*");
    private static final Pattern CLOSE = Pattern.compile("IAP2 CLOSE \\[([^\\]]+)\\]");
    private static final long ROUTE_TIMEOUT = 15000;
    // DiPlay throttles widget rendering to once per second. Keep a small,
    // short-lived set of real route snapshots until that view catches up.
    private static final long WIDGET_LAG_MS = 1500;
    private static final long EMPTY_LIST_HIDE_MS = 3000;
    private static final int MAX_MANEUVERS = 64;
    private final Map<String, Frame> pending = new HashMap<>();
    private final LinkedHashMap<String, Maneuver> maneuvers = new LinkedHashMap<>();
    private Route route;
    private final ArrayDeque<RetiredRoute> recentRoutes = new ArrayDeque<>();
    private long emptyListSince = -1;
    private String restartPrefix;
    private long restartWall = -1;
    private int framesSeen, framesParsed;
    private long lastNowWall = -1;

    private static final class Frame {
        String session; int kind, pid; long wall;
        final Map<Integer, String> parameters = new HashMap<>();
        String prefix() { return pid + ":" + session + ":" + number(parameters.get(0)); }
    }
    private static final class Maneuver {
        int type; String description, road; long wall;
    }
    private static final class Route {
        String prefix, currentRoad; int index, distance; long wall;
    }
    private static final class RetiredRoute {
        final Route route; final long replacedAt;
        RetiredRoute(Route route, long replacedAt) { this.route = route; this.replacedAt = replacedAt; }
    }

    /** Return true only when a complete, trusted navigation frame changed the cache. */
    public synchronized boolean accept(String line, int sourceUid, long nowWall) {
        if (lastNowWall >= 0 && nowWall < lastNowWall - 1000) clear();
        lastNowWall = nowWall;
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
            Matcher closed = CLOSE.matcher(message);
            if (closed.matches()) {
                String sessionPrefix = pid + ":" + closed.group(1) + ":";
                pending.remove(thread);
                maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(sessionPrefix) && e.getValue().wall <= wall);
                if (route != null && route.prefix.startsWith(sessionPrefix) && route.wall <= wall) { route = null; recentRoutes.clear(); emptyListSince = -1; }
                if (restartPrefix != null && restartPrefix.startsWith(sessionPrefix) && restartWall <= wall) clearRestart();
                return true;
            }
            if (message.startsWith("IAP2 RX")) {
                pending.remove(thread);
                Matcher frame = FRAME.matcher(message);
                if (frame.matches()) {
                    framesSeen++;
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
                if (frame.parameters.size() >= 80) { pending.remove(thread); return false; }
                int id = Integer.parseInt(parameter.group(1));
                if (frame.parameters.put(id, parameter.group(2)) != null) pending.remove(thread);
            } else if (message.trim().startsWith("raw-body=")) {
                pending.remove(thread);
                boolean changed = commit(frame, nowWall);
                if (changed) framesParsed++;
                return changed;
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private boolean commit(Frame frame, long nowWall) {
        Integer identifier = typedNumber(frame.parameters.get(0), 16);
        String prefix;
        if (identifier != null) prefix = frame.prefix();
        else if (!frame.parameters.containsKey(0) && route != null && route.prefix.startsWith(frame.pid + ":" + frame.session + ":")
            && frame.wall >= route.wall && frame.wall - route.wall <= ROUTE_TIMEOUT) prefix = route.prefix;
        else return false;
        if (frame.kind == 0x5202) {
            Integer index = typedNumber(frame.parameters.get(1), 16);
            if (index == null) return false;
            String key = prefix + ":" + index;
            Maneuver previous = maneuvers.get(key);
            if (previous != null && previous.wall > frame.wall) return false;
            boolean currentZero = index == 0 && route != null && route.prefix.equals(prefix) && route.index == 0
                && frame.wall >= route.wall && frame.wall - route.wall <= ROUTE_TIMEOUT;
            if (index == 0 && !currentZero) previous = null;
            Integer type = frame.parameters.containsKey(3) ? typedNumber(frame.parameters.get(3), 8) : previous == null ? null : previous.type;
            if (type == null) return false;
            // A detail is not a route-selection message. A late first-turn
            // refresh must not erase the currently selected later turn.
            if (index == 0 && !currentZero) {
                if (route != null && route.prefix.equals(prefix) && frame.wall >= route.wall && frame.wall - route.wall <= ROUTE_TIMEOUT) {
                    restartPrefix = prefix; restartWall = frame.wall;
                } else {
                    maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(prefix + ":") && e.getValue().wall <= frame.wall);
                    if (route != null && route.prefix.equals(prefix) && route.wall <= frame.wall) { route = null; recentRoutes.clear(); emptyListSince = -1; }
                }
            }
            Maneuver next = new Maneuver(); next.type = type; next.wall = frame.wall;
            next.description = frame.parameters.containsKey(2) ? quoted(frame.parameters.get(2)) : previous == null ? "" : previous.description;
            next.road = frame.parameters.containsKey(4) ? quoted(frame.parameters.get(4)) : previous == null ? "" : previous.road;
            maneuvers.put(key, next);
            trimManeuvers(prefix, frame.wall);
            return true;
        }
        Integer state = typedNumber(frame.parameters.get(1), 8);
        if (frame.parameters.containsKey(1) && state == null) return false;
        if (route != null && route.wall > frame.wall) return false;
        if (state != null && (state == 0 || state == 2)) {
            if (route != null && route.prefix.equals(prefix)) { route = null; recentRoutes.clear(); emptyListSince = -1; }
            if (prefix.equals(restartPrefix)) clearRestart();
            maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(prefix + ":") && e.getValue().wall <= frame.wall);
            return true;
        }
        if (nowWall - frame.wall > ROUTE_TIMEOUT) return false;
        Route previous = route != null && route.prefix.equals(prefix) && frame.wall - route.wall <= ROUTE_TIMEOUT ? route : null;
        // The source keeps guidance for nonterminal states, including 3 and 5.
        if (state == null && previous == null) return false;
        if (frame.parameters.containsKey(13) && emptyList(frame.parameters.get(13))) {
            // The source retains the last instruction for a brief empty list;
            // it need not resend cached details when a populated list resumes.
            if (previous != null) {
                if (emptyListSince < 0) emptyListSince = frame.wall;
                // Empty-list keepalives are still fresh route context. Keep
                // the deadline fixed while allowing a later state-less resume.
                route.wall = frame.wall;
                if (frame.parameters.containsKey(3)) route.currentRoad = quoted(frame.parameters.get(3));
            } else { route = null; recentRoutes.clear(); emptyListSince = -1; }
            return true;
        }
        Integer index = frame.parameters.containsKey(13) ? firstIndex(frame.parameters.get(13)) : previous == null ? null : previous.index;
        Integer distance = frame.parameters.containsKey(10) ? typedNumber(frame.parameters.get(10), 32) : previous == null ? null : previous.distance;
        if (index == null || distance == null) { route = null; recentRoutes.clear(); emptyListSince = -1; return true; }
        boolean restarted = previous != null && previous.index != 0 && index == 0;
        if (restarted) {
            // Only a route update confirms the return to the first maneuver.
            // Never mix old future details into this confirmed restart.
            final long boundary = prefix.equals(restartPrefix) && restartWall <= frame.wall && frame.wall - restartWall <= ROUTE_TIMEOUT ? restartWall : frame.wall;
            maneuvers.entrySet().removeIf(e -> e.getKey().startsWith(prefix + ":") && e.getValue().wall < boundary);
            recentRoutes.clear(); clearRestart();
        }
        if (frame.parameters.containsKey(13)) emptyListSince = -1;
        Route next = new Route(); next.prefix = prefix; next.index = index; next.distance = distance; next.wall = frame.wall;
        next.currentRoad = frame.parameters.containsKey(3) ? quoted(frame.parameters.get(3)) : previous == null ? "" : previous.currentRoad;
        if (route == null || !route.prefix.equals(next.prefix)) { recentRoutes.clear(); emptyListSince = -1; clearRestart(); }
        else if (!restarted && (route.index != next.index || route.distance != next.distance)) {
            recentRoutes.addLast(new RetiredRoute(route, frame.wall));
            while (recentRoutes.size() > 8) recentRoutes.removeFirst();
        }
        while (!recentRoutes.isEmpty() && frame.wall - recentRoutes.peekFirst().replacedAt > WIDGET_LAG_MS) recentRoutes.removeFirst();
        route = next; return true;
    }

    public synchronized String road(String widgetTurn, Integer widgetMeters, long nowWall) {
        if (route == null || widgetTurn == null || widgetMeters == null || nowWall < route.wall || nowWall - route.wall > ROUTE_TIMEOUT) return "";
        if (emptyListSince >= 0 && nowWall - emptyListSince >= EMPTY_LIST_HIDE_MS) return "";
        // Equal distance alone cannot identify an old maneuver. If the current
        // route has that distance, missing/new details must never borrow its predecessor.
        if (widgetMeters == Protocol.roundedWidgetMeters(route.distance))
            return matches(route, widgetTurn, widgetMeters, nowWall) ? roadFor(route) : "";
        java.util.Iterator<RetiredRoute> candidates = recentRoutes.descendingIterator();
        while (candidates.hasNext()) {
            RetiredRoute previous = candidates.next();
            if (nowWall >= previous.replacedAt && nowWall - previous.replacedAt <= WIDGET_LAG_MS
                && route.prefix.equals(previous.route.prefix) && matches(previous.route, widgetTurn, widgetMeters, nowWall))
                return roadFor(previous.route);
        }
        return "";
    }

    private boolean matches(Route candidate, String turn, int meters, long nowWall) {
        Maneuver maneuver = maneuvers.get(candidate.prefix + ":" + candidate.index);
        return nowWall >= candidate.wall && nowWall - candidate.wall <= ROUTE_TIMEOUT
            && maneuver != null && maneuver.wall <= nowWall && turn.equals(Protocol.turnForAppleType(maneuver.type))
            && meters == Protocol.roundedWidgetMeters(candidate.distance);
    }

    private String roadFor(Route candidate) {
        Maneuver maneuver = maneuvers.get(candidate.prefix + ":" + candidate.index);
        String road = Protocol.roadName(maneuver.road);
        if (road.isEmpty()) road = Protocol.roadName(candidate.currentRoad);
        return road.isEmpty() ? Protocol.roadName(maneuver.description) : road;
    }

    private void clearRestart() { restartPrefix = null; restartWall = -1; }

    /** Keep a hard bound without letting distant prefetch evict the visible/next instruction. */
    private void trimManeuvers(String incomingPrefix, long nowWall) {
        while (maneuvers.size() > MAX_MANEUVERS) {
            String victim = null; long worst = Long.MIN_VALUE;
            for (Map.Entry<String, Maneuver> entry : maneuvers.entrySet()) {
                String key = entry.getKey();
                if (route != null && key.equals(route.prefix + ":" + route.index)) continue;
                boolean protectedRecent = false;
                for (RetiredRoute recent : recentRoutes) {
                    if (nowWall - recent.replacedAt <= WIDGET_LAG_MS && key.equals(recent.route.prefix + ":" + recent.route.index)) { protectedRecent = true; break; }
                }
                if (protectedRecent) continue;
                int split = key.lastIndexOf(':');
                String prefix = split < 0 ? "" : key.substring(0, split);
                int index;
                try { index = Integer.parseInt(key.substring(split + 1)); }
                catch (NumberFormatException e) { index = Integer.MAX_VALUE; }
                boolean active = route != null && prefix.equals(route.prefix);
                long score;
                if (!active && !prefix.equals(incomingPrefix)) score = 3_000_000L;
                else if (active && index < route.index) score = 2_000_000L + route.index - index;
                else score = active ? (long) index - route.index : index;
                if (victim == null || score > worst) { victim = key; worst = score; }
            }
            // At most nine entries can be protected by current/recent snapshots.
            if (victim == null) break;
            maneuvers.remove(victim);
        }
    }

    private static boolean emptyList(String value) {
        return "void".equals(value) || "[]".equals(value) || "bytes=0 [<empty>]".equals(value);
    }

    public synchronized void clear() { pending.clear(); maneuvers.clear(); recentRoutes.clear(); route = null; emptyListSince = -1; clearRestart(); lastNowWall = -1; }
    public synchronized int framesSeen() { return framesSeen; }
    public synchronized int framesParsed() { return framesParsed; }

    /** Parameter 13 is a u16 list; the widget uses its first maneuver, even in an opaque trace. */
    private static Integer firstIndex(String value) {
        Integer single = typedNumber(value, 16);
        if (single != null || value == null || value.length() > 512) return single;
        if (value.matches("\\[0x[0-9a-fA-F]{4}(?:,\\s*0x[0-9a-fA-F]{4}){0,63}\\]"))
            return Integer.parseInt(value.substring(3, 7), 16);
        Matcher scalar = Pattern.compile("u(32|64)=\\d+ \\(0x([0-9a-fA-F ]+)\\)").matcher(value);
        Matcher bytes = Pattern.compile("bytes=(\\d+) \\[([0-9a-fA-F ]+)\\]").matcher(value);
        int size; String hex;
        if (scalar.matches()) { size = Integer.parseInt(scalar.group(1)) / 8; hex = scalar.group(2); }
        else if (bytes.matches()) { size = Integer.parseInt(bytes.group(1)); hex = bytes.group(2); }
        else return null;
        if (size < 2 || size > 24 || size % 2 != 0) return null;
        String[] parts = hex.trim().split(" +");
        if (parts.length != size) return null;
        for (String part : parts) if (!part.matches("[0-9a-fA-F]{2}")) return null;
        return Integer.parseInt(parts[0] + parts[1], 16);
    }

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
                else if (escaped == 'n' || escaped == 'r' || escaped == 't') c = ' ';
                else return "";
            }
            result.append(c);
        }
        return result.toString();
    }

    private static Integer typedNumber(String value, int bits) {
        // The source formats opaque one-byte NUL as an empty quoted string.
        // This means numeric zero only in a field whose wire type is u8.
        if (bits == 8 && "\"\"".equals(value)) return 0;
        if (value == null || !value.startsWith("u" + bits + "=")) return null;
        Integer result = number(value);
        if (result == null || (bits < 32 && result >= (1 << bits))) return null;
        return result;
    }
}
