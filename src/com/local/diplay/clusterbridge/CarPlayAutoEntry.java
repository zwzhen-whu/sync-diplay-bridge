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
        policy.connection(connected, now);
        if (!enabled) { foreground.clear(); status = "关闭"; return; }
        if (!hasAccess(context)) { foreground.clear(); policy.unknownForeground(); status = "请授权使用情况访问"; return; }
        if (!connected) { foreground.clear(); status = "等待 CarPlay 连接"; return; }
        if (!policy.watching(now)) return;
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
            foreground.queried(wall);
            if (policy.request(now, foreground.packageName(), foreground.className())) {
                status = widgetRoot != null && widgetRoot.performClick() ? "已请求进入 CarPlay" : "小组件没有可用的跳转入口";
                Log.i("DiPlayBridge", "Auto-entry: " + status);
            } else status = "本次连接仅在 DiPlay 入口页补跳转一次";
        } catch (RuntimeException e) {
            foreground.clear(); policy.unknownForeground(); status = "无法确认前台页面：" + e.getClass().getSimpleName();
        }
    }

    String status() { return status; }
}
