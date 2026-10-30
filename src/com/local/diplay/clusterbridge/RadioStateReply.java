package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed ordered-broadcast result from CarRadio API 2, bound to one query and the monotonic clock. */
public final class RadioStateReply {
    private static final Pattern RECORD = Pattern.compile("Broadcast completed: result=2, data=\"CAR_MEDIA_V2:([a-f0-9-]{36}):([0-4]):([0-9]{1,2}):([0-9]{1,16})\"");
    public final int source, playback;
    private RadioStateReply(int source, int playback) { this.source = source; this.playback = playback; }
    public static RadioStateReply parse(String output, String request, long created, long now) {
        if (output == null || output.length() > 8192 || created < 0 || now < created || now - created > MediaKeyPolicy.MAX_AGE) return null;
        RadioStateReply result = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher m = RECORD.matcher(line.trim());
            if (!m.matches()) continue;
            if (result != null || !m.group(1).equals(request)) return null;
            long at = Long.parseLong(m.group(4)); int state = Integer.parseInt(m.group(3));
            if (at < created || at > now || state > 11) return null;
            result = new RadioStateReply(Integer.parseInt(m.group(2)), state);
        }
        return result;
    }
}
