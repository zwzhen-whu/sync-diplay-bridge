package com.local.diplay.clusterbridge;

/** Send the first valid song once, then only real title/play-state changes. */
public final class MusicDelivery {
    public enum Action { NONE, SEND }
    private String lastIdentity;

    public Action next(Protocol.Song song) {
        return song != null && !identity(song).equals(lastIdentity) ? Action.SEND : Action.NONE;
    }

    public void sent(Protocol.Song song) { lastIdentity = identity(song); }
    public void reset() { lastIdentity = null; }
    private static String identity(Protocol.Song song) { return song.text + "\u0001" + song.playing; }
}
