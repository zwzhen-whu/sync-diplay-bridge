package com.local.diplay.clusterbridge;

/** Regressions for one-shot initial music delivery and first-boot CarPlay entry. */
public final class StartupTest {
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        Protocol.Song song = Protocol.song("♪ 正在播放的歌曲");
        MusicDelivery delivery = new MusicDelivery();
        eq(delivery.next(null), MusicDelivery.Action.NONE);
        eq(delivery.next(song), MusicDelivery.Action.SEND); // first valid widget snapshot, no song change needed
        delivery.sent(song);
        for (int tick = 0; tick < 3600; tick++) {
            eq(delivery.next(Protocol.song("♪ 正在播放的歌曲")), MusicDelivery.Action.NONE);
        }
        Protocol.Song paused = Protocol.song("❚❚ 正在播放的歌曲");
        eq(delivery.next(paused), MusicDelivery.Action.SEND);
        delivery.sent(paused);
        eq(delivery.next(paused), MusicDelivery.Action.NONE);
        Protocol.Song changed = Protocol.song("♪ 下一首");
        eq(delivery.next(changed), MusicDelivery.Action.SEND);
        delivery.sent(changed);
        eq(delivery.next(changed), MusicDelivery.Action.NONE);
        delivery.reset(); // a fresh connection, disabled forwarding, or service restart
        eq(delivery.next(null), MusicDelivery.Action.NONE);
        eq(delivery.next(changed), MusicDelivery.Action.SEND);
        delivery.sent(changed);
        eq(delivery.next(changed), MusicDelivery.Action.NONE);

        CarPlayEntryPolicy entry = new CarPlayEntryPolicy();
        entry.connection(true, 0);
        eq(entry.request(2000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(entry.request(3000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(entry.request(5000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(entry.request(6000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        entry.connection(false, 7000); entry.connection(true, 8000);
        eq(entry.request(11000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(entry.request(14000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(entry.request(16000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true); // first-boot fallback after video appeared
        entry.connection(false, 17000); entry.connection(true, 18000);
        eq(entry.request(21000, "com.other.app", "OtherActivity"), false);
        eq(entry.request(22000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false); // never pull back
        entry.connection(false, 23000); entry.connection(true, 24000);
        eq(entry.request(27000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        entry.unknownForeground(); // screen off, denied access, or no reliable usage event
        eq(entry.request(29000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(entry.request(31000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        entry.connection(false, 32000); entry.connection(true, 33000);
        eq(entry.request(124000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false); // expired
        entry.connection(false, 125000);
        eq(entry.request(130000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);

        // The entry page may have been foreground for minutes before the phone
        // connects. A one-minute lookback would miss it, and empty incremental
        // queries must not erase that unchanged foreground activity.
        ForegroundActivityState foreground = new ForegroundActivityState();
        long wall = 2000000, elapsed = 600000, oldResume = wall - 180000;
        if (foreground.queryStart(wall, elapsed) > oldResume) throw new AssertionError("Long-wait entry event omitted");
        foreground.event(1, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY, oldResume);
        foreground.queried(wall, elapsed);
        CarPlayEntryPolicy delayed = new CarPlayEntryPolicy(); delayed.connection(true, elapsed);
        eq(delayed.request(elapsed+3000, foreground.packageName(), foreground.className()), false);
        foreground.queryStart(wall+4000, elapsed+4000); foreground.queried(wall+4000, elapsed+4000); // no new events
        eq(delayed.request(elapsed+5000, foreground.packageName(), foreground.className()), true);
        foreground.event(2, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY, wall+6000);
        eq(foreground.packageName(), null);
        foreground.event(1, "com.other.app", "OtherActivity", wall+7000);
        foreground.event(1, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY, oldResume); // old overlapping record
        eq(foreground.packageName(), "com.other.app");
        foreground.queried(wall+8000, elapsed+8000);
        foreground.queryStart(wall-1000, elapsed+9000); // wall-clock adjustment invalidates old state
        eq(foreground.packageName(), null);

        // Replay the measured first-boot timing: session active, video foreground,
        // entry activity returns 3749 ms later, without another connection event.
        CarPlayEntryPolicy bootRace = new CarPlayEntryPolicy(); bootRace.connection(true, 0);
        eq(bootRace.request(3000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(bootRace.request(3749, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        eq(bootRace.request(5749, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), true);
        eq(bootRace.request(7000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false);
        CarPlayEntryPolicy settled = new CarPlayEntryPolicy(); settled.connection(true, 0);
        eq(settled.request(3000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.PLAYER), false);
        eq(settled.request(91000, CarPlayEntryPolicy.SOURCE, CarPlayEntryPolicy.ENTRY), false); // deliberate return after startup
        System.out.println("Startup tests passed (first song once, no repeated sends, pause/change, first-boot fallback, long-wait foreground, reconnect limits)");
    }
}
