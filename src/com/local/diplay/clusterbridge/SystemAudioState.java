package com.local.diplay.clusterbridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse only the verified SYNC+ diagnostic format; unfamiliar or incomplete dumps fail closed. */
public final class SystemAudioState {
    public enum Focus { UNKNOWN, STANDBY, SOURCE, OTHER, EMPTY }
    public final Focus focus;
    public final int sourcePid;
    public final boolean sourcePlaying, sourceRequested, safeCallState, musicUnmuted;
    private SystemAudioState(Focus focus, int pid, boolean playing, boolean requested, boolean call, boolean unmuted) {
        this.focus = focus; sourcePid = pid; sourcePlaying = playing; sourceRequested = requested;
        safeCallState = call; musicUnmuted = unmuted;
    }
    public static SystemAudioState unknown() { return new SystemAudioState(Focus.UNKNOWN, -1, false, false, false, false); }
    public boolean missingSourceFocus() {
        return focus == Focus.EMPTY && sourcePlaying && sourceRequested && safeCallState && musicUnmuted;
    }
    public static SystemAudioState parse(String dump, int uid) {
        if (dump == null || !dump.contains("MediaFocusControl dump time:")) return unknown();
        int start = dump.indexOf("Audio Focus of Main stack entries"), end = dump.indexOf("No external focus policy", start);
        int resources = dump.indexOf("Audio Resource array list:"), history = dump.indexOf("Audio event log: focus commands", resources);
        if (start < 0 || end < start || resources < end || history < resources
            || !dump.substring(start, end).contains("Audio Focus of Navi stack entries")) return unknown();
        String stacks = dump.substring(start, end), resourceList = dump.substring(resources + "Audio Resource array list:".length(), history);
        Focus focus = Focus.EMPTY;
        if (stacks.contains("USAGE_SYSTEM_STANDBY") || stacks.matches("(?s).*client: \\S+@999(?:\\s|$).*")) focus = Focus.STANDBY;
        else {
            Matcher owners = Pattern.compile("\\bpack: (\\S+)").matcher(stacks); boolean any = false;
            while (owners.find()) {
                any = true;
                if (!"com.shihab.diplay".equals(owners.group(1))) { focus = Focus.OTHER; break; }
                focus = Focus.SOURCE;
            }
            if (focus == Focus.SOURCE) {
                String main = stacks.substring(0, stacks.indexOf("Audio Focus of Navi stack entries"));
                if (!Pattern.compile("(?m)^.*pack: com\\.shihab\\.diplay -- .*usage=USAGE_MEDIA\\b.*$").matcher(main).find()) focus = Focus.OTHER;
            }
            if (!any && stacks.contains("source:")) focus = Focus.UNKNOWN;
            if (focus == Focus.EMPTY && !resourceList.trim().isEmpty()) focus = Focus.OTHER;
        }
        int pid = -1;
        Matcher players = Pattern.compile("(?m)^\\s*ID:\\d+ -- type:android\\.media\\.AudioTrack -- u/pid:(\\d+)/(\\d+) -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA\\b.*$").matcher(dump);
        while (players.find()) {
            try {
                if (Integer.parseInt(players.group(1)) != uid) continue;
                int found = Integer.parseInt(players.group(2));
                if (pid >= 0 && pid != found) return unknown();
                pid = found;
            } catch (NumberFormatException e) { return unknown(); }
        }
        int volumes = dump.indexOf("Stream volumes", history);
        if (volumes < 0) return unknown();
        boolean requested = pid > 0 && Pattern.compile("requestAudioFocus\\(\\) from uid/pid " + uid + "/" + pid
            + " .*callingPack=com\\.shihab\\.diplay req=1 useage=USAGE_MEDIA(?: |$)").matcher(dump.substring(history, volumes)).find();
        int music = dump.indexOf("- STREAM_MUSIC:"), musicEnd = dump.indexOf("\n- STREAM_", music + 1);
        boolean unmuted = music >= 0 && musicEnd > music && dump.substring(music, musicEnd).contains("Muted: false");
        return new SystemAudioState(focus, pid, pid > 0, requested, dump.contains("In ring or call: false"), unmuted);
    }
    public static boolean mediaReceiverIsSource(String dump) {
        return dump != null && Pattern.compile("(?m)^\\s*Media button session is com\\.shihab\\.diplay/DiPlay CarPlay \\(userId=0\\)\\s*$").matcher(dump).find();
    }
}
