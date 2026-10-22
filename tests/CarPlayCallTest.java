package com.local.diplay.clusterbridge;

public final class CarPlayCallTest {
    private static int checks;
    private static long now = 1000000, elapsed = 1000;
    private static String log(String text, int uid, int pid, int tid, long wall) {
        return (wall / 1000) + "." + String.format(java.util.Locale.ROOT, "%03d", wall % 1000) + " " + uid + " " + pid + " " + tid + " I xcertplay-usb: " + text;
    }
    private static void check(boolean result, String why) { checks++; if (!result) throw new AssertionError(why); }
    private static boolean feed(CarPlayCallState state, String message, int uid, int pid, int tid, long wall) {
        return state.accept(log(message, uid, pid, tid, wall), 10089, now, elapsed);
    }
    private static void frame(CarPlayCallState state, int status, String id, String name, String number) {
        frame(state, status == 0 ? "\"\"" : "u8=" + status + " (0x0" + status + ")", id, name, number, 10089, 3497, 10, now, "wired");
    }
    private static void frame(CarPlayCallState state, String status, String id, String name, String number, int uid, int pid, int tid, long wall, String session) {
        feed(state, "IAP2 RX [" + session + "] 0x4155 CallStateUpdate frame=100B body=5 params", uid, pid, tid, wall);
        feed(state, "  [0] param: \"" + number + "\"", uid, pid, tid, wall);
        feed(state, "  [1] param: \"" + name + "\"", uid, pid, tid, wall);
        feed(state, "  [2] param: " + status, uid, pid, tid, wall);
        feed(state, "  [3] param: u8=2 (0x02)", uid, pid, tid, wall);
        feed(state, "  [4] param: \"" + id + "\"", uid, pid, tid, wall);
        feed(state, "  raw-body=synthetic", uid, pid, tid, wall);
    }
    private static void advance(long ms) { now += ms; elapsed += ms; }
    public static void main(String[] args) {
        CarPlayCallState s = new CarPlayCallState(); s.reset(now);
        frame(s, 1, "out", "测试联系人", "12345");
        check(s.current() != null && s.current().status == 1, "live outgoing frame");
        check(s.current().name.equals("测试联系人") && s.current().number.equals("12345"), "separate contact name and remote number");
        check(s.current().seconds(elapsed) == 0 && s.current().activeSince == -1, "dialing has no timer");
        advance(1000); frame(s, 4, "out", "测试联系人", "12345");
        long since = s.current().activeSince;
        advance(21000); check(s.current().seconds(elapsed) == 21, "active call survives quiet log interval");
        frame(s, 4, "out", "测试联系人", "12345"); check(s.current().activeSince == since, "duplicates preserve timer");
        advance(1000); frame(s, 5, "out", "", "");
        check(s.current().name.equals("测试联系人") && s.current().activeSince == since && s.current().status == 5, "held delta preserves identity and timer");
        advance(1); frame(s, 2, "waiting", "来电测试", "67890");
        check(s.current().key.endsWith(":waiting") && s.current().activeSince == -1, "ringing call takes priority");
        advance(1); frame(s, 0, "waiting", "来电测试", "67890");
        check(s.current().key.endsWith(":out") && s.current().activeSince == since, "ending waiting call reveals held call");
        advance(1); frame(s, 6, "out", "", ""); check(s.current() == null, "disconnecting clears last call");
        frame(s, 4, "out", "迟到", "12345"); check(s.current() == null, "late equal-time frame cannot resurrect ended call");
        advance(1); frame(s, 2, "incoming", "", "54321"); check(s.current().name.equals("54321"), "number fallback");
        advance(1000); frame(s, 0, "incoming", "", "54321"); check(s.current() == null, "real formatter quoted zero ends call");
        frame(s, "u8=7 (0x07)", "invalid", "错误", "1", 10089, 3497, 10, now, "wired"); check(s.current() == null, "unknown status refused");
        frame(s, "u8=4 (0x02)", "invalid", "错误", "1", 10089, 3497, 10, now, "wired"); check(s.current() == null, "contradictory scalar refused");
        for (String bad : new String[]{"", "void", "u16=4 (0x0004)", "\"4\"", "bytes=1 [04]"}) check(CarPlayCallState.status(bad) == null, "invalid status form refused");
        frame(s, "u8=4 (0x04)", "untrusted", "错误", "1", 10088, 3497, 10, now, "wired"); check(s.current() == null, "wrong UID refused");
        frame(s, "u8=4 (0x04)", "stale", "错误", "1", 10089, 3497, 10, now - 11000, "wired"); check(s.current() == null, "old log backlog refused");
        frame(s, "u8=4 (0x04)", "future", "错误", "1", 10089, 3497, 10, now + 1001, "wired"); check(s.current() == null, "future log refused");
        frame(s, 4, "", "", ""); check(s.current() == null, "identity required");
        feed(s, "IAP2 RX [wired] 0x4155 body=3 params", 10089, 3497, 10, now);
        feed(s, "  [2] param: u8=4 (0x04)", 10089, 3497, 10, now);
        feed(s, "  [4] param: \"partial\"", 10089, 3497, 10, now);
        feed(s, "  raw-body=synthetic", 10089, 3497, 10, now);
        check(s.current() == null, "dropped parameter invalidates incomplete frame");
        feed(s, "IAP2 RX [wired] 0x4155 body=2 params", 10089, 3497, 10, now);
        feed(s, "  [2] param: u8=4 (0x04)", 10089, 3497, 10, now);
        feed(s, "IAP2 RX [wired] 0x5201 body=1 params", 10089, 3497, 10, now);
        feed(s, "  [4] param: \"interrupted\"", 10089, 3497, 10, now);
        feed(s, "  raw-body=synthetic", 10089, 3497, 10, now);
        check(s.current() == null, "non-call frame interrupts partial call");
        advance(1); frame(s, 4, "session", "示例", "12345");
        feed(s, "IAP2 CLOSE [other]", 10089, 3497, 10, now); check(s.current() != null, "unrelated session close ignored");
        feed(s, "IAP2 CLOSE [wired]", 10089, 3497, 10, now); check(s.current() == null, "session close clears call");
        frame(s, 4, "session", "迟到", "12345"); check(s.current() == null, "closed session's buffered frame refused");
        advance(1); frame(s, 4, "new", "新通话", "12345"); check(s.current() != null, "new live call after close");
        advance(1); feed(s, "IAP2 RX [wired] 0x5201 body=0 params", 10089, 4000, 10, now);
        check(s.current() == null, "source process replacement clears old call");
        s.reset(now); frame(s, "u8=4 (0x04)", "old", "旧通话", "12345", 10089, 4000, 10, now - 1, "wired");
        check(s.current() == null, "enable cutoff refuses prior call");
        check(CarPlayCallState.quoted("\"张\\\"三\\n测试\\\\\"").equals("张\"三\n测试\\"), "formatter escaping");
        check(CarPlayCallState.quoted("\"bad\\q\"") == null && CarPlayCallState.quoted("\"bad\"tail") == null, "bad escapes and quote forms rejected");
        check(CallDisplayProtocol.communication(1) == 2 && CallDisplayProtocol.communication(3) == 2, "stock outgoing state");
        check(CallDisplayProtocol.communication(2) == 6 && CallDisplayProtocol.communication(4) == 3 && CallDisplayProtocol.communication(5) == 16, "stock ring active held mapping");
        String caller = CallDisplayProtocol.caller(1, "12345", "测试");
        check(caller.equals("SignalID:0x50;field:{BTDevie_Index:0;};field1:{PhoneType:0;Validity:2;};CallIDNumber:12345;CallIDName:测试;"), "exact native field names and spelling");
        check(CallDisplayProtocol.caller(0, "", "").endsWith("Validity:0;};CallIDNumber:;CallIDName:;"), "stock empty caller cleanup");
        check(CallDisplayProtocol.text("张;{三}\n:四\u202e", 32).equals("张 三 四"), "structural injection and controls removed");
        check(CallDisplayProtocol.text("甲😀乙😀", 3).equals("甲😀乙"), "Unicode-safe display truncation");
        for (int id : CallDisplayProtocol.IDS) check(id == 554699825 || id == 557845523 || id == 557845524 || id == 557845525 || id == 557845528, "display-only allowlist");
        System.out.println("CarPlayCallTest: " + checks + " checks passed");
    }
}
