package com.local.diplay.clusterbridge;

/** Descriptor-only identification: never opens, claims, or asks permission for USB. */
public final class IphoneUsbPresence {
    public enum State { PRESENT, ABSENT, UNKNOWN }
    public static State device(int vendorId, String productName) {
        if (vendorId != 0x05ac) return State.ABSENT;
        if (productName == null || productName.trim().isEmpty()) return State.UNKNOWN;
        String name = productName.trim();
        return name.equalsIgnoreCase("iPhone") || name.regionMatches(true, 0, "iPhone ", 0, 7) ? State.PRESENT : State.ABSENT;
    }
    public static State inventory(State... devices) {
        if (devices == null) return State.UNKNOWN;
        int phones = 0; boolean unknown = false;
        for (State device : devices) {
            if (device == State.PRESENT) phones++;
            else if (device == null || device == State.UNKNOWN) unknown = true;
        }
        if (phones > 1) return State.UNKNOWN;
        return phones == 1 ? State.PRESENT : unknown ? State.UNKNOWN : State.ABSENT;
    }
}
