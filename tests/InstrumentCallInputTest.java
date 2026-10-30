package com.local.diplay.clusterbridge;

public final class InstrumentCallInputTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final String PREFIX = "1000.100 10042 2810 2810 I InsClusterController: ";
    private static InstrumentCallInput parse(String message) { return InstrumentCallInput.parse(PREFIX + message, 10042, 1000000, 1000200); }
    private static MediaKeyPolicy.Snapshot call(int phase) { return new MediaKeyPolicy.Snapshot(1, 3497, phase, MediaKeyPolicy.hash("synthetic-call")); }
    public static void main(String[] args) {
        InstrumentCallInput answer = parse("hqj incommingByte is 1"), reject = parse("hqj incommingByte is 2"), end = parse("hqj endByte is = 1");
        check(answer != null && answer.action == MediaKeyPolicy.Action.ANSWER, "OEM incoming flag 1 answers");
        check(reject != null && reject.action == MediaKeyPolicy.Action.END, "OEM incoming flag 2 rejects");
        check(end != null && !end.incomingOnly && end.action == MediaKeyPolicy.Action.END, "explicit end event recognized");
        check(answer.key(call(2), 1000050) == 88 && reject.key(call(2), 1000050) == 87 && end.key(call(2), 1000050) == 87, "menu uses same previous-answer next-end routing");
        for (int phase : new int[]{0, 1, 3, 4, 5, 6}) {
            check(answer.key(call(phase), 1000050) == -1 && reject.key(call(phase), 1000050) == -1, "old incoming selection cannot answer or hang up a changed call");
            check(end.key(call(phase), 1000050) == (phase >= 1 && phase <= 5 ? 87 : -1), "end event requires a live DiPlay call");
        }
        check(answer.key(null, 1000050) == -1 && answer.key(new MediaKeyPolicy.Snapshot(1, -1, 2, "-"), 1000050) == -1, "native menu cannot invent iPhone state");
        check(answer.key(call(2), 1000101) == -1 && answer.key(call(2), 0) == -1, "a menu action cannot carry over to a newer or unknown call update");
        for (String bad : new String[]{"hqj incommingByte is 0", "hqj incommingByte is 3", "hqj incommingByte is 1 extra", "hqj incommingByte is ",
            "hqj endByte is 1", "hqj endByte is = 0", "hqj SIGNAL_BT_INCOMMING_CALL_RQ", "hqj phone 主叫状态 i 1"}) {
            check(parse(bad) == null, "only explicit selected menu operations are accepted");
        }
        check(InstrumentCallInput.parse(PREFIX + "hqj incommingByte is 1", 10043, 1000000, 1000200) == null, "wrong UID rejected");
        check(InstrumentCallInput.parse(PREFIX.replace("InsClusterController", "Other") + "hqj incommingByte is 1", 10042, 1000000, 1000200) == null, "wrong tag rejected");
        check(InstrumentCallInput.parse(PREFIX + "hqj incommingByte is 1", 10042, 1000101, 1000200) == null, "pre-enable backlog rejected");
        check(InstrumentCallInput.parse(PREFIX + "hqj incommingByte is 1", 10042, 1000000, 1001101) == null, "old action rejected");
        check(InstrumentCallInput.parse(PREFIX + "hqj incommingByte is 1", 10042, 1000000, 1000099) == null, "future action rejected");
        check(InstrumentCallInput.parse(PREFIX + "hqj incommingByte is 1", -1, 1000000, 1000200) == null, "missing verified OEM package rejected");
        System.out.println("InstrumentCallInputTest: " + checks + " checks passed (offline; OEM menu event delivery unverified)");
    }
}
