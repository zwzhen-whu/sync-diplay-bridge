package com.local.diplay.clusterbridge.audio;

import java.util.Objects;

/** Two fixed OEM boot keys. Durable ownership and undo journal precede every write. */
public final class NativeBootVolumeTransaction {
    public static final String MIN = "on_volume_min_STREAM_MUSIC", MAX = "on_volume_max_STREAM_MUSIC";
    public static final int MAXIMUM = 30;

    public static final class Pair {
        public final String min, max;
        public Pair(String min, String max) { validate(min); validate(max); this.min = min; this.max = max; }
        public static Pair fixed(int target) {
            level(target); return new Pair(Integer.toString(target), Integer.toString(target));
        }
        private static void validate(String value) {
            if (value != null && (!value.matches("[0-9]{1,2}") || Integer.parseInt(value) > MAXIMUM))
                throw new IllegalArgumentException("Unsupported OEM value");
        }
        public boolean equals(Object other) {
            if (!(other instanceof Pair)) return false;
            Pair p = (Pair) other; return Objects.equals(min, p.min) && Objects.equals(max, p.max);
        }
        public int hashCode() { return Objects.hash(min, max); }
    }
    public static final class Owner {
        public final Pair original;
        public final int target, boot;
        public Owner(Pair original, int target, int boot) {
            level(target); if (original == null || boot < 0) throw new IllegalArgumentException("Ownership");
            this.original = original; this.target = target; this.boot = boot;
        }
        public boolean equals(Object other) {
            if (!(other instanceof Owner)) return false;
            Owner o = (Owner) other; return original.equals(o.original) && target == o.target && boot == o.boot;
        }
        public int hashCode() { return Objects.hash(original, target, boot); }
    }
    public static final class Record {
        public final Owner owner, next;
        public final Pair before, after;
        public Record(Owner owner) { this(owner, null, null, null); }
        public Record(Owner owner, Pair before, Pair after, Owner next) {
            if ((before == null) != (after == null)) throw new IllegalArgumentException("Incomplete journal");
            this.owner = owner; this.before = before; this.after = after; this.next = next;
        }
        public boolean pending() { return before != null; }
    }
    public interface Backend {
        Pair read() throws Exception;
        void writeMin(String value) throws Exception;
        void writeMax(String value) throws Exception;
    }
    public interface Journal {
        Record load() throws Exception;
        void save(Record value) throws Exception;
        void clear() throws Exception;
    }
    public static class Conflict extends Exception { public Conflict() { super("OEM setting changed outside bridge"); } }
    public static class Pending extends Exception { public Pending(Throwable cause) { super("Undo pending", cause); } }
    public static class WriteFailed extends Exception { public WriteFailed(Throwable cause) { super("Write failed; previous settings restored", cause); } }
    private final Backend backend;
    private final Journal journal;
    public NativeBootVolumeTransaction(Backend backend, Journal journal) { this.backend = backend; this.journal = journal; }
    private static void level(int value) {
        if (value < 0 || value > MAXIMUM) throw new IllegalArgumentException("Target out of range");
    }
    private static boolean either(String value, String before, String after) {
        return Objects.equals(value, before) || Objects.equals(value, after);
    }
    private void finish(Owner owner) throws Exception {
        if (owner == null) journal.clear(); else journal.save(new Record(owner));
    }
    private Pair ownedValues(Pair target, Pair alternative) throws Exception {
        Pair current = backend.read();
        if (!either(current.min, target.min, alternative.min) || !either(current.max, target.max, alternative.max))
            throw new Conflict();
        return current;
    }
    private void replace(Pair target, Pair alternative) throws Exception {
        Pair current = ownedValues(target, alternative);
        if (!Objects.equals(current.min, target.min)) backend.writeMin(target.min);
        if (!Objects.equals(ownedValues(target, alternative).max, target.max)) backend.writeMax(target.max);
        if (!ownedValues(target, alternative).equals(target)) throw new IllegalStateException("OEM readback mismatch");
    }
    /** After interrupted writes, undo only values belonging to this recorded operation. */
    private void recover() throws Exception {
        Record record = journal.load();
        if (record == null || !record.pending()) return;
        Pair current = backend.read();
        if (!either(current.min, record.before.min, record.after.min)
                || !either(current.max, record.before.max, record.after.max)) throw new Conflict();
        replace(record.before, record.after);
        finish(record.owner);
    }
    public Owner inspect() throws Exception {
        recover(); Record record = journal.load();
        Owner owner = record == null ? null : record.owner;
        if (owner != null && !backend.read().equals(Pair.fixed(owner.target))) throw new Conflict();
        return owner;
    }
    public Owner apply(int target, int boot) throws Exception {
        level(target); if (boot < 0) throw new IllegalArgumentException("Boot unavailable");
        Owner old = inspect();
        if (old != null && old.target == target) return old;
        Pair before = backend.read();
        Owner next = new Owner(old == null ? before : old.original, target, boot);
        change(old, before, Pair.fixed(target), next); return next;
    }
    public void restore() throws Exception {
        Owner old = inspect();
        if (old != null) change(old, Pair.fixed(old.target), old.original, null);
    }
    private void change(Owner old, Pair before, Pair after, Owner next) throws Exception {
        journal.save(new Record(old, before, after, next));
        try {
            // Check again after saving the journal, before mutating settings.
            if (!backend.read().equals(before)) throw new Conflict();
            replace(after, before); finish(next);
        } catch (Exception error) {
            try {
                Record observed = journal.load();
                // A journal acknowledgement may fail after the commit reached storage.
                if ((observed == null && next == null || observed != null && !observed.pending()
                        && Objects.equals(observed.owner, next)) && backend.read().equals(after)) return;
                recover();
            } catch (Exception undo) { throw new Pending(undo); }
            throw new WriteFailed(error);
        }
    }

    /** A confirmed OEM-owned startup must never be followed by a late second restore. */
    public static boolean skipLateRestore(int mode, boolean owned, int configured, int level, int maximum) {
        return mode == MediaVolumeBootPolicy.FIXED && owned && maximum == MAXIMUM && level == configured
            && configured >= 0 && configured <= MAXIMUM;
    }
}
