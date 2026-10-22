package com.local.diplay.clusterbridge;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Existing-root helper: five instrument display properties only, with an independent six-second lease. */
public final class CallDisplayTool {
    private final Object activity;
    private final Method peek;
    private Object property;
    private final Class<?> api, valueClass;
    private final Map<Integer, Object> last = new HashMap<>();
    private boolean owned;
    private int sourcePid = -1;
    private long heartbeat = SystemClock.elapsedRealtime();

    private CallDisplayTool() throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT < 26 || android.os.Build.VERSION.SDK_INT > 27)
            throw new IllegalStateException("Unsupported helper environment");
        activity = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        peek = Class.forName("android.app.IActivityManager").getMethod("peekService", Intent.class, String.class, String.class);
        api = Class.forName("android.car.hardware.property.ICarProperty"); valueClass = Class.forName("android.car.hardware.CarPropertyValue");
        connect(); nativePhoneConnected();
    }
    private IBinder peek(Intent intent) throws Exception { return (IBinder)peek.invoke(activity, intent, null, "com.android.shell"); }
    private void connect() throws Exception {
        IBinder binder = peek(new Intent("android.car.ICar").setPackage("com.android.car"));
        if (binder == null) throw new IllegalStateException("Car service unavailable");
        Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        IBinder vendor = (IBinder)Class.forName("android.car.ICar").getMethod("getCarService", String.class).invoke(car, "vendor_instrument_cluster");
        property = Class.forName("android.car.hardware.property.ICarProperty$Stub").getMethod("asInterface", IBinder.class).invoke(null, vendor);
        List<?> configs = (List<?>)api.getMethod("getPropertyList").invoke(property);
        for (int expected : CallDisplayProtocol.IDS) {
            boolean found = false;
            for (Object config : configs) {
                Class<?> c = config.getClass();
                if ((Integer)c.getMethod("getPropertyId").invoke(config) == expected
                    && c.getMethod("getPropertyType").invoke(config) == (expected == CallDisplayProtocol.CALLER ? String.class : Integer.class)) found = true;
            }
            if (!found) throw new IllegalStateException("Phone display property unavailable");
        }
    }
    private boolean nativePhoneConnected() throws Exception {
        IBinder binder = peek(new Intent("android.bluetooth.IBluetoothHeadsetClient").setComponent(
            new ComponentName("com.android.bluetooth", "com.android.bluetooth.hfpclient.HeadsetClientService")));
        if (binder == null) return false;
        Object hfp = Class.forName("android.bluetooth.IBluetoothHeadsetClient$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        List<?> devices = (List<?>)Class.forName("android.bluetooth.IBluetoothHeadsetClient").getMethod("getConnectedDevices").invoke(hfp);
        return !devices.isEmpty();
    }
    private boolean sourceAlive(int pid) {
        try {
            String command = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/cmdline")), StandardCharsets.UTF_8).split("\u0000", 2)[0];
            return command.equals("com.shihab.diplay") || command.startsWith("com.shihab.diplay:");
        } catch (Exception e) { return false; }
    }
    private void set(int id, Object value, boolean force) throws Exception {
        boolean allowed = false; for (int expected : CallDisplayProtocol.IDS) if (expected == id) allowed = true;
        if (!allowed) throw new IllegalArgumentException("Unsupported display property");
        if (!force && value.equals(last.get(id))) return;
        Object v = valueClass.getConstructor(int.class, int.class, Object.class).newInstance(id, 0, value);
        api.getMethod("setProperty", valueClass).invoke(property, v); last.put(id, value);
    }
    private synchronized String command(String line) throws Exception {
        heartbeat = SystemClock.elapsedRealtime();
        if (line.equals("P")) return "OK";
        if (line.equals("E")) { clear(false); return "OK"; }
        if (line.equals("R")) { owned = true; clear(true); return "OK"; }
        if (line.equals("Q")) { clear(false); return "OK"; }
        String[] fields = line.split("\t", -1);
        if (fields.length != 6 || !fields[0].equals("C")) throw new IllegalArgumentException("Invalid display command");
        int status = Integer.parseInt(fields[1]), seconds = Integer.parseInt(fields[2]), pid = Integer.parseInt(fields[5]);
        int comm = CallDisplayProtocol.communication(status);
        if (seconds < 0 || seconds > 604800 || pid <= 0 || !sourceAlive(pid)) throw new IllegalArgumentException("Inactive source");
        String name = decode(fields[3]), number = decode(fields[4]);
        if (nativePhoneConnected()) { owned = false; last.clear(); return "HANDOFF"; }
        // Ownership is established before the first write so partial failures also get cleared.
        owned = true; sourcePid = pid;
        set(CallDisplayProtocol.BT, 1, false); set(CallDisplayProtocol.DEVICE, 0, false);
        set(CallDisplayProtocol.CALLER, CallDisplayProtocol.caller(status, number, name), false);
        set(CallDisplayProtocol.DURATION, seconds, false); set(CallDisplayProtocol.COMM, comm, false);
        return "OK";
    }
    private static String decode(String value) {
        if (value.length() > 1024) throw new IllegalArgumentException("Display text too long");
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private synchronized void clear(boolean force) throws Exception {
        if (!owned) return;
        // The native dialer owns the display as soon as HFP reconnects; never clear its call card.
        if (nativePhoneConnected()) { owned = false; last.clear(); sourcePid = -1; return; }
        set(CallDisplayProtocol.COMM, 7, true);
        set(CallDisplayProtocol.DURATION, 0, true);
        set(CallDisplayProtocol.CALLER, CallDisplayProtocol.caller(0, "", ""), true);
        set(CallDisplayProtocol.BT, 2, true);
        owned = false; sourcePid = -1; last.clear();
    }
    private synchronized void watch() throws Exception {
        if (owned && (nativePhoneConnected() || !sourceAlive(sourcePid))) clear(false);
        if (SystemClock.elapsedRealtime() - heartbeat > 6000) { clear(false); System.exit(0); }
    }
    public static void main(String[] args) {
        CallDisplayTool tool = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Arguments unsupported");
            tool = new CallDisplayTool(); final CallDisplayTool owner = tool;
            Thread guard = new Thread(() -> {
                for (;;) {
                    try { Thread.sleep(1000); owner.watch(); }
                    catch (Throwable e) { if (!owner.cleanup()) { System.out.println("ERR cleanup"); System.out.flush(); } System.exit(2); }
                }
            }, "call-display-lease"); guard.setDaemon(true); guard.start();
            System.out.println("READY"); System.out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
            String line;
            while ((line = boundedLine(reader)) != null) {
                try { System.out.println(tool.command(line)); }
                catch (Throwable e) { tool.cleanup(); System.out.println("ERR display"); }
                System.out.flush();
                if (line.equals("Q")) break;
            }
        } catch (Throwable e) { System.out.println("ERR unavailable"); System.out.flush(); }
        finally { if (tool != null) tool.cleanup(); }
    }
    private boolean cleanup() {
        for (int i = 0; i < 3; i++) {
            try { clear(true); return true; }
            catch (Exception e) { try { connect(); } catch (Exception ignored) { } }
        }
        return false;
    }
    private static String boundedLine(BufferedReader reader) throws Exception {
        StringBuilder line = new StringBuilder(); int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return line.toString();
            if (c == '\r') continue;
            if (line.length() >= 2048) throw new IllegalArgumentException("Display input too long");
            line.append((char)c);
        }
        return null;
    }
}
