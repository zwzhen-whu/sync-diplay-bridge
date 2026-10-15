package com.local.diplay.clusterbridge;

/** The opt-in trip summary has its own delivery state; it never triggers music updates. */
public final class RemainingDelivery {
    public enum Action { NONE, SEND, CLEAR }
    private RemainingTrip last;

    public Action next(boolean enabled, boolean activeNavigation, RemainingTrip value) {
        if (!enabled || !activeNavigation || value == null) {
            boolean owned = last != null;
            last = null;
            return owned ? Action.CLEAR : Action.NONE;
        }
        if (value.sameAs(last)) return Action.NONE;
        last = value;
        return Action.SEND;
    }
}
