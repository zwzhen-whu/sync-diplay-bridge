package com.local.diplay.clusterbridge;

/** Optional display transform for music apps that put lyrics in the Now Playing title. */
public final class MusicTextPolicy {
    public static final String LYRICS_ONLY = "music_lyrics_only";
    private MusicTextPolicy() { }
    public static Protocol.Song display(Protocol.Song source, boolean lyricsOnly) {
        if (source == null || !lyricsOnly) return source;
        String text = source.text;
        // DiPlay 0.2.21 joins Title and Artist with " — ". Prefer that delimiter
        // so an ordinary hyphen inside the lyric or track name remains intact.
        for (String separator : new String[]{" — ", " – ", " - "}) {
            int split = text.lastIndexOf(separator);
            if (split < 0) continue;
            String lyric = text.substring(0, split).trim();
            String suffix = text.substring(split + separator.length()).trim();
            if (lyric.isEmpty() || suffix.isEmpty()) return source;
            return new Protocol.Song(lyric, source.playing);
        }
        return source;
    }
}
