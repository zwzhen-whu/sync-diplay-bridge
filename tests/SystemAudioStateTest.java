package com.local.diplay.clusterbridge;

public final class SystemAudioStateTest {
    private static void eq(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    private static String dump(String main, String navi, String resources, String player) {
        return "MediaFocusControl dump time: 00:01\nAudio Focus of Main stack entries (last is top of Main stack):\n" + main
            + "\nAudio Focus of Navi stack entries (last is top of Navi stack):\n" + navi
            + "\nNo external focus policy\nIn ring or call: false\nAudio Resource array list:\n" + resources
            + "\nAudio event log: focus commands as seen by MediaFocusControl\n"
            + "10-18 23:58:30 requestAudioFocus() from uid/pid 10089/3462 clientId=media callingPack=com.shihab.diplay req=1 useage=USAGE_MEDIA flags=0x0 sdk=37\n"
            + "Stream volumes (device: index)\n- STREAM_MUSIC:\n Muted: false\n- STREAM_ALARM:\n Muted: false\n"
            + "PlaybackActivityMonitor dump time: 00:01\n players:\n" + player + "\n";
    }
    public static void main(String[] args) {
        String player = " ID:79 -- type:android.media.AudioTrack -- u/pid:10089/3462 -- state:started -- attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x200";
        String empty = dump("", "", "", player);
        SystemAudioState state = SystemAudioState.parse(empty, 10089);
        eq(state.focus, SystemAudioState.Focus.EMPTY); eq(state.sourcePid, 3462); eq(state.missingSourceFocus(), true);
        String owner = "source:android.os.BinderProxy@abc -- pack: com.shihab.diplay -- client: media -- gain: GAIN -- uid:10089 -- attr: AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC";
        state = SystemAudioState.parse(dump(owner, "", "[source:AUDIO_SOURCE_APIM -- status:GRANTED]", player), 10089);
        eq(state.focus, SystemAudioState.Focus.SOURCE); eq(state.missingSourceFocus(), false);
        state = SystemAudioState.parse(dump(owner.replace("USAGE_MEDIA", "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"), "", "", player), 10089);
        eq(state.focus, SystemAudioState.Focus.OTHER);
        state = SystemAudioState.parse(dump("", owner, "", player), 10089); eq(state.focus, SystemAudioState.Focus.OTHER);
        state = SystemAudioState.parse(dump(owner.replace("com.shihab.diplay", "com.tencent.qqmusiccar"), "", "", player), 10089);
        eq(state.focus, SystemAudioState.Focus.OTHER); eq(state.missingSourceFocus(), false);
        state = SystemAudioState.parse(dump("source:binder -- pack: android -- client: android.media.AudioManager@abc@999 -- attr: usage=USAGE_SYSTEM_STANDBY", "", "", player), 10089);
        eq(state.focus, SystemAudioState.Focus.STANDBY); eq(state.missingSourceFocus(), false);
        state = SystemAudioState.parse(dump("", "", "[source:AUDIO_SOURCE_RADIO -- status:GRANTED]", player), 10089);
        eq(state.focus, SystemAudioState.Focus.OTHER);
        for (String invalid : new String[]{empty.replace("state:started", "state:paused"), empty.replace("u/pid:10089/3462", "u/pid:10078/3462"),
            empty.replace("from uid/pid 10089/3462", "from uid/pid 10089/5000"), empty.replace("In ring or call: false", "In ring or call: true"),
            empty.replace("- STREAM_MUSIC:\n Muted: false", "- STREAM_MUSIC:\n Muted: true"), empty.replace("No external focus policy", "incomplete"), "Permission Denial: missing DUMP"}) {
            eq(SystemAudioState.parse(invalid, 10089).missingSourceFocus(), false);
        }
        eq(SystemAudioState.mediaReceiverIsSource("Media button session is com.shihab.diplay/DiPlay CarPlay (userId=0)\n"), true);
        eq(SystemAudioState.mediaReceiverIsSource("Media button session is com.tencent.qqmusiccar/QQMusicCar (userId=0)\n"), false);
        System.out.println("System audio state tests passed (real SYNC+ fields, standby/empty/granted distinction, active UID/PID/history, calls/mute, media receiver)");
    }
}
