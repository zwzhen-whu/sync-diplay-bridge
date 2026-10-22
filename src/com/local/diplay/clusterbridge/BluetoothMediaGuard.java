package com.local.diplay.clusterbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android 8.x media / optional call profile control with ordinary Bluetooth permissions. */
final class BluetoothMediaGuard {
    static final String ENABLED = "iphone_usb_bluetooth_media", TARGET = "iphone_usb_bluetooth_device";
    static final String CALLS = "iphone_usb_bluetooth_calls";
    private static final Object OPERATIONS = new Object();
    private final boolean calls;
    private final int profileId;
    private final String binderInterface, settingPrefix;
    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final BluetoothAdapter adapter;
    private final Journal journal;
    private final BluetoothMediaPolicy policy;
    private final Sink backend;
    private volatile BluetoothProfile profile;
    private volatile boolean ready, closed;
    private boolean binding;
    private volatile String status = "关闭";
    private long lastTaskAt = -1;
    private boolean usbReceiverRegistered;

    BluetoothMediaGuard(Context context, SharedPreferences preferences) {
        this(context, preferences, false);
    }

    BluetoothMediaGuard(Context context, SharedPreferences preferences, boolean calls) {
        this.context = context; this.preferences = preferences;
        this.calls = calls; profileId = calls ? 16 : 11;
        binderInterface = calls ? "android.bluetooth.IBluetoothHeadsetClient" : "android.bluetooth.IBluetoothA2dpSink";
        settingPrefix = calls ? "bluetooth_headset_priority_" : "bluetooth_a2dp_src_priority_";
        adapter = supported() ? BluetoothAdapter.getDefaultAdapter() : null;
        journal = new Journal(preferences, calls);
        backend = new Sink(); policy = new BluetoothMediaPolicy(journal, backend);
        if (supported()) {
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(calls ? "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
                : "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            try { context.registerReceiver(usbChanges, filter); usbReceiverRegistered = true; }
            catch (RuntimeException e) { Log.w("DiPlayBridge", "USB events unavailable; using inventory checks"); }
        }
    }

    static boolean supported() { return Build.VERSION.SDK_INT == 26 || Build.VERSION.SDK_INT == 27; }

    static List<BluetoothDevice> devices() {
        List<BluetoothDevice> result = new ArrayList<>();
        if (!supported()) return result;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return result;
        try { Set<BluetoothDevice> bonded = adapter.getBondedDevices(); if (bonded != null) result.addAll(bonded); }
        catch (RuntimeException e) { return result; }
        Collections.sort(result, Comparator.comparing(BluetoothMediaGuard::deviceLabel));
        return result;
    }

    static String deviceLabel(BluetoothDevice device) {
        String name = device.getName(), address = device.getAddress();
        return (name == null || name.trim().isEmpty() ? "已配对设备" : name) + "（" + address.substring(12) + "）";
    }

    private final BroadcastReceiver usbChanges = new BroadcastReceiver() {
        public void onReceive(Context ignored, Intent intent) {
            // Use the actual inventory, never trust a broadcast's supplied device.
            lastTaskAt = -1; tick();
        }
    };

    private Boolean iphoneInserted() {
        try {
            UsbManager usb = context.getSystemService(UsbManager.class);
            if (usb == null) return null;
            Map<String, UsbDevice> inventory = usb.getDeviceList();
            if (inventory == null) return null;
            List<IphoneUsbPresence.State> devices = new ArrayList<>();
            for (UsbDevice device : inventory.values())
                devices.add(IphoneUsbPresence.device(device.getVendorId(), device.getProductName()));
            IphoneUsbPresence.State state = IphoneUsbPresence.inventory(devices.toArray(new IphoneUsbPresence.State[0]));
            return state == IphoneUsbPresence.State.UNKNOWN ? null : state == IphoneUsbPresence.State.PRESENT;
        } catch (RuntimeException e) { return null; }
    }

    /** Called by the existing watchdog. At most one Bluetooth task, every two seconds. */
    void tick() {
        if (closed) return;
        boolean enabled = enabled(), pending = journal.exists();
        if (!enabled && !pending) { status = calls ? "保持原通话设置" : "关闭"; return; }
        if (!supported()) { status = "当前仅支持已适配的 Android 8.0/8.1 蓝牙接口"; return; }
        if (context.checkSelfPermission("android.permission.BLUETOOTH") != PackageManager.PERMISSION_GRANTED
            || context.checkSelfPermission("android.permission.BLUETOOTH_ADMIN") != PackageManager.PERMISSION_GRANTED) {
            status = "蓝牙权限未授予，请检查应用权限"; return;
        }
        if (adapter == null) { status = "车机没有可用的蓝牙适配器"; return; }
        if (!adapter.isEnabled()) { status = pending ? "蓝牙已关闭，保留恢复记录，等待蓝牙开启" : "等待车机蓝牙开启"; return; }
        if (!binding) {
            binding = true;
            try {
                if (!adapter.getProfileProxy(context, listener, profileId)) {
                    status = "系统未提供蓝牙媒体接收接口，请手动设置"; return;
                }
            } catch (RuntimeException e) { status = "系统不允许访问蓝牙媒体接口：" + e.getClass().getSimpleName(); return; }
        }
        if (!ready || profile == null) { status = "等待系统蓝牙媒体接口就绪"; return; }
        long now = SystemClock.elapsedRealtime();
        if (lastTaskAt >= 0 && now >= lastTaskAt && now - lastTaskAt < 2000) return;
        if (!busy.compareAndSet(false, true)) return;
        lastTaskAt = now;
        worker.execute(() -> {
            try {
                synchronized (OPERATIONS) {
                    if (closed) return;
                    Boolean inserted = iphoneInserted();
                    policy.connection(inserted, SystemClock.elapsedRealtime());
                    String target = preferences.getString(TARGET, "");
                    if (target.isEmpty()) {
                        BluetoothMediaPolicy.Snapshot saved = journal.load();
                        List<BluetoothDevice> paired = devices();
                        target = saved != null ? saved.address : paired.size() == 1 ? paired.get(0).getAddress() : null;
                    }
                    if (calls && enabled() && Boolean.TRUE.equals(inserted) && target != null && !backend.callsAreIdle(target)) {
                        status = "正在通话或无法确认空闲，结束后再关闭蓝牙通话连接"; return;
                    }
                    status = describe(policy.tick(enabled(), target, SystemClock.elapsedRealtime()));
                }
            } catch (Exception e) {
                status = "蓝牙媒体操作未完成" + (journal.exists() ? "，恢复记录保留" : "") + "：" + cause(e).getClass().getSimpleName();
                Log.w("DiPlayBridge", "Bluetooth media operation unavailable: " + cause(e).getClass().getSimpleName());
            } finally { busy.set(false); }
        });
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int id, BluetoothProfile proxy) {
            if (id != profileId) return;
            if (closed) { adapter.closeProfileProxy(profileId, proxy); return; }
            profile = proxy; ready = true; lastTaskAt = -1; tick();
        }
        public void onServiceDisconnected(int id) { if (id == profileId) ready = false; }
    };

    /** Orderly service stop restores before closing the profile; process kills keep the journal. */
    void close() {
        if (closed) return;
        closed = true;
        if (usbReceiverRegistered) { context.unregisterReceiver(usbChanges); usbReceiverRegistered = false; }
        worker.execute(() -> {
            try { synchronized (OPERATIONS) { if (journal.load() != null) status = describe(policy.restore()); } }
            catch (Exception e) { Log.w("DiPlayBridge", "Bluetooth media restore pending: " + cause(e).getClass().getSimpleName()); }
            finally {
                if (adapter != null && profile != null) adapter.closeProfileProxy(profileId, profile);
                profile = null; ready = false;
            }
        });
        worker.shutdown();
    }

    private boolean enabled() { return preferences.getBoolean(ENABLED, false) && (!calls || preferences.getBoolean(CALLS, false)); }
    String status() { return calls ? status.replace("媒体", "通话") : status; }

    private static Throwable cause(Exception e) { return e.getCause() == null ? e : e.getCause(); }

    private static String describe(BluetoothMediaPolicy.Result result) {
        switch (result) {
            case DISABLED: return "关闭";
            case WAIT_CONNECTION: return "等待 USB iPhone；设备无法识别时保留当前媒体设置";
            case WAIT_DISCONNECT: return "正在确认 iPhone 已拔出，随后恢复媒体设置";
            case NEED_DEVICE: return "请选择此 iPhone 的蓝牙配对；自动模式仅在一台已配对时执行";
            case DEVICE_REMOVED: return "原手机已取消配对，已清除恢复记录";
            case ALREADY_OFF: return "手机蓝牙媒体原本已关闭，保留原设置";
            case SUSPENDED: return "已确认手机蓝牙媒体关闭；拔出 iPhone 后恢复";
            case WAIT_APPLY: return "已请求关闭手机蓝牙媒体，等待系统确认";
            case APPLY_FAILED: return "系统尚未接受关闭请求，稍后重试";
            case ATTEMPTS_EXHAUSTED: return "本次关闭重试已达上限，请检查手机的媒体音频设置";
            case SAVE_FAILED: return "无法保存恢复记录，已停止本次操作";
            case RESTORED: return "已恢复原媒体设置；原连接的回连由系统处理";
            case RESTORE_FAILED: return "恢复媒体设置尚未成功，保留记录稍后重试";
            default: return "无法确认媒体原设置，暂不操作";
        }
    }

    private static final class Journal implements BluetoothMediaPolicy.Store {
        private final SharedPreferences preferences;
        private final String prefix;
        Journal(SharedPreferences preferences, boolean calls) {
            this.preferences = preferences; prefix = calls ? "bluetooth_call_restore_" : "bluetooth_media_restore_";
        }
        boolean exists() { return preferences.contains(prefix + "address"); }
        public BluetoothMediaPolicy.Snapshot load() {
            String address = preferences.getString(prefix + "address", "");
            if (address.isEmpty()) return null;
            int priority = preferences.getInt(prefix + "priority", -1);
            if (!BluetoothAdapter.checkBluetoothAddress(address) || (priority != 0 && priority != 100 && priority != 1000))
                throw new IllegalStateException("Invalid media restore record");
            return new BluetoothMediaPolicy.Snapshot(address, priority, preferences.getBoolean(prefix + "connected", false));
        }
        public boolean save(BluetoothMediaPolicy.Snapshot value) {
            return preferences.edit().putString(prefix + "address", value.address).putInt(prefix + "priority", value.priority)
                .putBoolean(prefix + "connected", value.connected).commit();
        }
        public boolean clear() {
            return preferences.edit().remove(prefix + "address").remove(prefix + "priority").remove(prefix + "connected").commit();
        }
    }

    private final class Sink implements BluetoothMediaPolicy.Media {
        private BluetoothDevice device(String address) { return adapter.getRemoteDevice(address); }
        private BluetoothProfile service() throws Exception {
            BluetoothProfile current = profile;
            if (!ready || current == null || adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Media service unavailable");
            // The framework proxy otherwise returns misleading OFF/DISCONNECTED on IPC failure.
            Field field = current.getClass().getDeclaredField("mService"); field.setAccessible(true);
            Object binder = field.get(current);
            if (!(binder instanceof IInterface) || !((IInterface) binder).asBinder().isBinderAlive()) throw new IllegalStateException("Media service disconnected");
            return current;
        }
        private Object invoke(String name, String address, Integer priority) throws Exception {
            BluetoothProfile current = service();
            Object receiver = current;
            if (priority != null) {
                // This SYNC+ framework wrapper hardcodes ON (100), even for OFF.
                // Pass the requested value unchanged to the existing media service.
                // The service still enforces this application's BLUETOOTH_ADMIN permission.
                Field field = current.getClass().getDeclaredField("mService"); field.setAccessible(true);
                receiver = field.get(current);
            }
            Method method = priority == null ? receiver.getClass().getMethod(name, BluetoothDevice.class)
                : Class.forName(binderInterface).getMethod(name, BluetoothDevice.class, int.class);
            return priority == null ? method.invoke(receiver, device(address)) : method.invoke(receiver, device(address), priority.intValue());
        }
        public boolean bonded(String address) throws Exception { service(); return device(address).getBondState() == BluetoothDevice.BOND_BONDED; }
        public int priority(String address) throws Exception {
            int value = (Integer) invoke("getPriority", address, null);
            int stored = Settings.Global.getInt(context.getContentResolver(), settingPrefix + address, Integer.MIN_VALUE);
            if (stored == Integer.MIN_VALUE || stored != value) throw new IllegalStateException("Media setting could not be verified");
            return value;
        }
        public int state(String address) throws Exception { return service().getConnectionState(device(address)); }
        public boolean priority(String address, int value) throws Exception { return (Boolean) invoke("setPriority", address, value); }
        public boolean disconnect(String address) throws Exception { return (Boolean) invoke("disconnect", address, null); }
        public boolean connect(String address) throws Exception { return (Boolean) invoke("connect", address, null); }
        boolean callsAreIdle(String address) throws Exception {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) return false;
            if (state(address) == BluetoothMediaPolicy.DISCONNECTED) return true;
            Object current = invoke("getCurrentCalls", address, null);
            return current instanceof List && ((List<?>) current).isEmpty();
        }
    }
}
