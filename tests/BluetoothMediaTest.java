package com.local.diplay.clusterbridge;

import java.util.ArrayList;
import java.util.List;
import static com.local.diplay.clusterbridge.BluetoothMediaPolicy.*;

/** Offline fault and lifecycle regressions, without any vehicle-side mutations. */
public final class BluetoothMediaTest {
    private static int assertions;
    private static void eq(Object actual, Object expected) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
    private static final class Fake implements Store, Media {
        Snapshot saved;
        int priority = ON, state = CONNECTED;
        boolean bonded = true, saveOk = true, clearOk = true, setOk = true, disconnectOk = true, connectOk = true;
        boolean synchronous = true;
        boolean resetPriorityOnDisconnect;
        final List<String> events = new ArrayList<>();
        public Snapshot load() { return saved; }
        public boolean save(Snapshot value) { events.add("save"); if (saveOk) saved = value; return saveOk; }
        public boolean clear() { events.add("clear"); if (clearOk) saved = null; return clearOk; }
        public boolean bonded(String address) { return bonded; }
        public int priority(String address) { return priority; }
        public int state(String address) { return state; }
        public boolean priority(String address, int value) {
            events.add("priority:" + address + ":" + value); if (setOk) priority = value; return setOk;
        }
        public boolean disconnect(String address) {
            events.add("disconnect:" + address);
            if (disconnectOk && synchronous) state = DISCONNECTED;
            if (resetPriorityOnDisconnect) priority = ON;
            return disconnectOk;
        }
        public boolean connect(String address) { events.add("connect:" + address); if (connectOk) state = CONNECTED; return connectOk; }
    }
    private static BluetoothMediaPolicy policy(Fake fake) { return new BluetoothMediaPolicy(fake, fake); }

    public static void main(String[] args) throws Exception {
        // Current head unit: Apple 05ac, product name iPhone. Other USB devices must not trigger.
        eq(IphoneUsbPresence.device(0x05ac, "iPhone"), IphoneUsbPresence.State.PRESENT);
        eq(IphoneUsbPresence.device(0x05ac, " iPhone 17 Pro "), IphoneUsbPresence.State.PRESENT);
        eq(IphoneUsbPresence.device(0x05ac, "iPad"), IphoneUsbPresence.State.ABSENT);
        eq(IphoneUsbPresence.device(0x05ac, "Apple Watch"), IphoneUsbPresence.State.ABSENT);
        eq(IphoneUsbPresence.device(0x1234, "iPhone"), IphoneUsbPresence.State.ABSENT);
        eq(IphoneUsbPresence.device(0x05ac, null), IphoneUsbPresence.State.UNKNOWN);
        eq(IphoneUsbPresence.inventory(), IphoneUsbPresence.State.ABSENT);
        eq(IphoneUsbPresence.inventory((IphoneUsbPresence.State[]) null), IphoneUsbPresence.State.UNKNOWN);
        eq(IphoneUsbPresence.inventory(IphoneUsbPresence.State.ABSENT, IphoneUsbPresence.State.PRESENT), IphoneUsbPresence.State.PRESENT);
        eq(IphoneUsbPresence.inventory(IphoneUsbPresence.State.UNKNOWN), IphoneUsbPresence.State.UNKNOWN);
        eq(IphoneUsbPresence.inventory(IphoneUsbPresence.State.PRESENT, IphoneUsbPresence.State.PRESENT), IphoneUsbPresence.State.UNKNOWN);
        Fake f = new Fake(); BluetoothMediaPolicy p = policy(f);
        eq(p.tick(true, "phone", 0), Result.WAIT_CONNECTION); eq(f.events.size(), 0);
        p.connection(false, 0); eq(p.tick(true, "phone", 2999), Result.WAIT_DISCONNECT); eq(f.events.size(), 0);
        eq(p.tick(true, "phone", 3000), Result.WAIT_CONNECTION); eq(f.events.size(), 0);
        p.connection(true, 4000); eq(p.tick(true, null, 4000), Result.NEED_DEVICE); eq(f.events.size(), 0);
        eq(p.tick(false, "phone", 4000), Result.DISABLED); eq(f.events.size(), 0);

        // Save first, disable only the selected A2DP sink, and wait for actual state.
        eq(p.tick(true, "phone", 4000), Result.WAIT_APPLY);
        eq(f.events.toString(), "[save, disconnect:phone, priority:phone:0]");
        // The optional call profile owns a separate journal and restores independently.
        Fake music = new Fake(), calls = new Fake();
        BluetoothMediaPolicy musicPolicy = policy(music), callPolicy = policy(calls);
        musicPolicy.connection(true, 0); callPolicy.connection(true, 0);
        musicPolicy.tick(true, "phone", 0);
        eq(callPolicy.tick(false, "phone", 0), Result.DISABLED); eq(calls.events.size(), 0);
        eq(calls.priority, ON); eq(music.priority, OFF);
        callPolicy.tick(true, "phone", 1000); eq(calls.priority, OFF); eq(music.priority, OFF);
        eq(callPolicy.tick(false, "phone", 3000), Result.RESTORED);
        eq(calls.priority, ON); eq(music.priority, OFF);
        musicPolicy.tick(false, "phone", 4000); eq(music.priority, ON);
        eq(f.saved.priority, ON); eq(f.saved.connected, true); eq(f.priority, OFF);
        eq(p.tick(true, "phone", 6000), Result.SUSPENDED); eq(f.events.size(), 3);
        p.connection(false, 7000); eq(p.tick(true, "phone", 9999), Result.WAIT_DISCONNECT);
        p.connection(false, 9000); // Further disconnected callbacks do not extend the grace period.
        eq(p.tick(true, "phone", 10000), Result.RESTORED);
        eq(f.priority, ON); eq(f.state, CONNECTED); eq(f.saved == null, true);
        eq(f.events.toString(), "[save, disconnect:phone, priority:phone:0, priority:phone:100, clear, connect:phone]");

        // A brief USB re-enumeration must not restore media while the phone remains attached.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        p.connection(false, 1000); p.tick(true, "phone", 3000); p.connection(true, 3500);
        eq(p.tick(true, "phone", 6000), Result.SUSPENDED); eq(f.priority, OFF);
        p.connection(null, 7000); eq(p.tick(true, "phone", 20000), Result.WAIT_CONNECTION); eq(f.priority, OFF);
        eq(p.tick(false, "phone", 21000), Result.RESTORED); eq(f.priority, ON);

        // Do not enable a media profile that was already off before inserting the phone.
        f = new Fake(); f.priority = OFF; f.state = DISCONNECTED; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.ALREADY_OFF); eq(f.events.size(), 0);
        p.connection(false, 1000); p.tick(true, "phone", 4000); eq(f.priority, OFF); eq(f.events.size(), 0);
        // An inconsistent originally-OFF but connected profile can be disconnected without enabling it later.
        f = new Fake(); f.priority = OFF; p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        p.restore(); eq(f.priority, OFF); eq(f.events.toString(), "[save, disconnect:phone, clear]");

        // Media enabled but not connected before USB insertion: restore without initiating a new connection.
        f = new Fake(); f.state = DISCONNECTED; p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        p.restore(); eq(f.priority, ON); eq(f.state, DISCONNECTED);
        eq(f.events.toString(), "[save, priority:phone:0, priority:phone:100, clear]");

        // A process restart retains the snapshot, waits for real connection data, then restores it.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        p = policy(f); eq(p.tick(true, "phone", 2000), Result.WAIT_CONNECTION); eq(f.priority, OFF);
        p.connection(false, 4000); eq(p.tick(true, "phone", 7000), Result.RESTORED); eq(f.priority, ON);
        // A journal saved before a crash but before any mutation must not cause an unnecessary reconnect.
        f = new Fake(); f.saved = new Snapshot("phone", ON, true); p = policy(f);
        eq(p.restore(), Result.RESTORED); eq(f.events.toString(), "[clear]");

        // Never change the profile if saving the original setting failed.
        f = new Fake(); f.saveOk = false; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.SAVE_FAILED); eq(f.events.toString(), "[save]"); eq(f.priority, ON);
        // Failed preference writes are bounded; original settings remain journaled.
        f = new Fake(); f.setOk = false; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.APPLY_FAILED);
        eq(p.tick(true, "phone", 4999), Result.WAIT_APPLY); eq(f.events.size(), 3);
        eq(p.tick(true, "phone", 5000), Result.APPLY_FAILED);
        eq(p.tick(true, "phone", 10000), Result.APPLY_FAILED);
        eq(p.tick(true, "phone", 15000), Result.ATTEMPTS_EXHAUSTED); eq(f.events.size(), 5);
        // Accepted asynchronous disconnect must not be called confirmed until state actually changes.
        f = new Fake(); f.synchronous = false; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.WAIT_APPLY); eq(p.tick(true, "phone", 2000), Result.WAIT_APPLY);
        f.state = DISCONNECTED; eq(p.tick(true, "phone", 4000), Result.SUSPENDED);

        // Failed restore retains its record for a later successful attempt.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0); f.setOk = false;
        eq(p.restore(), Result.RESTORE_FAILED); eq(f.saved != null, true); eq(f.priority, OFF);
        f.setOk = true; eq(p.restore(), Result.RESTORED); eq(f.saved == null, true);
        // Manual changes made before restoration take precedence.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0); f.priority = 1000;
        eq(p.restore(), Result.RESTORED); eq(f.priority, 1000); eq(f.events.toString(), "[save, disconnect:phone, priority:phone:0, clear, connect:phone]");
        // API only accepts OFF/ON, and must restore the enabled state of AUTO_CONNECT too.
        f = new Fake(); f.priority = 1000; p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        eq(p.restore(), Result.RESTORED); eq(f.priority, ON);

        // A target change restores the old phone before touching the new one.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0);
        eq(p.tick(true, "other", 6000), Result.RESTORED);
        eq(f.events.toString().contains("other"), false); eq(f.priority, ON);
        f = new Fake(); f.bonded = false; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.NEED_DEVICE); eq(f.events.size(), 0);
        f.saved = new Snapshot("phone", ON, true); eq(p.restore(), Result.DEVICE_REMOVED); eq(f.events.toString(), "[clear]");
        f = new Fake(); f.priority = -1; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.UNKNOWN_PRIORITY); eq(f.events.size(), 0);

        // A failed clear after restoration cannot repeat the reconnection on retry.
        f = new Fake(); p = policy(f); p.connection(true, 0); p.tick(true, "phone", 0); f.clearOk = false;
        eq(p.restore(), Result.SAVE_FAILED); eq(f.priority, ON); eq(f.saved != null, true);
        f.clearOk = true; eq(p.restore(), Result.RESTORED); eq(f.state, CONNECTED);
        // Firmware can update the preference during disconnect: write OFF afterwards.
        f = new Fake(); f.resetPriorityOnDisconnect = true; p = policy(f); p.connection(true, 0);
        eq(p.tick(true, "phone", 0), Result.WAIT_APPLY); eq(f.priority, OFF);
        eq(p.tick(true, "phone", 2000), Result.SUSPENDED);
        eq(f.events.toString(), "[save, disconnect:phone, priority:phone:0]");
        System.out.println("BluetoothMediaTest: " + assertions + " assertions passed (offline only)");
    }
}
