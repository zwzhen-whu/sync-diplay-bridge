package com.local.diplay.clusterbridge.homemap;

/** One window update per display frame, with no recurring animation or idle work. */
final class MapFollowFrame {
    interface Port { void post(Runnable frame); void remove(Runnable frame); void apply(); }
    private final Port port;
    private boolean pending, closed;
    private final Runnable frame;
    MapFollowFrame(Port port) {
        this.port = port;
        frame = () -> { pending = false; if (!closed) this.port.apply(); };
    }
    void request() {
        if (closed || pending) return;
        pending = true; port.post(frame);
    }
    void close() {
        closed = true;
        if (pending) port.remove(frame);
        pending = false;
    }
}
