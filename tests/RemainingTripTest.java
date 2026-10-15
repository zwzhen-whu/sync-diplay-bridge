package com.local.diplay.clusterbridge;

public final class RemainingTripTest {
    private static void eq(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
    }
    private static final RemainingTrip.Formats EN = new RemainingTrip.Formats("%1$d m", "%1$.1f km", "%1$d km", "%1$d min", "%1$d h %2$02d min");
    private static void trip(RemainingTrip.Formats format, String text, int meters, int seconds) {
        RemainingTrip trip = format.parse(text);
        if (trip == null) throw new AssertionError("Cannot parse " + text);
        eq(trip.meters, meters); eq(trip.seconds, seconds);
    }
    public static void main(String[] args) {
        trip(EN, "14:35 · 12 km · 23 min", 12000, 1380);
        trip(EN, "2.4 km · 1 h 05 min", 2400, 3900);
        trip(EN, "11:30 AM · 230 m · 0 min", 230, 0);
        trip(EN, "0 m · 0 min", 0, 0);
        trip(EN, "9.9 km · 24 h 00 min", 9900, 86400);
        trip(EN, "２.５ km · １２ min", 2500, 720);
        trip(EN, "\u200e2,5\u00a0km · 12\u202fmin", 2500, 720);
        RemainingTrip.Formats zh = new RemainingTrip.Formats("%1$d 米", "%1$.1f 公里", "%1$d 公里", "%1$d 分钟", "%1$d 小时 %2$02d 分钟");
        trip(zh, "下午 2:35 · 12 公里 · 1 小时 05 分钟", 12000, 3900);
        trip(zh, "990 米 · 23 分钟", 990, 1380);
        RemainingTrip.Formats ar = new RemainingTrip.Formats("%1$d م", "%1$.1f كم", "%1$d كم", "%1$d دقيقة", "%1$d س %2$02d د");
        trip(ar, "١٤:٣٥ · ١٫٢ كم · ٢ س ٠٥ د", 1200, 7500);
        trip(new RemainingTrip.Formats("%1$d 公尺", "%1$.1f 公里", "%1$d 公里", "%1$d 分鐘", "%1$d 小時 %2$02d 分鐘"),
            "下午 2:35 · 12 公里 · 1 小時 05 分鐘", 12000, 3900);
        trip(new RemainingTrip.Formats("%1$d m", "%1$.1f km", "%1$d km", "%1$d分", "%1$d時間%2$02d分"),
            "14:35 · 12 km · 1時間05分", 12000, 3900);
        trip(new RemainingTrip.Formats("%1$d м", "%1$.1f км", "%1$d км", "%1$d мин", "%1$d ч %2$02d мин"),
            "14:35 · 12 км · 1 ч 05 мин", 12000, 3900);
        trip(new RemainingTrip.Formats("%1$d м", "%1$.1f км", "%1$d км", "%1$d хв", "%1$d год %2$02d хв"),
            "14:35 · 12 км · 1 год 05 хв", 12000, 3900);
        RemainingTrip.Formats reordered = new RemainingTrip.Formats("%1$d m", "%1$.1f km", "%1$d km", "%1$d min", "%2$02d min / %1$d h");
        trip(reordered, "2 km · 05 min / 1 h", 2000, 3900);
        // A clock, missing field, unsupported unit or changed widget layout is never guessed.
        for (String text : new String[]{null, "", "14:30", "23 min", "2 km", "14:30 · 2 km", "14:30 · 23 min",
            "2 km · 14:30", "2 km · -1 min", "-2 km · 10 min", "2 km · 1 h 60 min", "2 miles · 10 min",
            "2 km · 8 days", "2 km · 169 h 00 min", "2001 km · 10 min", "99999999999 km · 10 min",
            "2 km · NaN min", "14:30 · 2 km · 10 min · extra", "14:30 ·  · 10 min"}) eq(EN.parse(text), null);
        eq(new RemainingTrip.Formats(null, null, null, null, null).parse("2 km · 10 min"), null);
        eq(new RemainingTrip.Formats("%1$d m", "%1$.1f km", "%1$d km", null, null).parse("2 km · 10 min"), null);

        RemainingDelivery delivery = new RemainingDelivery();
        RemainingTrip first = EN.parse("2 km · 10 min"), changed = EN.parse("1.9 km · 9 min");
        for (int i = 0; i < 3600; i++) eq(delivery.next(false, true, first), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, false, first), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, null), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, first), RemainingDelivery.Action.SEND);
        for (int i = 0; i < 3600; i++) eq(delivery.next(true, true, EN.parse("2 km · 10 min")), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, changed), RemainingDelivery.Action.SEND);
        eq(delivery.next(true, true, null), RemainingDelivery.Action.CLEAR);
        eq(delivery.next(true, true, null), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, first), RemainingDelivery.Action.SEND);
        eq(delivery.next(false, true, first), RemainingDelivery.Action.CLEAR);
        eq(delivery.next(false, true, first), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, first), RemainingDelivery.Action.SEND);
        eq(delivery.next(true, false, first), RemainingDelivery.Action.CLEAR);
        eq(delivery.next(true, false, first), RemainingDelivery.Action.NONE);
        eq(delivery.next(true, true, first), RemainingDelivery.Action.SEND);
        System.out.println("Remaining trip tests passed (localized formats, meters/seconds, missing data, default off, changes, clear once, reconnect)");
    }
}
