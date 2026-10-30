package com.local.diplay.clusterbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure routing rules for the explicitly selected bridge media-session entry. */
public final class MediaKeyPolicy {
    public static final int NEXT = 87, PREVIOUS = 88;
    public static final long MAX_AGE = 1500;
    public enum Action { NEXT, PREVIOUS, ANSWER, END, IGNORE }
    public static final class Snapshot {
        public final long generation;
        public final int pid, status;
        public final String call;
        public Snapshot(long generation, int pid, int status, String call) {
            this.generation = generation; this.pid = pid; this.status = status; this.call = call;
        }
        public boolean same(Snapshot other) {
            return other != null && generation == other.generation && pid == other.pid && status == other.status && call.equals(other.call);
        }
    }
    public static Action action(int key, int status) {
        if (key != NEXT && key != PREVIOUS) return Action.IGNORE;
        if (status == 0) return key == NEXT ? Action.NEXT : Action.PREVIOUS;
        if (status == 2) return key == PREVIOUS ? Action.ANSWER : Action.END;
        if (status == 1 || status == 3 || status == 4 || status == 5) return key == NEXT ? Action.END : Action.IGNORE;
        return Action.IGNORE;
    }
    public static boolean eligible(boolean enabled, boolean connected, Snapshot captured, Snapshot current, long created, long now) {
        return enabled && captured != null && (connected || captured.status == 0)
            && (captured.pid > 0 || captured.pid == -1 && captured.status == 0) && captured.same(current)
            && created >= 0 && now >= created && now - created <= MAX_AGE;
    }
    public static String hash(String key) {
        if (key == null) return "-";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    /** Suppress a held key's repeats/releases and duplicate callbacks from the same short press. */
    public static final class PressGate {
        private long nextAt = -1, previousAt = -1;
        public boolean press(int key, long now) {
            if (key != NEXT && key != PREVIOUS) return false;
            long last = key == NEXT ? nextAt : previousAt;
            if (last >= 0 && now >= last && now - last < 220) return false;
            if (key == NEXT) nextAt = now; else previousAt = now;
            return true;
        }
        public boolean event(int key, int action, int repeat, long eventTime, long uptime, long elapsed) {
            return action == 0 && repeat == 0 && eventTime >= 0 && (eventTime == 0 || uptime >= eventTime && uptime - eventTime <= MAX_AGE) && press(key, elapsed);
        }
        public void clear() { nextAt = previousAt = -1; }
    }
}
