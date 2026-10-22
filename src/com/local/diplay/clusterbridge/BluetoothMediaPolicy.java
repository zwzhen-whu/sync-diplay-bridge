package com.local.diplay.clusterbridge;

/** Reversible per-profile policy shared by media and the optional call connection. */
public final class BluetoothMediaPolicy {
    public static final int OFF = 0, ON = 100;
    public static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2;
    public static final long DISCONNECT_GRACE_MS = 3000, RETRY_MS = 5000;
    public static final int MAX_ATTEMPTS = 3;

    public static final class Snapshot {
        public final String address;
        public final int priority;
        public final boolean connected;
        public Snapshot(String address, int priority, boolean connected) {
            this.address = address; this.priority = priority; this.connected = connected;
        }
    }

    public interface Store {
        Snapshot load();
        boolean save(Snapshot snapshot);
        boolean clear();
    }

    public interface Media {
        boolean bonded(String address) throws Exception;
        int priority(String address) throws Exception;
        int state(String address) throws Exception;
        boolean priority(String address, int value) throws Exception;
        boolean disconnect(String address) throws Exception;
        boolean connect(String address) throws Exception;
    }

    public enum Result {
        DISABLED, WAIT_CONNECTION, WAIT_DISCONNECT, NEED_DEVICE, DEVICE_REMOVED,
        ALREADY_OFF, SUSPENDED, WAIT_APPLY, APPLY_FAILED, ATTEMPTS_EXHAUSTED,
        SAVE_FAILED, RESTORED, RESTORE_FAILED, UNKNOWN_PRIORITY
    }

    private final Store store;
    private final Media media;
    private Boolean connected;
    private long disconnectedAt = -1, attemptedAt = -1;
    private int attempts;

    public BluetoothMediaPolicy(Store store, Media media) { this.store = store; this.media = media; }

    /** USB presence: null means unavailable data, never a confirmed unplug. */
    public void connection(Boolean value, long now) {
        if (Boolean.FALSE.equals(value)) {
            if (!Boolean.FALSE.equals(connected) || disconnectedAt < 0 || now < disconnectedAt) disconnectedAt = now;
        } else disconnectedAt = -1;
        connected = value;
    }

    public Result tick(boolean enabled, String target, long now) throws Exception {
        if (!enabled) return store.load() == null ? Result.DISABLED : restore();
        if (connected == null) return Result.WAIT_CONNECTION;
        if (!connected) {
            if (now < disconnectedAt || now - disconnectedAt < DISCONNECT_GRACE_MS) return Result.WAIT_DISCONNECT;
            return store.load() == null ? Result.WAIT_CONNECTION : restore();
        }
        Snapshot saved = store.load();
        if (saved != null && target != null && !saved.address.equals(target)) return restore();
        if (target == null || target.isEmpty()) return Result.NEED_DEVICE;
        if (!media.bonded(target)) return saved == null ? Result.NEED_DEVICE : restore();

        int priority = media.priority(target), state = media.state(target);
        // Undefined priorities cannot be faithfully restored through this API.
        if (priority != OFF && priority != ON && priority != 1000) return Result.UNKNOWN_PRIORITY;
        if (saved == null) {
            if (priority == OFF && state == DISCONNECTED) return Result.ALREADY_OFF;
            saved = new Snapshot(target, priority, state == CONNECTED);
            // A crash immediately after the next write must retain the original setting.
            if (!store.save(saved)) return Result.SAVE_FAILED;
        }
        if (priority == OFF && state == DISCONNECTED) return Result.SUSPENDED;
        if (attemptedAt >= 0 && now >= attemptedAt && now - attemptedAt < RETRY_MS) return Result.WAIT_APPLY;
        if (attempts >= MAX_ATTEMPTS) return Result.ATTEMPTS_EXHAUSTED;
        attemptedAt = now; attempts++;
        // Match the head unit's DeviceProfilesSettings: disconnect first, then
        // setPreferred(false). Writing OFF before disconnect can be overwritten.
        boolean accepted = true;
        if (state == CONNECTED || state == CONNECTING) accepted = media.disconnect(target);
        if (media.priority(target) != OFF && !media.priority(target, OFF)) return Result.APPLY_FAILED;
        if (media.priority(target) != OFF) return Result.APPLY_FAILED;
        if (!accepted && media.state(target) != DISCONNECTED) return Result.APPLY_FAILED;
        // An accepted asynchronous request is not proof of disconnection.
        return Result.WAIT_APPLY;
    }

    /** Also used on an orderly stop, regardless of the latest CarPlay signal. */
    public Result restore() throws Exception {
        Snapshot saved = store.load();
        if (saved == null) return Result.DISABLED;
        if (!media.bonded(saved.address)) {
            if (!store.clear()) return Result.SAVE_FAILED;
            resetAttempts(); return Result.DEVICE_REMOVED;
        }
        int current = media.priority(saved.address);
        if (current != OFF && current != ON && current != 1000) return Result.UNKNOWN_PRIORITY;
        // Respect a setting already changed by the user or another application.
        if (current == OFF && saved.priority > OFF) {
            // The framework accepts only 0/100. 1000 also represents enabled.
            if (!media.priority(saved.address, ON) || media.priority(saved.address) <= OFF) return Result.RESTORE_FAILED;
        }
        if (!store.clear()) return Result.SAVE_FAILED;
        resetAttempts();
        if (saved.priority > OFF && saved.connected) {
            // Restore the old connection once; never send PLAY or claim that it connected.
            try { if (media.state(saved.address) == DISCONNECTED) media.connect(saved.address); }
            catch (Exception ignored) { /* The setting is already restored. */ }
        }
        return Result.RESTORED;
    }

    private void resetAttempts() { attempts = 0; attemptedAt = -1; }
}
