package com.local.diplay.clusterbridge.homemap;

public final class MapDisplayLeaseTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    private static final class Fake implements MapDisplayLease.Port {
        int created, released, changes; boolean rendering, accept = true;
        public boolean create(boolean next) { created++; if (!accept) return false; rendering = next; return true; }
        public void render(boolean next) { changes++; rendering = next; }
        public void release() { released++; rendering = false; }
    }
    public static void main(String[] args) {
        MapDisplayLease lease = new MapDisplayLease(); Fake port = new Fake();
        lease.update(false, true, port); check(port.created == 0);
        lease.update(true, false, port); check(port.created == 1 && !port.rendering);
        for (int i = 0; i < 20; i++) {
            lease.update(true, true, port); check(port.rendering && port.created == 1 && port.released == 0);
            lease.update(true, false, port); check(!port.rendering && port.created == 1 && port.released == 0);
        }
        lease.update(true, true, port); lease.update(false, true, port);
        check(!port.rendering && port.released == 0);
        lease.update(true, true, port); check(port.rendering && port.created == 1);
        int changes = port.changes; lease.update(true, true, port); check(port.changes == changes);
        lease.close(port); lease.close(port); check(port.released == 1 && !port.rendering);
        lease.update(true, true, port); check(port.created == 2 && port.rendering);
        lease = new MapDisplayLease(); port = new Fake(); port.accept = false;
        lease.update(true, true, port); lease.close(port); check(port.released == 0);
        port.accept = true; lease.update(true, true, port); check(port.created == 2 && port.rendering);
        System.out.println("MapDisplayLeaseTest: " + checks + " checks passed");
    }
}
