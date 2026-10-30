package com.local.diplay.clusterbridge;

import java.util.Objects;

/** Source ownership uses CarRadio's native_source numbering, not PLAY_INFO's source_type numbering. */
public final class MediaRouting {
    public static final int UNKNOWN = -1, ONLINE = 0, BLUETOOTH = 1, USB = 2, RADIO = 3, CARPLAY = 4;
    public enum Target { DIPLAY, CARRADIO, NONE }
    public static final class State {
        public final int nativeSource, playback;
        public final Object carplaySession;
        public final boolean radioAvailable, ambiguousSession;
        public State(int nativeSource, int playback, Object carplaySession, boolean radioAvailable, boolean ambiguousSession) {
            if (nativeSource < UNKNOWN || nativeSource > CARPLAY) throw new IllegalArgumentException("Unknown native source");
            this.nativeSource = nativeSource; this.playback = playback; this.carplaySession = carplaySession;
            this.radioAvailable = radioAvailable; this.ambiguousSession = ambiguousSession;
        }
        public Target target() {
            if (nativeSource >= ONLINE && nativeSource <= RADIO) return radioAvailable ? Target.CARRADIO : Target.NONE;
            if (ambiguousSession) return Target.NONE;
            // Android PlaybackState.STATE_PLAYING is 3. A connected/paused session is not playing music.
            if (carplaySession != null && playback == 3) return Target.DIPLAY;
            return radioAvailable ? Target.CARRADIO : Target.NONE;
        }
        public boolean same(State other) {
            return other != null && nativeSource == other.nativeSource && playback == other.playback
                && Objects.equals(carplaySession, other.carplaySession) && radioAvailable == other.radioAvailable
                && ambiguousSession == other.ambiguousSession;
        }
    }
    public interface Port {
        State state() throws Exception;
        boolean current() throws Exception;
        int audioMode() throws Exception;
        boolean send(Target target, State captured, int key) throws Exception;
    }
    private MediaRouting() {}
    /** Make one decision, recheck immediately before sending, and never try a second player on failure. */
    public static Target execute(int key, Port port) throws Exception {
        if (key != MediaKeyPolicy.NEXT && key != MediaKeyPolicy.PREVIOUS) throw new IllegalArgumentException("Unsupported media key");
        State captured = port.state();
        Target target = captured.target();
        if (target == Target.NONE) return Target.NONE;
        if (!port.current()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.STATE_CHANGED);
        if (port.audioMode() != 0) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.AUDIO_BUSY);
        if (!captured.same(port.state()) || !port.current()) throw new KeyControlEngine.Fault(KeyControlEngine.FaultCode.STATE_CHANGED);
        return port.send(target, captured, key) ? target : Target.NONE;
    }
}
