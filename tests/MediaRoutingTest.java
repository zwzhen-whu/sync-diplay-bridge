package com.local.diplay.clusterbridge;

/** Offline behavioral checks; no Android/device connection. */
public final class MediaRoutingTest {
    private static int checks;
    private static final Object SESSION = new Object();
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static MediaRouting.State state(int source, int playback) {
        return new MediaRouting.State(source, playback, SESSION, true, false);
    }
    private static final class Fake implements MediaRouting.Port {
        MediaRouting.State first = MediaRoutingTest.state(MediaRouting.CARPLAY, 3), second;
        MediaRouting.Target target;
        int reads, sends, audio, key;
        boolean current = true, accepted = true, throwOnSend;
        public MediaRouting.State state() { return ++reads == 1 || second == null ? first : second; }
        public boolean current() { return current; }
        public int audioMode() { return audio; }
        public boolean send(MediaRouting.Target target, MediaRouting.State captured, int key) throws Exception {
            sends++; this.target = target; this.key = key;
            if (throwOnSend) throw new Exception("synthetic uncertain dispatch");
            return accepted;
        }
    }
    private static void blocked(Fake port, KeyControlEngine.FaultCode expected, String why) throws Exception {
        boolean failed = false;
        try { MediaRouting.execute(87, port); }
        catch (KeyControlEngine.Fault fault) { failed = fault.code == expected; }
        check(failed && port.sends == 0, why);
    }
    public static void main(String[] args) throws Exception {
        for (int source = MediaRouting.UNKNOWN; source <= MediaRouting.CARPLAY; source++) {
            for (int playback = 0; playback <= 11; playback++) {
                MediaRouting.Target expected = (source == MediaRouting.UNKNOWN || source == MediaRouting.CARPLAY) && playback == 3
                    ? MediaRouting.Target.DIPLAY : MediaRouting.Target.CARRADIO;
                check(state(source, playback).target() == expected, "source ownership plus actual playback table");
            }
        }
        check(state(MediaRouting.CARPLAY, 2).target() == MediaRouting.Target.CARRADIO, "selected CarPlay but paused delegates to CarRadio");
        check(state(MediaRouting.ONLINE, 3).target() == MediaRouting.Target.CARRADIO, "selected native source overrides stale playing DiPlay session");
        check(new MediaRouting.State(4, 3, null, true, false).target() == MediaRouting.Target.CARRADIO, "no DiPlay session is not active playback");
        check(new MediaRouting.State(-1, 0, null, true, false).target() == MediaRouting.Target.CARRADIO, "disconnected phone can still control native source");
        check(new MediaRouting.State(4, 3, SESSION, false, false).target() == MediaRouting.Target.DIPLAY, "CarPlay needs no CarRadio dependency");
        check(new MediaRouting.State(0, 3, SESSION, false, false).target() == MediaRouting.Target.NONE, "missing CarRadio never redirects native keys to DiPlay");
        check(new MediaRouting.State(4, 3, SESSION, true, true).target() == MediaRouting.Target.NONE, "ambiguous DiPlay sessions do not guess a player");
        check(new MediaRouting.State(0, 3, SESSION, true, true).target() == MediaRouting.Target.CARRADIO, "known native owner does not depend on DiPlay session uniqueness");
        for (int key : new int[]{87, 88}) for (int source : new int[]{0, 1, 2, 3, 4, -1}) for (int playback : new int[]{2, 3}) {
            Fake f = new Fake(); f.first = state(source, playback);
            MediaRouting.Target expected = f.first.target();
            check(MediaRouting.execute(key, f) == expected && f.target == expected && f.sends == 1 && f.key == key && f.reads == 2,
                "one targeted dispatch after two consistent source reads");
        }
        Fake f = new Fake(); f.second = state(0, 3);
        blocked(f, KeyControlEngine.FaultCode.STATE_CHANGED, "source changed while querying cancels instead of rerouting queued key");
        f = new Fake(); f.second = state(4, 2);
        blocked(f, KeyControlEngine.FaultCode.STATE_CHANGED, "CarPlay paused before dispatch cancels key");
        f = new Fake(); f.first = state(0, 2); f.second = state(1, 2);
        blocked(f, KeyControlEngine.FaultCode.STATE_CHANGED, "native source changes cancel even when both belong to CarRadio");
        f = new Fake(); f.second = new MediaRouting.State(4, 3, new Object(), true, false);
        blocked(f, KeyControlEngine.FaultCode.STATE_CHANGED, "new DiPlay session cannot inherit previous target");
        f = new Fake(); f.current = false;
        blocked(f, KeyControlEngine.FaultCode.STATE_CHANGED, "call, feature toggle or request deadline changed before dispatch");
        for (int audio : new int[]{1, 2, 3}) for (int source : new int[]{0, 4}) {
            f = new Fake(); f.audio = audio; f.first = state(source, 3);
            blocked(f, KeyControlEngine.FaultCode.AUDIO_BUSY, "voice or phone audio blocks both owners");
        }
        f = new Fake(); f.accepted = false;
        check(MediaRouting.execute(87, f) == MediaRouting.Target.NONE && f.sends == 1 && f.target == MediaRouting.Target.DIPLAY,
            "DiPlay rejecting a key never causes fallback to CarRadio");
        f = new Fake(); f.first = state(0, 3); f.accepted = false;
        check(MediaRouting.execute(88, f) == MediaRouting.Target.NONE && f.sends == 1 && f.target == MediaRouting.Target.CARRADIO,
            "CarRadio rejecting a key never causes fallback to DiPlay");
        f = new Fake(); f.throwOnSend = true; boolean threw = false;
        try { MediaRouting.execute(87, f); } catch (Exception expected) { threw = true; }
        check(threw && f.sends == 1, "uncertain dispatch never retries another target");
        f = new Fake(); boolean rejected = false;
        try { MediaRouting.execute(85, f); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && f.reads == 0 && f.sends == 0, "only previous and next keys enter routing");
        check(state(0, 2).same(state(0, 2)), "equivalent native state compares equal");
        check(!state(0, 2).same(null), "missing state invalidates dispatch");
        MediaKeyPolicy.Snapshot idle = new MediaKeyPolicy.Snapshot(1, -1, 0, "-");
        check(MediaKeyPolicy.eligible(true, false, idle, idle, 1000, 1000), "idle bridge entry stays usable without CarPlay connection");
        check(!MediaKeyPolicy.eligible(false, false, idle, idle, 1000, 1000), "disabled media entry stays disabled");
        check(!MediaKeyPolicy.eligible(true, false, idle, idle, 1000, 2501), "disconnected routing retains key expiration");
        MediaKeyPolicy.Snapshot call = new MediaKeyPolicy.Snapshot(1, 100, 2, MediaKeyPolicy.hash("synthetic-call"));
        check(!MediaKeyPolicy.eligible(true, false, call, call, 1000, 1000), "phone actions still require connected CarPlay");
        for (int source : new int[]{-2, 5, 100}) {
            rejected = false;
            try { state(source, 3); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "unknown native_source code is not mistaken for PLAY_INFO source_type");
        }
        String id="01234567-0123-0123-0123-0123456789ab";
        String reply="Broadcasting: Intent { act=com.baidu.car.radio.MEDIA_REQUEST }\nBroadcast completed: result=2, data=\"CAR_MEDIA_V2:"+id+":4:3:1100\"\n";
        RadioStateReply parsed=RadioStateReply.parse(reply,id,1000,1200);
        check(parsed!=null&&parsed.source==4&&parsed.playback==3,"public API response is bound to request and monotonic clock");
        check(RadioStateReply.parse(reply,id,1101,1200)==null,"reply earlier than query cannot be reused");
        check(RadioStateReply.parse(reply,id,1000,1099)==null,"future reply is rejected");
        check(RadioStateReply.parse(reply,id,1000,2501)==null,"expired query cannot dispatch a key");
        check(RadioStateReply.parse(reply,id.replace('a','b'),1000,1200)==null,"different request cannot inherit source result");
        check(RadioStateReply.parse(reply+reply,id,1000,1200)==null,"duplicate responses are ambiguous");
        check(RadioStateReply.parse(reply.replace(":4:3:",":9:3:"),id,1000,1200)==null,"unrecognized native source rejected");
        check(RadioStateReply.parse(reply.replace(":4:3:",":4:99:"),id,1000,1200)==null,"unrecognized playback state rejected");
        check(RadioStateReply.parse("Broadcast completed: result=0",id,1000,1200)==null,"old radio without API2 is distinguishable");
        System.out.println("MediaRoutingTest: " + checks + " checks passed (offline; physical source control unverified)");
    }
}
