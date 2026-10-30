package com.local.diplay.clusterbridge.audio;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** No injection or live audio operations; only two audited OEM boot settings. */
public final class NativeBootVolumeTool {
    private static final String HELPER = "com.local.diplay.clusterbridge";
    private static final String CERT = "26e386a76a9272a40f84c3d64e608bb27ccd99956ee030d73fe086afbd106039";
    private static final File DIRECTORY = new File("/data/local/bridge-native-boot-volume");
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static int audit() throws Exception {
        if (android.os.Process.myUid() != 0 || !NativeBootVolumeController.supportedDevice())
            throw new IllegalStateException("Unsupported device");
        IBinder packages = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, packages);
        PackageInfo helper = (PackageInfo) Class.forName("android.content.pm.IPackageManager")
            .getMethod("getPackageInfo", String.class, int.class, int.class).invoke(pm, HELPER, 64, 0);
        if (helper == null || helper.applicationInfo == null || helper.signatures == null || helper.signatures.length != 1
                || !CERT.equals(hex(MessageDigest.getInstance("SHA-256").digest(helper.signatures[0].toByteArray()))))
            throw new IllegalStateException("Bridge identity");
        if (!new File(helper.applicationInfo.sourceDir).getCanonicalPath().equals(
                new File(System.getProperty("java.class.path", "")).getCanonicalPath()))
            throw new IllegalStateException("Installed helper required");
        Class<?> audio = Class.forName("android.media.AudioSystem");
        if (!NativeBootVolumeTransaction.MIN.equals(audio.getMethod("getMinOnVolumeName", int.class).invoke(null, 3))
                || !NativeBootVolumeTransaction.MAX.equals(audio.getMethod("getMaxOnVolumeName", int.class).invoke(null, 3)))
            throw new IllegalStateException("Unaudited OEM keys");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
        Object service = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object maximum = Class.forName("android.media.IAudioService").getMethod("getStreamMaxVolume", int.class).invoke(service, 3);
        if (!Integer.valueOf(30).equals(maximum)) throw new IllegalStateException("Unaudited volume scale");
        int user = (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        if (user != 0) throw new IllegalStateException("Primary user required");
        String boot = command("get", "global", "boot_count", null);
        return Integer.parseInt(boot);
    }
    private static String command(String operation, String table, String key, String value) throws Exception {
        boolean bootRead = "get".equals(operation) && "global".equals(table) && "boot_count".equals(key);
        if (!bootRead && (!"system".equals(table) || !(NativeBootVolumeTransaction.MIN.equals(key) || NativeBootVolumeTransaction.MAX.equals(key))))
            throw new IllegalArgumentException("Setting not permitted");
        if (!("get".equals(operation) || "put".equals(operation) || "delete".equals(operation)))
            throw new IllegalArgumentException("Operation not permitted");
        String[] args = value == null
            ? new String[]{"/system/bin/settings", "--user", "0", operation, table, key}
            : new String[]{"/system/bin/settings", "--user", "0", operation, table, key, value};
        Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!child.waitFor(3, TimeUnit.SECONDS)) { child.destroy(); throw new IOException("Settings timeout"); }
        byte[] bytes = read(child.getInputStream(), 1024);
        if (child.exitValue() != 0) throw new IOException("Settings rejected");
        String result = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!"get".equals(operation) && !result.isEmpty()
                && !("delete".equals(operation) && result.matches("Deleted [01] rows")))
            throw new IOException("Settings acknowledgement");
        return result;
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[512]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (out.size() + count > limit) throw new IOException("Output too large");
            out.write(bytes, 0, count);
        }
        return out.toByteArray();
    }
    private static void regular(File path) throws Exception {
        StructStat stat = Os.lstat(path.getPath());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 0 || stat.st_nlink != 1)
            throw new IOException("Unsafe journal file");
    }
    private static final class Backend implements NativeBootVolumeTransaction.Backend {
        private String get(String key) throws Exception {
            String value = command("get", "system", key, null); return "null".equals(value) ? null : value;
        }
        public NativeBootVolumeTransaction.Pair read() throws Exception {
            return new NativeBootVolumeTransaction.Pair(get(NativeBootVolumeTransaction.MIN), get(NativeBootVolumeTransaction.MAX));
        }
        private void put(String key, String value) throws Exception {
            command(value == null ? "delete" : "put", "system", key, value);
        }
        public void writeMin(String value) throws Exception { put(NativeBootVolumeTransaction.MIN, value); }
        public void writeMax(String value) throws Exception { put(NativeBootVolumeTransaction.MAX, value); }
    }
    private static final class Journal implements NativeBootVolumeTransaction.Journal {
        private final File path = new File(DIRECTORY, "ownership.json"), temporary = new File(DIRECTORY, "ownership.tmp");
        private static JSONObject pair(NativeBootVolumeTransaction.Pair value) throws Exception {
            return new JSONObject().put("min", value.min == null ? JSONObject.NULL : value.min)
                .put("max", value.max == null ? JSONObject.NULL : value.max);
        }
        private static NativeBootVolumeTransaction.Pair pair(JSONObject value) throws Exception {
            if (!value.has("min") || !value.has("max")) throw new IOException("Incomplete original settings");
            return new NativeBootVolumeTransaction.Pair(value.isNull("min") ? null : value.getString("min"),
                value.isNull("max") ? null : value.getString("max"));
        }
        private static Object owner(NativeBootVolumeTransaction.Owner value) throws Exception {
            return value == null ? JSONObject.NULL : new JSONObject().put("original", pair(value.original))
                .put("target", value.target).put("boot", value.boot);
        }
        private static NativeBootVolumeTransaction.Owner owner(JSONObject root, String key) throws Exception {
            if (!root.has(key)) throw new IOException("Incomplete ownership");
            if (root.isNull(key)) return null;
            JSONObject value = root.getJSONObject(key);
            return new NativeBootVolumeTransaction.Owner(pair(value.getJSONObject("original")), value.getInt("target"), value.getInt("boot"));
        }
        public NativeBootVolumeTransaction.Record load() throws Exception {
            if (!path.exists()) return null;
            regular(path);
            JSONObject value;
            try (InputStream input = new FileInputStream(path)) {
                value = new JSONObject(new String(read(input, 4096), StandardCharsets.UTF_8));
            }
            if (value.getInt("schema") != 1 || !CERT.equals(value.getString("certificate"))) throw new IOException("Journal identity");
            NativeBootVolumeTransaction.Owner old = owner(value, "owner");
            if (!value.getBoolean("pending")) {
                if (old == null) throw new IOException("Missing ownership");
                return new NativeBootVolumeTransaction.Record(old);
            }
            return new NativeBootVolumeTransaction.Record(old, pair(value.getJSONObject("before")), pair(value.getJSONObject("after")), owner(value, "next"));
        }
        public void save(NativeBootVolumeTransaction.Record record) throws Exception {
            if (path.exists()) regular(path);
            if (temporary.exists()) { regular(temporary); Os.remove(temporary.getPath()); }
            JSONObject value = new JSONObject().put("schema", 1).put("certificate", CERT)
                .put("owner", owner(record.owner)).put("pending", record.pending());
            if (record.pending()) value.put("before", pair(record.before)).put("after", pair(record.after)).put("next", owner(record.next));
            java.io.FileDescriptor fd = Os.open(temporary.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            try (FileOutputStream output = new FileOutputStream(fd)) {
                Os.fchmod(fd, 0600); output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            Os.rename(temporary.getPath(), path.getPath()); syncDirectory();
        }
        public void clear() throws Exception {
            if (path.exists()) { regular(path); Os.remove(path.getPath()); syncDirectory(); }
        }
        private void syncDirectory() throws Exception {
            java.io.FileDescriptor fd = Os.open(DIRECTORY.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            try {
                StructStat stat=Os.fstat(fd);
                if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid!=0) throw new IOException("Unsafe journal directory");
                Os.fsync(fd);
            } finally { Os.close(fd); }
        }
    }
    private static String response(NativeBootVolumeTransaction.Owner owner) {
        return owner == null ? "RESULT OFF" : "RESULT FIXED " + owner.target + " " + owner.boot;
    }
    private static String line(Reader input) throws Exception {
        StringBuilder out = new StringBuilder(); int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return out.toString();
            if (out.length() >= 64) throw new IOException("Request too long");
            out.append((char) value);
        }
        return null;
    }
    public static void main(String[] args) {
        try {
            int boot = audit();
            if (!DIRECTORY.exists() && !DIRECTORY.mkdir()) throw new IOException("Journal directory unavailable");
            StructStat stat = Os.lstat(DIRECTORY.getPath());
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 0) throw new IOException("Unsafe directory");
            Os.chmod(DIRECTORY.getPath(), 0700);
            File lockPath = new File(DIRECTORY, "owner.lock");
            if (lockPath.exists()) regular(lockPath);
            try (RandomAccessFile file = new RandomAccessFile(lockPath, "rw"); FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IOException("Native controller busy");
                Os.chmod(lockPath.getPath(), 0600);
                Journal journal = new Journal();
                NativeBootVolumeTransaction transaction = new NativeBootVolumeTransaction(new Backend(), journal);
                System.out.println("READY"); System.out.flush();
                Reader input = new InputStreamReader(System.in, StandardCharsets.US_ASCII); String request;
                for (int count = 0; count < 8 && (request = line(input)) != null; count++) {
                    if ("X".equals(request)) break;
                    String reply;
                    try {
                        if ("Q".equals(request)) reply = response(transaction.inspect());
                        else if ("R".equals(request)) { transaction.restore(); reply = "RESULT OFF"; }
                        else if (request.matches("F (?:[0-9]|[12][0-9]|30)")) reply = response(transaction.apply(Integer.parseInt(request.substring(2)), boot));
                        else reply = "ERR COMMAND";
                    } catch (NativeBootVolumeTransaction.Conflict error) { reply = "ERR CONFLICT"; }
                    catch (NativeBootVolumeTransaction.Pending error) { reply = "ERR PENDING"; }
                    catch (NativeBootVolumeTransaction.WriteFailed error) { reply = "ERR WRITE"; }
                    catch (Exception error) { reply = "ERR UNAVAILABLE"; }
                    System.out.println(reply); System.out.flush();
                }
            }
        } catch (Exception error) { System.out.println("ERR UNAVAILABLE"); System.out.flush(); }
    }
}
