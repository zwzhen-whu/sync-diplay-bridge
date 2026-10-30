package com.local.diplay.clusterbridge.homemap;

/** Keep one display identity for the session; suspend its output instead of unplugging it. */
final class MapDisplayLease {
    interface Port {
        boolean create(boolean rendering);
        void render(boolean rendering);
        void release();
    }
    private boolean allocated, rendering;
    void update(boolean surfaceReady, boolean requested, Port port) {
        boolean next = surfaceReady && requested;
        if (!allocated) {
            if (surfaceReady && port.create(next)) { allocated = true; rendering = next; }
        } else if (rendering != next) {
            port.render(next); rendering = next;
        }
    }
    void close(Port port) {
        if (allocated) { port.release(); allocated = false; rendering = false; }
    }
}
