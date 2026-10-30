package com.local.diplay.clusterbridge;

import java.util.LinkedHashSet;

/** Pure execution boundary. A press is never retried or converted after its captured state changes. */
public final class KeyControlEngine {
    public interface Port {
        long elapsed();
        long resetAt();
        boolean enabled() throws Exception;
        MediaKeyPolicy.Snapshot snapshot();
        boolean sourceAlive(int pid) throws Exception;
        int audioMode() throws Exception;
        boolean callControlsEnabled() throws Exception;
        boolean openCarPlay() throws Exception;
        MediaRouting.Target media(KeyControlRequest request) throws Exception;
        boolean call(KeyControlRequest request, MediaKeyPolicy.Action action) throws Exception;
    }
    public enum FaultCode { ANSWER_NOT_FOREGROUND, ANSWER_NOT_FOCUSED, ANSWER_INPUT_REJECTED, ANSWER_NO_RECEIPT, ANSWER_DECLINED,
        STATE_CHANGED, AUDIO_BUSY, DIPLAY_CONTROLS_OFF, DIPLAY_VERSION_UNSUPPORTED }
    public static final class Fault extends Exception {
        public final FaultCode code;
        public Fault(FaultCode code) { super(code.name()); this.code = code; }
    }
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private String validate(KeyControlRequest request, Port port) throws Exception {
        if (!port.enabled()) return "ERR DISABLED";
        if (!request.matches(port.snapshot(), port.resetAt(), port.elapsed()) || request.expected.pid > 0 && !port.sourceAlive(request.expected.pid)) return "ERR STATE_CHANGED";
        return null;
    }
    public synchronized String execute(KeyControlRequest request, Port port) throws Exception {
        if (!seen.add(request.id)) return "ERR DUPLICATE";
        if (seen.size() > 64) seen.remove(seen.iterator().next());
        String invalid = validate(request, port); if (invalid != null) return invalid;
        MediaKeyPolicy.Action action = MediaKeyPolicy.action(request.key, request.expected.status);
        if (action == MediaKeyPolicy.Action.IGNORE) return "RESULT IGNORE";
        if (action == MediaKeyPolicy.Action.NEXT || action == MediaKeyPolicy.Action.PREVIOUS) {
            if (port.audioMode() != 0) return "ERR AUDIO_BUSY";
            invalid = validate(request, port); if (invalid != null) return invalid;
            try {
                MediaRouting.Target target = port.media(request);
                return target == MediaRouting.Target.NONE ? "ERR NO_MEDIA"
                    : "RESULT " + (target == MediaRouting.Target.CARRADIO ? "CARRADIO_" : "") + action.name();
            } catch (Fault fault) { return "ERR " + fault.code.name(); }
        }
        try { if (!port.callControlsEnabled()) return "ERR DIPLAY_CONTROLS_OFF"; }
        catch (Fault fault) { return "ERR " + fault.code.name(); }
        if (action == MediaKeyPolicy.Action.ANSWER) {
            // The captured state is DiPlay's iPhone call, independent of Android/HFP state.
            if (!port.openCarPlay()) return "ERR CARPLAY_NOT_FOREGROUND";
        }
        invalid = validate(request, port); if (invalid != null) return invalid;
        try { return port.call(request, action) ? "RESULT " + action.name() : "ERR UNCONFIRMED"; }
        catch (Fault fault) { return "ERR " + fault.code.name(); }
    }
}
