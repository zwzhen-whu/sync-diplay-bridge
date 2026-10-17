package com.local.diplay.clusterbridge;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/** Use the widget's existing click action only while DiPlay's entry activity is foreground. */
final class CarPlayAutoEntry {
    private final Context context;
    private final CarPlayEntryPolicy policy = new CarPlayEntryPolicy();
    private final ForegroundActivityState foreground = new ForegroundActivityState();
    private String status = "关闭";

    CarPlayAutoEntry(Context context) { this.context = context; }

    static boolean hasAccess(Context context) {
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) { return false; }
    }

    void tick(boolean enabled, boolean connected, View widgetRoot) {
        long now = SystemClock.elapsedRealtime();
        if (!enabled) { policy.connection(false, now); foreground.clear(); status = "关闭"; return; }
        if (!hasAccess(context)) { policy.connection(false, now); foreground.clear(); status = "请授权使用情况访问"; return; }
        policy.connection(connected, now);
        if (!connected) { foreground.clear(); status = "等待 CarPlay 连接"; return; }
        if (!policy.watching(now)) {
            status = policy.confirmed() ? "已确认 CarPlay 界面稳定显示" : "等待连接初始检查或本次检查已结束";
            return;
        }
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager lock = context.getSystemService(KeyguardManager.class);
            if (power == null || !power.isInteractive() || (lock != null && lock.isKeyguardLocked())) {
                policy.unknownForeground(); return;
            }
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            long wall = System.currentTimeMillis();
            UsageEvents events = usage == null ? null : usage.queryEvents(foreground.queryStart(wall, now), wall + 1);
            if (events == null) {
                foreground.clear(); policy.unknownForeground(); status = "无法读取前台页面"; return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                foreground.event(event.getEventType(), event.getPackageName(), event.getClassName(), event.getTimeStamp());
            }
            foreground.queried(wall, now);
            if (foreground.packageName() == null || foreground.className() == null) {
                policy.unknownForeground(); status = "尚未获得当前页面，等待系统前台事件"; return;
            }
            if (policy.request(now, foreground.packageName(), foreground.className())) {
                status = (widgetRoot != null && widgetRoot.performClick() ? "已请求进入 CarPlay，等待界面确认" : "跳转入口暂不可用，稍后重试")
                    + "（" + policy.attempts() + "/" + CarPlayEntryPolicy.MAX_ATTEMPTS + "）";
                Log.i("DiPlayBridge", "Auto-entry: " + status);
            } else status = policy.confirmed() ? "CarPlay 界面稳定显示，继续观察首次连接回退" :
                policy.attempts() >= CarPlayEntryPolicy.MAX_ATTEMPTS ? "已达到本次自动进入次数上限，请手动进入" : "检查首次连接页面，仅在 DiPlay 入口页请求跳转";
        } catch (RuntimeException e) {
            foreground.clear(); policy.unknownForeground(); status = "无法确认前台页面：" + e.getClass().getSimpleName();
        }
    }

    String status() { return status; }
}
