package com.local.diplay.clusterbridge;

/** Fixed, bounded local helper protocol; contains a digest, never caller text. */
public final class KeyControlRequest {
    public final String id;
    public final long created;
    public final int key;
    public final MediaKeyPolicy.Snapshot expected;
    public KeyControlRequest(String id, long created, int key, MediaKeyPolicy.Snapshot expected) {
        if (id == null || !id.matches("[a-f0-9]{8,40}") || created < 0 || expected == null || (expected.pid <= 0 && !(expected.pid == -1 && expected.status == 0))
            || key != MediaKeyPolicy.NEXT && key != MediaKeyPolicy.PREVIOUS || expected.status < 0 || expected.status > 5
            || !(expected.status == 0 ? "-".equals(expected.call) : expected.call.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid key request");
        this.id = id; this.created = created; this.key = key; this.expected = expected;
    }
    public String encode() { return "K\t" + id + "\t" + created + "\t" + key + "\t" + expected.pid + "\t" + expected.status + "\t" + expected.call; }
    public static KeyControlRequest parse(String line) {
        if (line == null || line.length() > 256) throw new IllegalArgumentException("Invalid key request length");
        String[] v = line.split("\t", -1);
        if (v.length != 7 || !v[0].equals("K")) throw new IllegalArgumentException("Invalid key request shape");
        return new KeyControlRequest(v[1], Long.parseLong(v[2]), Integer.parseInt(v[3]),
            new MediaKeyPolicy.Snapshot(0, Integer.parseInt(v[4]), Integer.parseInt(v[5]), v[6]));
    }
    public boolean matches(MediaKeyPolicy.Snapshot live, long resetAt, long now) {
        return live != null && live.pid == expected.pid && live.status == expected.status && live.call.equals(expected.call)
            && now >= created && now - created <= MediaKeyPolicy.MAX_AGE && created >= resetAt;
    }
}
