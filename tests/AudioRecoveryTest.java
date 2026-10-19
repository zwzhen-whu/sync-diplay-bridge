package com.local.diplay.clusterbridge;

public final class AudioRecoveryTest {
    private static final long WALL = 1792336860000L;
    private static void eq(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    private static void line(AudioRecoveryPolicy p, long event, long observed, int uid, int pid, String tag, String text) {
        long wall = WALL + event;
        String log = String.format(java.util.Locale.ROOT, "%d.%03d %d %d 1 I %s: %s", wall / 1000, wall % 1000, uid, pid, tag, text);
        p.accept(log, 10089, WALL + observed, observed);
    }
    private static AudioRecoveryPolicy failed() {
        AudioRecoveryPolicy p = new AudioRecoveryPolicy();
        line(p, 35000, 43000, 10089, 3379, "DiPlay-MediaKeys", "media keys active focusDisabled=false focusGranted=false");
        line(p, 110000, 110001, 1000, 2653, "MediaFocusControl", "abandonAudioFocus() from uid/pid 1000/2653 clientId=android.media.AudioManager@72a7614@999");
        return p;
    }
    private static AudioRecoveryPolicy.Action next(AudioRecoveryPolicy p, long now, boolean playing) {
        return p.next(now, true, playing, true, true, true, false);
    }
    public static void main(String[] args) {
        AudioRecoveryPolicy p = failed();
        eq(next(p, 111000, true), AudioRecoveryPolicy.Action.NONE);
        eq(next(p, 112000, true), AudioRecoveryPolicy.Action.PAUSE);
        p.paused(112000);
        eq(next(p, 112600, true), AudioRecoveryPolicy.Action.NONE);
        eq(next(p, 112600, false), AudioRecoveryPolicy.Action.PLAY);
        p.resumed(112600);
        eq(next(p, 113000, false), AudioRecoveryPolicy.Action.NONE);
        line(p, 113000, 113001, 1000, 2653, "MediaFocusControl", "requestAudioFocus() from uid/pid 10089/3379 clientId=media callingPack=com.shihab.diplay req=1 useage=USAGE_MEDIA flags=0x0");
        eq(p.cancelled(), false);
        line(p, 113100, 113101, 10089, 3379, "DiPlay-MediaKeys", "audio focus regained=true");
        eq(p.recovered(), true);
        eq(next(p, 114000, true), AudioRecoveryPolicy.Action.NONE);
        eq(p.attempted(), true);

        // The second real boot retained only the matching system request + retry-failed reply.
        p = new AudioRecoveryPolicy();
        line(p, 39700, 46000, 1000, 2663, "MediaFocusControl", "requestAudioFocus() from uid/pid 10089/3518 clientId=media callingPack=com.shihab.diplay req=1 useage=USAGE_MEDIA flags=0x0 sdk=37");
        line(p, 40100, 46000, 10089, 3518, "DiPlay-MediaKeys", "audio focus regained=false");
        line(p, 103900, 103901, 1000, 2663, "MediaFocusControl", "abandonAudioFocus() from uid/pid 1000/2663 clientId=android.media.AudioManager@a67efa2@999");
        eq(next(p, 105900, true), AudioRecoveryPolicy.Action.NONE);
        eq(next(p, 106900, true), AudioRecoveryPolicy.Action.PAUSE);
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            p = new AudioRecoveryPolicy();
            line(p, 39700, 46000, 1000, 2663, "MediaFocusControl", "requestAudioFocus() from uid/pid 10089/3518 clientId=media callingPack=com.shihab.diplay req=1 useage=USAGE_MEDIA flags=0x0 sdk=37");
            line(p, mismatch == 2 ? 43000 : 40100, 46000, mismatch == 0 ? 10078 : 10089,
                mismatch == 1 ? 5000 : 3518, "DiPlay-MediaKeys", "audio focus regained=false");
            line(p, 103900, 103901, 1000, 2663, "MediaFocusControl", "abandonAudioFocus() from uid/pid 1000/2663 clientId=android.media.AudioManager@a67efa2@999");
            eq(next(p, 105900, true), AudioRecoveryPolicy.Action.NONE);
            eq(next(p, 106900, true), AudioRecoveryPolicy.Action.NONE);
        }

        // Success without any bridge command cancels the proposed cycle.
        p = failed();
        line(p, 111000, 111001, 10089, 3379, "DiPlay-MediaKeys", "audio focus change=1");
        eq(p.recovered(), true); eq(next(p, 115000, true), AudioRecoveryPolicy.Action.NONE);
        p = failed();
        line(p, 111000, 111001, 10089, 5000, "DiPlay-MediaKeys", "media keys active focusDisabled=false focusGranted=true");
        eq(p.recovered(), true); eq(next(p, 115000, true), AudioRecoveryPolicy.Action.NONE);
        p = new AudioRecoveryPolicy();
        line(p, 35000, 35001, 10089, 3379, "DiPlay-MediaKeys", "media keys active focusDisabled=false focusGranted=true");
        eq(next(p, 112000, true), AudioRecoveryPolicy.Action.NONE);

        p = failed();
        line(p, 111000, 111001, 1000, 2653, "MediaFocusControl", "requestAudioFocus() from uid/pid 10078/6000 clientId=qq callingPack=com.tencent.qqmusiccar req=1 useage=USAGE_MEDIA");
        eq(p.cancelled(), true); eq(next(p, 115000, true), AudioRecoveryPolicy.Action.NONE);
        p = failed();
        line(p, 111000, 111001, 1000, 2653, "MediaFocusControl", "requestAudioFocus() from uid/pid 10089/3379 clientId=call callingPack=com.shihab.diplay req=2 useage=USAGE_VOICE_COMMUNICATION flags=0x0");
        eq(p.cancelled(), true); eq(next(p, 115000, true), AudioRecoveryPolicy.Action.NONE);

        // The exact UID, startup message, standby-release marker and freshness are required.
        for (int wrong : new int[]{0, 1, 2, 3, 4}) {
            p = new AudioRecoveryPolicy();
            line(p, 35000, wrong == 4 ? 130001 : 43000, wrong == 0 ? 10078 : 10089, 3379,
                "DiPlay-MediaKeys", wrong == 1 ? "media keys active focusDisabled=true focusGranted=false" : "media keys active focusDisabled=false focusGranted=false");
            line(p, 110000, 110001, wrong == 2 ? 10078 : 1000, 2653, "MediaFocusControl",
                "abandonAudioFocus() from uid/pid 1000/2653 clientId=android.media.AudioManager@72a7614@" + (wrong == 3 ? "9990" : "999"));
            eq(next(p, 112000, true), AudioRecoveryPolicy.Action.NONE);
            eq(next(p, 113000, true), AudioRecoveryPolicy.Action.NONE);
        }
        p = failed();
        eq(p.next(112000, true, true, true, true, true, true), AudioRecoveryPolicy.Action.NONE);
        eq(p.next(113000, true, true, true, true, true, true), AudioRecoveryPolicy.Action.NONE);
        eq(next(p, AudioRecoveryPolicy.BOOT_WINDOW_MS + 1, true), AudioRecoveryPolicy.Action.NONE);

        // Calls, other foreground pages, stale widgets and disconnects cannot trigger PAUSE.
        for (int condition = 0; condition < 4; condition++) {
            p = failed();
            eq(p.next(112000, condition != 0, true, condition != 1, condition != 2, condition != 3, false), AudioRecoveryPolicy.Action.NONE);
            eq(p.next(113000, condition != 0, true, condition != 1, condition != 2, condition != 3, false), AudioRecoveryPolicy.Action.NONE);
        }
        p = failed(); next(p, 111000, true); next(p, 112000, true); p.paused(112000);
        eq(p.next(113000, true, false, false, true, true, true), AudioRecoveryPolicy.Action.NONE);
        eq(p.cancelled(), true);
        p = failed(); next(p, 111000, true); next(p, 112000, true); p.paused(112000);
        eq(next(p, 116001, false), AudioRecoveryPolicy.Action.NONE); eq(p.cancelled(), true);
        p = new AudioRecoveryPolicy(); p.confirmedMissingFocus(3462, 185000);
        eq(next(p, 185000, true), AudioRecoveryPolicy.Action.NONE);
        eq(next(p, 187000, true), AudioRecoveryPolicy.Action.PAUSE); p.paused(187000);
        eq(p.next(188000, true, false, true, true, false, true), AudioRecoveryPolicy.Action.NONE);
        eq(p.cancelled(), false);
        eq(p.next(189000, true, false, true, true, true, true), AudioRecoveryPolicy.Action.PLAY);
        System.out.println("Startup audio recovery tests passed (captured failure/release sequence, one cycle, trusted fresh evidence, success/call/app/disconnect guards)");
    }
}
