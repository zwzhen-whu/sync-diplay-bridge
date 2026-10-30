package com.local.diplay.clusterbridge;

public final class MediaKeyControlTest {
    private static int checks, sequence;
    private static final String CALL = MediaKeyPolicy.hash("synthetic-session:synthetic-call");
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static MediaKeyPolicy.Snapshot snapshot(int status) { return new MediaKeyPolicy.Snapshot(1, 3497, status, status == 0 ? "-" : CALL); }
    private static KeyControlRequest request(int key, int status) { return new KeyControlRequest(String.format(java.util.Locale.ROOT, "%08x", ++sequence), 1000, key, snapshot(status)); }
    private static final class Fake implements KeyControlEngine.Port {
        long now = 1000, reset;
        boolean enabled = true, alive = true, controls = true, ringing, hfp, foreground = true, mediaAccepted = true, receipt = true;
        boolean changeOnOpen, disableOnOpen, hfpOnOpen, ringOnOpen, expireOnOpen;
        int audio, mediaKeys, answer, end, open;
        KeyControlEngine.FaultCode fault;
        String sourceVersion = "0.2.19";
        MediaKeyPolicy.Snapshot state = MediaKeyControlTest.snapshot(0);
        public long elapsed() { return now; }
        public long resetAt() { return reset; }
        public boolean enabled() { return enabled; }
        public MediaKeyPolicy.Snapshot snapshot() { return state; }
        public boolean sourceAlive(int pid) { return alive; }
        public int audioMode() { return audio; }
        public boolean callControlsEnabled() throws KeyControlEngine.Fault {
            if (!DiPlayCallControl.supportedVersion(sourceVersion))
                throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.DIPLAY_VERSION_UNSUPPORTED);
            return controls;
        }
        public boolean openCarPlay() {
            open++; if (changeOnOpen) state = MediaKeyControlTest.snapshot(4); if (disableOnOpen) enabled = false;
            if (expireOnOpen) now += 1501; if (hfpOnOpen) hfp = true; if (ringOnOpen) ringing = true;
            return foreground;
        }
        MediaRouting.Target mediaTarget = MediaRouting.Target.DIPLAY;
        public MediaRouting.Target media(KeyControlRequest request) { int key = request.key; check(key == 87 || key == 88, "only next/previous forwarded"); mediaKeys++; return mediaAccepted ? mediaTarget : MediaRouting.Target.NONE; }
        public boolean call(KeyControlRequest request, MediaKeyPolicy.Action action) throws Exception {
            if (fault != null) throw new KeyControlEngine.Fault(fault);
            if (action == MediaKeyPolicy.Action.ANSWER) answer++; else if (action == MediaKeyPolicy.Action.END) end++;
            else throw new AssertionError("unsupported phone action"); return receipt;
        }
        int effects() { return mediaKeys + answer + end; }
    }
    public static final class Android81TaskApi {
        int max = -1, flags = -1;
        public java.util.List<?> getTasks(int max, int flags) { this.max = max; this.flags = flags; return java.util.Collections.singletonList("synthetic-task"); }
    }
    private static String run(KeyControlRequest request, Fake fake) throws Exception { return new KeyControlEngine().execute(request, fake); }
    private static void blocked(KeyControlRequest request, Fake fake, String expected, String why) throws Exception {
        check(run(request, fake).equals(expected), why + " reason"); check(fake.effects() == 0, why + " has no side effects");
    }
    public static void main(String[] args) throws Exception {
        for (int key : new int[]{87, 88}) for (int status = 0; status <= 7; status++) {
            MediaKeyPolicy.Action action = MediaKeyPolicy.action(key, status);
            MediaKeyPolicy.Action expected = status == 0 ? key == 87 ? MediaKeyPolicy.Action.NEXT : MediaKeyPolicy.Action.PREVIOUS
                : status == 2 ? key == 88 ? MediaKeyPolicy.Action.ANSWER : MediaKeyPolicy.Action.END
                : status <= 5 ? key == 87 ? MediaKeyPolicy.Action.END : MediaKeyPolicy.Action.IGNORE : MediaKeyPolicy.Action.IGNORE;
            check(action == expected, "complete phase/key routing table");
        }
        check(MediaKeyPolicy.action(85, 2) == MediaKeyPolicy.Action.IGNORE && MediaKeyPolicy.action(6, 4) == MediaKeyPolicy.Action.IGNORE, "no play or unrelated external controls");
        for (int key : new int[]{87, 88}) {
            Fake f = new Fake(); check(run(request(key, 0), f).equals(key == 87 ? "RESULT NEXT" : "RESULT PREVIOUS") && f.mediaKeys == 1 && f.answer == 0 && f.end == 0, "idle sends one media action");
            f = new Fake(); f.state = snapshot(2); check(run(request(key, 2), f).equals(key == 88 ? "RESULT ANSWER" : "RESULT END"), "ringing call routes correctly");
            check(f.mediaKeys == 0 && f.effects() == 1, "ringing never skips a song");
        }
        for (int key : new int[]{87, 88}) {
            Fake radio = new Fake(); radio.mediaTarget = MediaRouting.Target.CARRADIO;
            check(run(request(key, 0), radio).equals(key == 87 ? "RESULT CARRADIO_NEXT" : "RESULT CARRADIO_PREVIOUS")
                && radio.mediaKeys == 1 && radio.answer == 0 && radio.end == 0, "native source reports correct dispatch owner");
            radio = new Fake(); radio.mediaTarget = MediaRouting.Target.CARRADIO; radio.state = snapshot(2);
            check(run(request(key, 2), radio).equals(key == 88 ? "RESULT ANSWER" : "RESULT END")
                && radio.mediaKeys == 0, "incoming call takes priority over native media ownership");
            radio = new Fake(); radio.mediaTarget = MediaRouting.Target.CARRADIO; radio.state = snapshot(4);
            check(run(request(key, 4), radio).equals(key == 87 ? "RESULT END" : "RESULT IGNORE")
                && radio.mediaKeys == 0, "active call never sends keys to CarRadio");
        }
        for (int phase : new int[]{1, 3, 4, 5}) {
            Fake f = new Fake(); f.state = snapshot(phase);
            check(run(request(87, phase), f).equals("RESULT END") && f.end == 1 && f.mediaKeys == 0, "next ends outgoing/active/held call");
            f = new Fake(); f.state = snapshot(phase);
            check(run(request(88, phase), f).equals("RESULT IGNORE") && f.effects() == 0, "previous does not skip music during call");
        }
        Fake bootstrap = new Fake(); bootstrap.state = new MediaKeyPolicy.Snapshot(1, -1, 0, "-"); bootstrap.alive = false;
        KeyControlRequest bootstrapPress = new KeyControlRequest("abcdef00", 1000, 87, bootstrap.state);
        check(KeyControlRequest.parse(bootstrapPress.encode()).expected.pid == -1, "unknown idle source wire round trip");
        check(run(bootstrapPress, bootstrap).equals("RESULT NEXT") && bootstrap.mediaKeys == 1 && bootstrap.answer == 0 && bootstrap.end == 0, "unknown source can only request media, not phone control");
        bootstrap = new Fake(); bootstrap.state = new MediaKeyPolicy.Snapshot(1, -1, 0, "-"); bootstrap.audio = 3;
        blocked(bootstrapPress, bootstrap, "ERR AUDIO_BUSY", "unknown idle bootstrap still blocks call/voice audio");
        boolean unknownCallRejected = false;
        try { new KeyControlRequest("abcdef01", 1000, 87, new MediaKeyPolicy.Snapshot(1, -1, 2, CALL)); }
        catch (IllegalArgumentException expected) { unknownCallRejected = true; }
        check(unknownCallRejected, "unknown PID never authorizes a call");
        Fake f = new Fake(); f.enabled = false; blocked(request(87, 0), f, "ERR DISABLED", "disabled feature");
        f = new Fake(); f.alive = false; blocked(request(87, 0), f, "ERR STATE_CHANGED", "source process gone");
        f = new Fake(); f.state = snapshot(2); blocked(request(87, 0), f, "ERR STATE_CHANGED", "call starts while media request queues");
        f = new Fake(); blocked(request(88, 2), f, "ERR STATE_CHANGED", "call ends before answer");
        f = new Fake(); f.state = snapshot(4); blocked(request(87, 2), f, "ERR STATE_CHANGED", "ringing becomes active before reject");
        f = new Fake(); f.state = new MediaKeyPolicy.Snapshot(1, 3497, 2, MediaKeyPolicy.hash("another-call")); blocked(request(88, 2), f, "ERR STATE_CHANGED", "new caller cannot inherit old press");
        f = new Fake(); f.state = new MediaKeyPolicy.Snapshot(1, 4000, 0, "-"); blocked(request(87, 0), f, "ERR STATE_CHANGED", "source PID changed");
        f = new Fake(); f.now = 2501; blocked(request(87, 0), f, "ERR STATE_CHANGED", "expired press");
        f = new Fake(); f.now = 999; blocked(request(87, 0), f, "ERR STATE_CHANGED", "future timestamp");
        f = new Fake(); f.reset = 1001; blocked(request(87, 0), f, "ERR STATE_CHANGED", "transport closed after press");
        for (int audio : new int[]{1, 2, 3}) { f = new Fake(); f.audio = audio; blocked(request(87, 0), f, "ERR AUDIO_BUSY", "native phone or voice audio busy"); }
        f = new Fake(); f.state = snapshot(2); f.controls = false; blocked(request(88, 2), f, "ERR DIPLAY_CONTROLS_OFF", "DiPlay opt-in disabled");
        f = new Fake(); f.state = snapshot(4); f.controls = false; blocked(request(87, 4), f, "ERR DIPLAY_CONTROLS_OFF", "hangup requires DiPlay opt-in");
        for (String version : new String[]{"0.2.19", "0.2.21"}) for (int phase : new int[]{2, 4}) {
            f = new Fake(); f.sourceVersion = version; f.state = snapshot(phase);
            check(run(request(phase == 2 ? 88 : 87, phase), f).equals(phase == 2 ? "RESULT ANSWER" : "RESULT END")
                && f.effects() == 1 && f.mediaKeys == 0, "audited version permits one call action: " + version);
            f = new Fake(); f.sourceVersion = version; f.state = snapshot(phase); f.controls = false;
            blocked(request(phase == 2 ? 88 : 87, phase), f, "ERR DIPLAY_CONTROLS_OFF", "each audited version still requires explicit opt-in");
        }
        for (String version : new String[]{null, "", "0.2.20", "0.2.22", "0.2.210", "0.2.21-beta"}) {
            f = new Fake(); f.sourceVersion = version; f.state = snapshot(2);
            blocked(request(88, 2), f, "ERR DIPLAY_VERSION_UNSUPPORTED", "unaudited version cannot answer");
            check(f.open == 0, "unsupported version is rejected before opening CarPlay");
            f = new Fake(); f.sourceVersion = version; f.state = snapshot(4);
            blocked(request(87, 4), f, "ERR DIPLAY_VERSION_UNSUPPORTED", "unaudited version cannot end a call");
            f = new Fake(); f.sourceVersion = version;
            check(run(request(87, 0), f).equals("RESULT NEXT") && f.mediaKeys == 1, "version gate remains specific to phone controls");
        }
        for (int audio : new int[]{0, 1, 2, 3}) {
            f = new Fake(); f.state = snapshot(2); f.hfp = true; f.ringing = true; f.audio = audio;
            check(run(request(88, 2), f).equals("RESULT ANSWER") && f.answer == 1 && f.mediaKeys == 0,
                "DiPlay ringing controls answer independently of Android/HFP state and audio mode");
        }
        f = new Fake(); f.audio = 0; f.hfp = true; f.ringing = true;
        check(run(request(88, 0), f).equals("RESULT PREVIOUS") && f.answer == 0,
            "Android/HFP ringing cannot invent an iPhone call");
        f = new Fake(); f.state = snapshot(2); f.foreground = false; blocked(request(88, 2), f, "ERR CARPLAY_NOT_FOREGROUND", "CarPlay could not become foreground");
        f = new Fake(); f.state = snapshot(2); f.changeOnOpen = true; blocked(request(88, 2), f, "ERR STATE_CHANGED", "call changes while opening CarPlay");
        f = new Fake(); f.state = snapshot(2); f.disableOnOpen = true; blocked(request(88, 2), f, "ERR DISABLED", "toggle turned off while opening CarPlay");
        f = new Fake(); f.state = snapshot(2); f.expireOnOpen = true; blocked(request(88, 2), f, "ERR STATE_CHANGED", "foreground operation took too long");
        f = new Fake(); f.state = snapshot(2); f.hfpOnOpen = true; f.ringOnOpen = true;
        check(run(request(88, 2), f).equals("RESULT ANSWER") && f.answer == 1, "native phone flags do not override captured DiPlay call after foregrounding");
        f = new Fake(); f.mediaAccepted = false;
        check(run(request(87, 0), f).equals("ERR NO_MEDIA") && f.mediaKeys == 1 && f.answer == 0 && f.end == 0, "failed chosen target has no retry to other player");
        f = new Fake(); f.state = snapshot(4); f.receipt = false;
        KeyControlEngine engine = new KeyControlEngine(); KeyControlRequest press = request(87, 4);
        check(engine.execute(press, f).equals("ERR UNCONFIRMED") && f.end == 1, "missing native send receipt is uncertain");
        check(engine.execute(press, f).equals("ERR DUPLICATE") && f.end == 1, "uncertain hangup is never automatically repeated");
        f = new Fake(); f.state = snapshot(4); f.hfp = true; f.ringing = true;
        check(run(request(87, 4), f).equals("RESULT END") && f.open == 0 && f.end == 1, "targeted CarPlay end never injects native ENDCALL");
        MediaKeyPolicy.PressGate gate = new MediaKeyPolicy.PressGate();
        check(gate.event(87, 0, 0, 100, 100, 1000), "first down accepted");
        check(!gate.event(87, 1, 0, 100, 101, 1001), "release swallowed");
        check(!gate.event(87, 0, 1, 100, 102, 1002), "long-press repeat swallowed");
        check(!gate.press(87, 1100), "duplicate transport callback swallowed");
        check(gate.press(88, 1100) && gate.press(87, 1220), "different direction and later press accepted");
        check(!gate.event(87, 0, 0, 1, 1502, 3000), "stale Android key event rejected");
        check(!gate.event(88, 0, 0, 102, 101, 3000), "future Android key event rejected");
        gate.clear();
        check(gate.event(87, 0, 0, 0, 400000, 400000), "Key Mapper constructor without timestamps accepted on receipt");
        check(!gate.event(87, 1, 0, 0, 400001, 400001), "untimed release still swallowed");
        check(!gate.event(87, 0, 1, 0, 400002, 400002), "untimed repeat still swallowed");
        check(!gate.event(87, 0, 0, 0, 400003, 400003), "untimed duplicate still debounced");
        check(!gate.event(85, 0, 0, 100, 100, 3000), "play pause unsupported");
        MediaKeyPolicy.Snapshot a = snapshot(2);
        check(MediaKeyPolicy.eligible(true, true, a, a, 1000, 2500), "deadline boundary allowed");
        check(!MediaKeyPolicy.eligible(true, true, a, a, 1000, 2501), "deadline exceeded");
        check(!MediaKeyPolicy.eligible(false, true, a, a, 1000, 1000) && !MediaKeyPolicy.eligible(true, false, a, a, 1000, 1000), "disable and disconnect cancel queued event");
        check(!MediaKeyPolicy.eligible(true, true, a, new MediaKeyPolicy.Snapshot(2, a.pid, a.status, a.call), 1000, 1000), "session generation changes cancel queued event");
        for (int phase = 0; phase <= 5; phase++) {
            KeyControlRequest original = request(88, phase), decoded = KeyControlRequest.parse(original.encode());
            check(original.id.equals(decoded.id) && decoded.expected.status == phase && decoded.created == 1000 && decoded.expected.call.equals(original.expected.call), "typed protocol round trip");
        }
        for (String bad : new String[]{"K\tshort\t1000\t87\t3497\t0\t-", "K\t12345678\t1000\t6\t3497\t0\t-", "K\t12345678\t1000\t87\t3497\t6\t-",
                "K\t12345678\t1000\t87\t3497\t2\tcaller-name", "K\t12345678\t-1\t87\t3497\t0\t-", "K\t12345678\t1000\t87\t0\t0\t-", "K\t12345678\t1000\t87\t3497\t0\t-\tother", "shell:input keyevent 6"}) {
            boolean rejected = false; try { KeyControlRequest.parse(bad); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "malformed or unapproved helper command rejected");
        }
        check(!request(88, 2).encode().contains("synthetic-call"), "protocol does not contain caller identity");
        String log = "1000.123 10089 3497 20 I DiPlay-CallKeys: call key 313: answer CarPlay call sent=true";
        CallKeyReceipt receipt = CallKeyReceipt.parse(log, 10089);
        check(receipt != null && receipt.sent && receipt.action == MediaKeyPolicy.Action.ANSWER && receipt.pid == 3497 && receipt.keyCode == 313 && receipt.wall == 1000123, "trusted DiPlay answer receipt");
        check(receipt.matches(MediaKeyPolicy.Action.ANSWER, 3497, 1000123), "exact new key receipt accepted");
        check(!receipt.matches(MediaKeyPolicy.Action.END, 3497, 1000123) && !receipt.matches(MediaKeyPolicy.Action.ANSWER, 4000, 1000123)
            && !receipt.matches(MediaKeyPolicy.Action.ANSWER, 3497, 1000124), "wrong action, process and earlier receipt rejected");
        CallKeyReceipt nativeReceipt = CallKeyReceipt.parse(log.replace("key 313", "key 5"), 10089);
        check(nativeReceipt != null && !nativeReceipt.matches(MediaKeyPolicy.Action.ANSWER, 3497, 1000123), "native CALL key receipt cannot confirm the new DiPlay-specific input");
        check(CallKeyReceipt.parse(log, 10088) == null && CallKeyReceipt.parse(log.replace("DiPlay-CallKeys", "Other"), 10089) == null, "wrong UID or tag rejected");
        receipt = CallKeyReceipt.parse(log.replace("call key 313: answer", "hang-up key 314: end").replace("true", "false"), 10089);
        check(receipt != null && !receipt.sent && receipt.action == MediaKeyPolicy.Action.END && receipt.keyCode == 314, "explicit failed hangup receipt preserved");
        check(CallKeyReceipt.parse(log.replace("sent=true", "sent=true extra"), 10089) == null, "ambiguous receipt rejected");
        CarPlayCallState tracker = new CarPlayCallState(); tracker.reset(1000000);
        check(tracker.keySnapshot().pid == -1, "new tracker is not an assumed active session");
        tracker.accept("1000.001 10089 3497 20 I xcertplay-usb: IAP2 RX [wired] 0x5201 body=0 params", 10089, 1000001, 1);
        check(tracker.keySnapshot().pid == 3497, "trusted transport header establishes source PID");
        tracker.accept("1000.002 10089 3497 20 I xcertplay-usb: IAP2 CLOSE [wired]", 10089, 1000002, 2);
        check(tracker.keySnapshot().pid == -1, "transport close invalidates idle media commands");
        tracker.accept("1000.001 10089 3497 20 I xcertplay-usb: IAP2 RX [wired] 0x5201 body=0 params", 10089, 1000003, 3);
        check(tracker.keySnapshot().pid == -1, "buffered transport header cannot revive a closed session");
        tracker.accept("1000.004 10089 3497 20 I xcertplay-usb: IAP2 RX [wired] 0x5201 body=0 params", 10089, 1000004, 4);
        check(tracker.keySnapshot().pid == 3497, "fresh transport header permits new session");
        tracker.accept("1000.005 10089 3497 20 I xcertplay-usb: IAP2 RX [wired] 0x4155 body=2 params", 10089, 1000005, 5);
        check(tracker.keySnapshot().status == 6 && MediaKeyPolicy.action(87, tracker.keySnapshot().status) == MediaKeyPolicy.Action.IGNORE,
            "partial incoming call header must not fall through to song control");
        String focus = "  mCurrentFocus=Window{ab123 u0 com.shihab.diplay/com.shilapi.xcertplay.CarPlayHostActivity}\n";
        check(DiPlayCallControl.focused(focus), "focused DiPlay CarPlay window accepted");
        check(!DiPlayCallControl.focused(focus.replace("CarPlayHostActivity", "DiPlayActivity")), "settings page is not an answer input target");
        check(!DiPlayCallControl.focused(focus.replace("com.shihab.diplay/", "com.android.dialer/")), "native phone window never receives answer injection");
        check(!DiPlayCallControl.focused(focus.replace("u0 ", "u10 ")) && !DiPlayCallControl.focused(focus + focus), "wrong user and ambiguous focus rejected");
        check(!DiPlayCallControl.focused("mCurrentFocus=null\n") && !DiPlayCallControl.focused("mFocusedApp=AppWindowToken{ " + focus + "}"), "no focused window is not inferred from foreground task");
        Android81TaskApi android81 = new Android81TaskApi();
        check(DiPlayCallControl.runningTasks(Android81TaskApi.class, android81).size() == 1 && android81.max == 1 && android81.flags == 0,
            "foreground query obeys actual Android 8.1 two-argument Binder contract");
        java.util.List<String> injected = new java.util.ArrayList<>();
        check(DiPlayCallControl.dispatchAnswer((eventAction, key) -> { injected.add(eventAction + ":" + key); return true; })
            && injected.equals(java.util.Arrays.asList("0:313", "1:313")), "one direct DiPlay-specific down/up pair without native CALL or child process");
        injected.clear();
        check(!DiPlayCallControl.dispatchAnswer((eventAction, key) -> { injected.add(eventAction + ":" + key); return eventAction == 1; })
            && injected.size() == 2, "uncertain down still releases once without retry");
        injected.clear();
        boolean injectionFailed = false;
        try { DiPlayCallControl.dispatchAnswer((eventAction, key) -> { injected.add(eventAction + ":" + key); if (eventAction == 0) throw new Exception("synthetic injection failure"); return true; }); }
        catch (Exception expected) { injectionFailed = true; }
        check(injectionFailed && injected.equals(java.util.Arrays.asList("0:313", "1:313")), "exception releases custom key once and never falls back");
        for (KeyControlEngine.FaultCode fault : KeyControlEngine.FaultCode.values()) {
            f = new Fake(); f.state = snapshot(2); f.fault = fault;
            check(run(request(88, 2), f).equals("ERR " + fault.name()) && f.mediaKeys == 0, "specific answer failure preserved instead of falling through to music");
        }
        System.out.println("MediaKeyControlTest: " + checks + " checks passed (offline; native car input still requires vehicle test)");
    }
}
