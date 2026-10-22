package com.local.diplay.clusterbridge;

/** Stateful regressions for actual log parsing, independent of Android views and receiver rendering. */
public final class NavigationStateContinuityTest {
    private static final long BASE = 1792411200000L;
    private static final int UID = 10089, PID = 3283, TID = 6987, IDENTIFIER = 42;
    private static int passed, failed;

    private static void eq(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("actual=" + actual + "; expected=" + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String prefix(long offset, int pid) {
        long wall = BASE + offset;
        return String.format(java.util.Locale.ROOT, "%d.%03d %d %d %d I xcertplay-usb: ",
            wall / 1000, wall % 1000, UID, pid, TID);
    }

    private static void frame(NavigationLogState state, long offset, int pid, String session, String kind, String... parameters) {
        String prefix = prefix(offset, pid);
        state.accept(prefix + "IAP2 RX [" + session + "] 0x" + kind + " Message", UID, BASE + offset);
        for (String parameter : parameters) state.accept(prefix + parameter, UID, BASE + offset);
        state.accept(prefix + "  raw-body=00 06", UID, BASE + offset);
    }

    private static void detail(NavigationLogState state, long offset, int pid, String session, int identifier, int index, String road) {
        frame(state, offset, pid, session, "5202", "  [0] Identifier: u16=" + identifier,
            "  [1] ManeuverIndex: u16=" + index, "  [2] Description: \"" + road + "\"",
            "  [3] ManeuverType: u8=1", "  [4] AfterRoad: \"" + road + "\"");
    }

    private static void detail(NavigationLogState state, long offset, int index, String road) {
        detail(state, offset, PID, "wired", IDENTIFIER, index, road);
    }

    private static void route(NavigationLogState state, long offset, int pid, String session, int identifier, int index, int meters) {
        frame(state, offset, pid, session, "5201", "  [0] Identifier: u16=" + identifier,
            "  [1] State: u8=1", "  [10] Distance: u32=" + meters, "  [13] CurrentManeuverList: u16=" + index);
    }

    private static void route(NavigationLogState state, long offset, int index, int meters) {
        route(state, offset, PID, "wired", IDENTIFIER, index, meters);
    }

    private static String road(NavigationLogState state, long offset, int meters) {
        return state.road("turn_left", meters, BASE + offset);
    }

    private static NavigationLogState activeNextRoad() {
        NavigationLogState state = new NavigationLogState();
        detail(state, 0, 0, "三环线 卓刀泉立交");
        detail(state, 1, 1, "珞瑜路");
        route(state, 2, 1, 600);
        eq(road(state, 3, 600), "珞瑜路");
        return state;
    }

    private static void futurePrefetchPreservesCurrentAndNext() {
        NavigationLogState state = new NavigationLogState();
        detail(state, 0, 0, "三环线 卓刀泉立交"); route(state, 1, 0, 600);
        for (int index = 1; index <= 80; index++) detail(state, index + 2, index, index == 1 ? "珞瑜路" : "未来道路" + index);
        require(state.savedManeuvers().split("\n").length <= 64, "persisted maneuver cache exceeded 64 records");
        eq(road(state, 84, 600), "三环线 卓刀泉立交");
        route(state, 85, 1, 550);
        eq(road(state, 86, 550), "珞瑜路");
        require(state.savedManeuvers().split("\n").length <= 64, "cache grew after advancing to the next road");
    }

    private static void lateZeroDetailKeepsActiveNextRoad() {
        NavigationLogState state = activeNextRoad();
        // Detail updates are not route-selection messages. A late/full first-turn detail
        // must not remove the already-selected second turn or its cached normal road.
        detail(state, 10, 0, "三环线 卓刀泉立交");
        eq(road(state, 11, 600), "珞瑜路");
        route(state, 12, 1, 550);
        eq(road(state, 13, 550), "珞瑜路");
        // A partial refresh of the inactive first turn must have the same isolation.
        frame(state, 14, PID, "wired", "5202", "[0] Identifier: u16=42", "[1] ManeuverIndex: u16=0",
            "[2] Description: \"首个路口说明更新\"");
        eq(road(state, 15, 550), "珞瑜路");
    }

    private static void transientEmptyListAllowsPartialResume() {
        NavigationLogState state = activeNextRoad();
        frame(state, 100, PID, "wired", "5201", "[0] Identifier: u16=42", "[1] State: u8=1",
            "[10] Distance: u32=600", "[13] CurrentManeuverList: void");
        eq(road(state, 150, 600), "珞瑜路");
        eq(road(state, 2099, 600), "珞瑜路");
        // The phone need not resend State or the 0x5202 detail when a list resumes.
        frame(state, 2200, PID, "wired", "5201", "[0] Identifier: u16=42", "[10] Distance: u32=550",
            "[13] CurrentManeuverList: u16=1");
        eq(road(state, 2201, 550), "珞瑜路");
    }

    private static void lastingEmptyListHidesGuidanceWithoutPurgingDetails() {
        NavigationLogState state = activeNextRoad();
        frame(state, 100, PID, "wired", "5201", "[0] Identifier: u16=42", "[1] State: u8=1",
            "[10] Distance: u32=600", "[13] CurrentManeuverList: void");
        eq(road(state, 3101, 600), "");
        route(state, 4000, 1, 550);
        eq(road(state, 4001, 550), "珞瑜路");
    }

    private static void repeatedEmptyKeepalivesKeepFixedHideDeadlineAndResumeContext() {
        NavigationLogState state = activeNextRoad();
        for (int offset = 100; offset <= 20100; offset += 1000) {
            frame(state, offset, PID, "wired", "5201", "[0] Identifier: u16=42", "[1] State: u8=5",
                "[13] CurrentManeuverList: void");
            eq(road(state, offset + 1, 600), offset < 3100 ? "珞瑜路" : "");
        }
        // The empty-list stream lasted longer than ROUTE_TIMEOUT, but is not a
        // route end. A populated partial list resumes without resending details.
        frame(state, 21100, PID, "wired", "5201", "[0] Identifier: u16=42", "[10] Distance: u32=450",
            "[13] CurrentManeuverList: u16=1");
        eq(road(state, 21101, 450), "珞瑜路");
        eq(road(state, 21101, 500), "");
        eq(road(state, 22601, 600), "");
        eq(state.road("turn_right", 450, BASE + 21101), "");
    }

    private static void expiredRestartCandidateCannotReviveAnOldFirstTurn() {
        NavigationLogState state = activeNextRoad();
        detail(state, 100, 0, "过早收到的首路刷新");
        route(state, 15000, 1, 550);
        route(state, 20000, 0, 500);
        eq(road(state, 20001, 500), "");
        detail(state, 20002, 0, "已确认的新首路");
        eq(road(state, 20003, 500), "已确认的新首路");
    }

    private static void explicitEndCannotResurrectOldRoad() {
        for (int endState : new int[]{0, 2}) {
            NavigationLogState state = activeNextRoad();
            frame(state, 100, PID, "wired", "5201", "[0] Identifier: u16=42", "[1] State: u8=" + endState);
            eq(road(state, 101, 600), "");
            frame(state, 102, PID, "wired", "5201", "[0] Identifier: u16=42", "[10] Distance: u32=550",
                "[13] CurrentManeuverList: u16=1");
            eq(road(state, 103, 550), "");
            route(state, 104, 1, 550);
            eq(road(state, 105, 550), "");
        }
    }

    private static void changedSourceCannotBorrowOldRoad() {
        // Each new source has the same maneuver, direction and distance; these
        // coincidences must never make an old source's text a valid fallback.
        int[][] identities = {{PID + 1, IDENTIFIER}, {PID, IDENTIFIER}, {PID, IDENTIFIER + 1}};
        String[] sessions = {"wired", "wireless", "wired"};
        for (int i = 0; i < identities.length; i++) {
            NavigationLogState state = activeNextRoad();
            int pid = identities[i][0], identifier = identities[i][1];
            route(state, 100, pid, sessions[i], identifier, 1, 600);
            eq(road(state, 101, 600), "");
            detail(state, 102, pid, sessions[i], identifier, 1, "新来源道路");
            eq(road(state, 103, 600), "新来源道路");
        }
    }

    private static void closeCannotResurrectOldRoad() {
        NavigationLogState state = activeNextRoad();
        state.accept(prefix(100, PID) + "IAP2 CLOSE [wired]", UID, BASE + 100);
        eq(road(state, 101, 600), "");
        route(state, 102, 1, 600);
        eq(road(state, 103, 600), "");
    }

    private static void confirmedReturnToZeroCannotReuseOldFuture() {
        NavigationLogState state = activeNextRoad();
        detail(state, 100, 0, "新路线首路");
        // A future detail actually received after the new first detail belongs
        // to the new candidate and must survive confirmation of route index 0.
        detail(state, 101, 2, "新路线第三路");
        route(state, 102, 0, 600);
        eq(road(state, 103, 600), "新路线首路");
        route(state, 104, 1, 550);
        eq(road(state, 105, 550), ""); // old-route 珞瑜路 was not resent
        route(state, 106, 2, 500);
        eq(road(state, 107, 500), "新路线第三路");
        detail(state, 108, 1, "新路线第二路"); route(state, 109, 1, 450);
        eq(road(state, 110, 450), "新路线第二路");
    }

    private static void opaqueZeroManeuverTypeIsStraight() {
        NavigationLogState state = new NavigationLogState();
        // DiPlay 0.2.19 has no strict 0x5202 field schema. formatOpaque
        // tries readableString before u8: the one-byte zero payload is "".
        frame(state, 0, PID, "wired", "5202", "[0] param: u16=42 (0x00 2a)",
            "[1] param: u16=7 (0x00 07)", "[3] param: \"\"", "[4] param: \"珞瑜路\"");
        route(state, 1, 7, 600);
        eq(state.road("turn_front", 600, BASE + 2), "珞瑜路");
        eq(state.road("turn_left", 600, BASE + 2), "");
    }

    private static void opaqueZeroRouteStateEndsGuidance() {
        NavigationLogState state = activeNextRoad();
        // Exact parameter text from baidu-live-epoch.txt:3560-3563;
        // the captured raw body ends in TLV 00 05 00 01 00 (u8 zero).
        frame(state, 100, PID, "wired", "5201", "[0] param: u16=42 (0x00 2a)", "[1] param: \"\"");
        eq(road(state, 101, 600), "");
        route(state, 102, 1, 550);
        eq(road(state, 103, 550), "");
    }

    private static void nonterminalRouteStatesFollowUpstream() {
        // Upstream BydHudRouteState only treats 0 and 2 as route termination.
        // State 3 is present in both Oct 18 map captures; state 5 is covered by
        // the upstream empty-list test. Neither should discard a matching turn.
        for (int activeState : new int[]{3, 5}) {
            NavigationLogState state = activeNextRoad();
            frame(state, 100, PID, "wired", "5201", "[0] param: u16=42 (0x00 2a)",
                "[1] param: u8=" + activeState, "[10] param: u32=550", "[13] param: u16=1");
            eq(road(state, 101, 550), "珞瑜路");
        }
    }

    private static void run(String name, Runnable test) {
        try {
            test.run(); passed++;
            System.out.println("PASS " + name);
        } catch (AssertionError failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure.getMessage());
        }
    }

    public static void main(String[] args) {
        run("future prefetch preserves current and immediate next under 64-record limit", NavigationStateContinuityTest::futurePrefetchPreservesCurrentAndNext);
        run("late/full and partial zero details preserve selected next road", NavigationStateContinuityTest::lateZeroDetailKeepsActiveNextRoad);
        run("brief empty list and state-less partial resume preserve matching road", NavigationStateContinuityTest::transientEmptyListAllowsPartialResume);
        run("lasting empty list hides guidance while allowing fresh recovery", NavigationStateContinuityTest::lastingEmptyListHidesGuidanceWithoutPurgingDetails);
        run("repeated empty keepalives keep the hide deadline and partial resume context", NavigationStateContinuityTest::repeatedEmptyKeepalivesKeepFixedHideDeadlineAndResumeContext);
        run("expired restart candidate cannot revive an old first turn", NavigationStateContinuityTest::expiredRestartCandidateCannotReviveAnOldFirstTurn);
        run("explicit end cannot resurrect old road", NavigationStateContinuityTest::explicitEndCannotResurrectOldRoad);
        run("changed PID/session/identifier cannot borrow old road", NavigationStateContinuityTest::changedSourceCannotBorrowOldRoad);
        run("CLOSE cannot resurrect old road", NavigationStateContinuityTest::closeCannotResurrectOldRoad);
        run("confirmed nonzero-to-zero new route cannot reuse old future details", NavigationStateContinuityTest::confirmedReturnToZeroCannotReuseOldFuture);
        run("opaque quoted zero maneuver type is valid straight guidance", NavigationStateContinuityTest::opaqueZeroManeuverTypeIsStraight);
        run("opaque quoted zero state ends guidance without stale resurrection", NavigationStateContinuityTest::opaqueZeroRouteStateEndsGuidance);
        run("nonterminal route states follow the upstream state machine", NavigationStateContinuityTest::nonterminalRouteStatesFollowUpstream);
        System.out.println("Navigation continuity regressions: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError(failed + " navigation continuity regression groups failed");
    }
}
