package com.local.diplay.clusterbridge.homemap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Event-only OEM desktop observer. It performs no actions and never injects input. */
public final class HomeMapScrollService extends AccessibilityService {
    static final String FOLLOW_KEY = "follow_launcher_scroll";
    interface Listener { void changed(LauncherScrollState.Snapshot snapshot); }
    private static HomeMapScrollService active;
    private static Listener mapListener;
    private final LauncherScrollState scroll = new LauncherScrollState();
    private SharedPreferences preferences;
    private boolean observing;
    private long lastRootRead = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (FOLLOW_KEY.equals(key) || "enabled".equals(key) || "ownership_claimed".equals(key)) configure();
    };

    static void listen(Listener listener) {
        mapListener = listener;
        if (listener != null) listener.changed(snapshot());
    }
    static void unlisten(Listener listener) { if (mapListener == listener) mapListener = null; }
    static LauncherScrollState.Snapshot snapshot() { return active == null ? null : active.scroll.latest(); }
    static boolean isObserving() { return active != null && active.observing; }
    static void requestSnapshot() { if (isObserving()) active.readDesktopPosition(); }
    static boolean authorized(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName wanted = new ComponentName(context, HomeMapScrollService.class);
        for (String value : enabled.split(":")) if (wanted.equals(ComponentName.unflattenFromString(value))) return true;
        return false;
    }
    static String status(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("home_map", MODE_PRIVATE);
        if (!prefs.getBoolean(FOLLOW_KEY, false)) return "已关闭：地图固定显示，可直接拖动调整位置。";
        if (!authorized(context)) return "请在系统无障碍设置中开启「桌面地图跟随」。";
        if (!prefs.getBoolean("enabled", false)) return "开启桌面地图后生效。";
        if (!isObserving()) return "等待系统连接桌面跟随服务。";
        return snapshot() == null ? "等待原车桌面的滚动位置，暂时保持固定显示。" : "已就绪：地图随桌面左右移动。";
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected(); active = this;
        preferences = getSharedPreferences("home_map", MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        configure();
    }
    private void configure() {
        if (preferences == null) return;
        observing = preferences.getBoolean(FOLLOW_KEY, false) && preferences.getBoolean("enabled", false)
            && preferences.getBoolean("ownership_claimed", false);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.packageNames = new String[]{LauncherScrollState.PACKAGE};
        info.eventTypes = observing ? AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED : 0;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 0;
        setServiceInfo(info);
        scroll.clear(); publish();
        if (observing) readDesktopPosition();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!observing || event == null || !LauncherScrollState.PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!LauncherScrollState.VIEW_CLASS.contentEquals(value(event.getClassName()))) return;
            AccessibilityNodeInfo source = null;
            try {
                source = event.getSource();
                if (source != null && source.getWindowId() == event.getWindowId()
                        && scroll.accept(value(source.getPackageName()), source.getViewIdResourceName(), value(source.getClassName()),
                            event.getWindowId(), event.getScrollX(), event.getMaxScrollX(), event.getEventTime())) publish();
            } catch (RuntimeException ignored) { /* A disappearing window must not disturb the map. */ }
            finally { if (source != null) source.recycle(); }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            LauncherScrollState.Snapshot last = scroll.latest();
            if (last == null || last.windowId != event.getWindowId()) {
                if (last != null) { scroll.clear(); publish(); }
                readDesktopPosition();
            }
        }
    }
    private void readDesktopPosition() {
        // 8.1 supplies drawing-bounds scrollX; clipped screen bounds cannot recover it.
        if (!observing || Build.VERSION.SDK_INT != 27) return;
        long now = SystemClock.uptimeMillis();
        if (lastRootRead >= 0 && now - lastRootRead < 250) return;
        lastRootRead = now;
        AccessibilityNodeInfo root = null;
        List<AccessibilityNodeInfo> views = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || !LauncherScrollState.PACKAGE.contentEquals(value(root.getPackageName()))) return;
            views = root.findAccessibilityNodeInfosByViewId(LauncherScrollState.VIEW_ID);
            for (AccessibilityNodeInfo view : views) {
                if (!LauncherScrollState.isTarget(value(view.getPackageName()), view.getViewIdResourceName(), value(view.getClassName()))) continue;
                AccessibilityNodeInfo content = view.getChild(0);
                if (content == null) continue;
                try {
                    if (!LauncherScrollState.CONTENT_ID.equals(content.getViewIdResourceName())) continue;
                    Rect viewport = new Rect(), drawing = new Rect();
                    view.getBoundsInParent(viewport); content.getBoundsInParent(drawing);
                    if (scroll.acceptDrawingBounds(value(view.getPackageName()), view.getViewIdResourceName(), value(view.getClassName()),
                            view.getWindowId(), viewport.left, viewport.width(), drawing.width(), SystemClock.uptimeMillis())) publish();
                    return;
                } finally { content.recycle(); }
            }
        } catch (RuntimeException ignored) { /* No guessing or polling when the OEM structure is unavailable. */ }
        finally {
            if (views != null) for (AccessibilityNodeInfo view : views) view.recycle();
            if (root != null) root.recycle();
        }
    }
    private void publish() { if (mapListener != null) mapListener.changed(scroll.latest()); }
    private void disconnect() {
        if (active != this) return;
        observing = false; scroll.clear(); active = null;
        if (mapListener != null) mapListener.changed(null);
    }
    @Override public void onInterrupt() { scroll.clear(); publish(); }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() {
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        disconnect(); super.onDestroy();
    }
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
}
