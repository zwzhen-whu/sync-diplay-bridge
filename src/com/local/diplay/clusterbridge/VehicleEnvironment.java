package com.local.diplay.clusterbridge;

import android.app.AppOpsManager;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;

/** Read-only inventory. Raw dumps, identifiers and call/notification content are never persisted. */
final class VehicleEnvironment {
    private final Context context;
    VehicleEnvironment(Context context) { this.context = context; }
    String collect() {
        StringBuilder result = new StringBuilder("车辆环境检查（只读）\n");
        result.append("时间：").append(System.currentTimeMillis()).append("\n品牌：").append(Build.BRAND)
            .append("\n厂商：").append(Build.MANUFACTURER).append("\n车型/设备型号：").append(Build.MODEL)
            .append("\n设备类型：").append(Build.DEVICE).append("\nAndroid：").append(Build.VERSION.RELEASE)
            .append(" API ").append(Build.VERSION.SDK_INT).append("\n固件：").append(Build.DISPLAY)
            .append("\n安全补丁：").append(Build.VERSION.SECURITY_PATCH)
            .append("\n启动至今：").append(SystemClock.elapsedRealtime() / 1000).append(" 秒\n");
        for (String name : new String[]{context.getPackageName(), BridgeService.SOURCE, BridgeService.CLUSTER, "org.savior.sync"})
            check(result, "应用 " + name, () -> packageInfo(name));
        for (String permission : new String[]{"READ_LOGS", "DUMP", "WRITE_EXTERNAL_STORAGE"})
            result.append("权限 ").append(permission).append("：").append(granted(permission)).append('\n');
        check(result, "进程日志组", () -> String.valueOf(LogAccess.hasLogGroup(new String(Files.readAllBytes(Paths.get("/proc/self/status")), StandardCharsets.UTF_8))));
        check(result, "使用情况访问", () -> {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return String.valueOf(ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED);
        });
        check(result, "下载存储状态", Environment::getExternalStorageState);
        SharedPreferences prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        for (String key : new String[]{"nav", "music", "lincoln_remaining", "auto_enter", "autostart", "startup_audio_recovery", BluetoothMediaGuard.ENABLED, BluetoothMediaGuard.CALLS})
            result.append("设置 ").append(key).append('=').append(prefs.getBoolean(key, "autostart".equals(key))).append('\n');
        result.append("道路模式：").append(Protocol.roadDisplayMode(prefs.getString("road_display_mode", Protocol.ROAD_ORIGINAL))).append('\n');
        check(result, "小组件绑定", () -> {
            android.appwidget.AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(prefs.getInt("widget", -1));
            return info == null ? "未绑定" : "provider=" + info.provider.flattenToShortString() + " matches=" + BridgeService.PROVIDER.equals(info.provider);
        });
        check(result, "源资源接口", () -> {
            Resources resources = context.getPackageManager().getResourcesForApplication(BridgeService.SOURCE);
            StringBuilder value = new StringBuilder();
            for (String name : new String[]{"widget_arrow", "widget_distance", "widget_road", "widget_song", "widget_eta"})
                value.append(name).append('=').append(resources.getIdentifier(name, "id", BridgeService.SOURCE) != 0).append(' ');
            for (String[] arrow : Protocol.ARROWS)
                value.append(arrow[0]).append('=').append(resources.getIdentifier(arrow[0], "drawable", BridgeService.SOURCE) != 0).append(' ');
            return value.toString();
        });
        for (String action : new String[]{"com.baidu.car.navi.STATE", "com.baidu.car.navi.INFO", "com.baidu.car.navi.REMAIN", "com.baidu.car.radio.notify.PLAY_INFO"})
            check(result, "接收接口 " + action, () -> receivers(action));
        check(result, "USB 描述符", () -> {
            UsbManager usb = context.getSystemService(UsbManager.class);
            if (usb == null) return "不可用";
            StringBuilder value = new StringBuilder("设备数=" + usb.getDeviceList().size());
            for (UsbDevice device : usb.getDeviceList().values())
                value.append(" vendor=").append(device.getVendorId()).append(" product=").append(device.getProductId())
                    .append(" iPhone=").append(IphoneUsbPresence.device(device.getVendorId(), device.getProductName()));
            return value.toString();
        });
        check(result, "蓝牙状态", () -> {
            BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
            return bluetooth == null ? "无适配器" : "enabled=" + bluetooth.isEnabled() + " bonded_count=" + bluetooth.getBondedDevices().size()
                + " a2dp_source_profile2=" + bluetooth.getProfileConnectionState(BluetoothProfile.A2DP)
                + " headset_profile1=" + bluetooth.getProfileConnectionState(BluetoothProfile.HEADSET)
                + "（标准手机角色概况，不代表车机 A2DP Sink/HFP Client 状态，不据此认定 CarPlay 已连接）";
        });
        result.append(audio()).append("\n接口边界：接收器已注册或广播已提交均不等于仪表已显示；此功能不注入测试导航，不切歌、不改蓝牙或音量。\n");
        return result.toString();
    }
    boolean granted(String name) { return context.checkSelfPermission("android.permission." + name) == PackageManager.PERMISSION_GRANTED; }
    @SuppressWarnings("deprecation")
    private String packageInfo(String name) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES);
        StringBuilder signature = new StringBuilder();
        if (info.signatures != null && info.signatures.length == 1)
            for (byte b : MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray()))
                signature.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        return "version=" + info.versionName + " code=" + info.versionCode + " enabled=" + info.applicationInfo.enabled + " cert_sha256=" + signature;
    }
    private String receivers(String action) {
        List<ResolveInfo> matches = context.getPackageManager().queryBroadcastReceivers(new Intent(action).setPackage(BridgeService.CLUSTER), PackageManager.GET_DISABLED_COMPONENTS);
        StringBuilder value = new StringBuilder("manifest_receivers=" + matches.size());
        for (ResolveInfo match : matches) if (match.activityInfo != null) {
            String permission = match.activityInfo.permission;
            value.append(" component=").append(match.activityInfo.name).append(" enabled=").append(match.activityInfo.enabled)
                .append(" exported=").append(match.activityInfo.exported).append(" permission=").append(permission)
                .append(" sender_granted=").append(permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        }
        return value.append("；动态注册接口不一定能从此列表查询，零结果不等于不支持").toString();
    }
    String audio() {
        StringBuilder result = new StringBuilder("音频快照 " + System.currentTimeMillis() + "\n");
        check(result, "系统播放状态", () -> {
            AudioManager audio = context.getSystemService(AudioManager.class);
            return audio == null ? "不可用" : "mode=" + audio.getMode() + " music_active=" + audio.isMusicActive()
                + " music_volume=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " muted=" + audio.isStreamMute(AudioManager.STREAM_MUSIC);
        });
        if (!granted("DUMP")) return result.append("系统音频/媒体会话：未授权 DUMP，跳过；仍可采集小组件和分发记录。\n").toString();
        check(result, "系统音频解析", () -> {
            int uid = context.getPackageManager().getApplicationInfo(BridgeService.SOURCE, 0).uid;
            SystemAudioState audio = SystemAudioState.parse(readService("audio"), uid);
            return "focus=" + audio.focus + " source_playing=" + audio.sourcePlaying + " source_requested=" + audio.sourceRequested
                + " music_unmuted=" + audio.musicUnmuted + "（UNKNOWN 表示格式未识别；原始快照不保存）";
        });
        check(result, "媒体按键会话", () -> "source_is_receiver=" + SystemAudioState.mediaReceiverIsSource(readService("media_session")) + "（仅匹配已知格式）");
        return result.toString();
    }
    private String readService(String service) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("dumpsys", "-t", "2", service).redirectErrorStream(true);
        java.lang.Process child = builder.start(); long deadline = SystemClock.elapsedRealtime() + 2500;
        try (InputStream input = child.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (true) {
                int available = input.available();
                if (available > 0) {
                    int n = input.read(buffer, 0, Math.min(buffer.length, available)); if (n > 0) bytes.write(buffer, 0, n);
                    if (bytes.size() > 128 * 1024) throw new java.io.IOException("OutputLimit");
                } else if (!child.isAlive()) break;
                else Thread.sleep(20);
                if (SystemClock.elapsedRealtime() > deadline) throw new java.io.IOException("Timeout");
            }
            if (child.exitValue() != 0) throw new java.io.IOException("Unavailable");
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally { child.destroy(); }
    }
    private interface Read { String get() throws Exception; }
    private static void check(StringBuilder out, String title, Read read) {
        out.append(title).append("：");
        try { out.append(DiagnosticReport.clean(read.get(), 5000)); }
        catch (Exception e) { out.append("不可用（").append(e.getClass().getSimpleName()).append('）'); }
        out.append('\n');
    }
}
