package com.local.diplay.clusterbridge.homemap;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;

public final class MapSettingsActivity extends Activity {
    private final Handler main = new Handler();
    private TextView status;
    private SharedPreferences preferences;
    private SeekBar widthControl, heightControl, featherControl;
    private Switch followControl, avoidBarsControl;
    private TextView followStatus;
    private Button followPermissionButton;
    private Button startButton, stopButton, previewButton, resetButton;
    private TextView handoff;
    private boolean busy, appearanceReady;
    private String operation;
    private final Runnable refresh = new Runnable() {
        public void run() {
            android.content.SharedPreferences prefs = getSharedPreferences("home_map", MODE_PRIVATE);
            status.setText(operation != null ? operation : prefs.getString("status", "浮窗已关闭") + "\n" + prefs.getString("runtime_status", ""));
            refreshFollowStatus();
            main.postDelayed(this, 700);
        }
    };
    static boolean usageAllowed(android.content.Context context) {
        return context.getSystemService(AppOpsManager.class).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("home_map", MODE_PRIVATE);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(36, 26, 36, 26); body.setBackgroundColor(0xff182029);
        LinearLayout header = new LinearLayout(this); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText("桌面地图"); title.setTextSize(26); title.setTextColor(0xffe5f5f2);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        status = new TextView(this); status.setTextSize(16); status.setTextColor(0xff66dfbf);
        status.setGravity(android.view.Gravity.RIGHT); header.addView(status, new LinearLayout.LayoutParams(0, -2, 2)); body.addView(header);
        TextView tip = new TextView(this); tip.setText("拖动浮窗可移动位置。调整自动保存，返回桌面即可查看。");
        tip.setTextSize(16); tip.setTextColor(0xffb9cad1); tip.setPadding(0, 10, 0, 8); body.addView(tip);
        handoff = new TextView(this); handoff.setTextSize(15); handoff.setTextColor(0xffb9cad1);
        handoff.setText("首次接管会保留旧设置、停用独立应用，并重连一次 CarPlay。");
        handoff.setVisibility(preferences.getBoolean("ownership_claimed", false) ? android.view.View.GONE : android.view.View.VISIBLE); body.addView(handoff);
        TextView settingsTip = new TextView(this);
        settingsTip.setText("边缘渐变越大，过渡范围越宽；0 为无渐变。");
        settingsTip.setTextSize(16); settingsTip.setTextColor(0xffb9cad1); settingsTip.setPadding(0, 0, 0, 10); body.addView(settingsTip);
        LinearLayout sizeRow = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL); left.setPadding(0, 0, 20, 0);
        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL); right.setPadding(20, 0, 0, 0);
        sizeRow.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        sizeRow.addView(right, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(sizeRow);
        widthControl = addControl(left, "宽度", 360, 1000, 10, preferences.getInt("width", 520), "");
        heightControl = addControl(right, "高度", 240, 560, 10, preferences.getInt("height", 340), "");
        featherControl = addControl(body, "边缘渐变", 0, 200, 5, preferences.getInt("feather_percent", 100), "%");
        avoidBarsControl = new Switch(this); avoidBarsControl.setText("避开系统状态栏和底栏");
        avoidBarsControl.setTextColor(0xffe5f5f2); avoidBarsControl.setTextSize(18);
        avoidBarsControl.setPadding(0, 12, 0, 8);
        avoidBarsControl.setChecked(preferences.getBoolean(MapWindowMetrics.AVOID_BARS_KEY, false));
        avoidBarsControl.setOnCheckedChangeListener((button, checked) -> {
            if (!busy) preferences.edit().putBoolean(MapWindowMetrics.AVOID_BARS_KEY, checked).apply();
        }); body.addView(avoidBarsControl);
        TextView boundsTip = new TextView(this);
        boundsTip.setText("默认可拖到屏幕边缘。开启后避开系统栏；若车机上报的边界不准确，可关闭。小屏幕会自动缩小浮窗以完整显示。");
        boundsTip.setTextColor(0xffb9cad1); boundsTip.setTextSize(16); body.addView(boundsTip);
        followControl = new Switch(this); followControl.setText("跟随桌面滑动");
        followControl.setTextColor(0xffe5f5f2); followControl.setTextSize(18);
        followControl.setPadding(0, 12, 0, 8);
        followControl.setChecked(preferences.getBoolean(HomeMapScrollService.FOLLOW_KEY, false));
        followControl.setOnCheckedChangeListener((button, checked) -> {
            if (busy) return;
            preferences.edit().remove(MapFollowAnchor.KEY).putBoolean(HomeMapScrollService.FOLLOW_KEY, checked).apply();
            refreshFollowStatus();
        }); body.addView(followControl);
        TextView followTip = new TextView(this);
        followTip.setText("开启时以当前桌面页为基准，地图区域的滑动交给桌面；调整位置或更换所属页面时先关闭跟随，再重新开启。");
        followTip.setTextColor(0xffb9cad1); followTip.setTextSize(16); body.addView(followTip);
        followStatus = new TextView(this); followStatus.setTextColor(0xff66dfbf); followStatus.setTextSize(15);
        followStatus.setPadding(0, 6, 0, 0); body.addView(followStatus);
        followPermissionButton = new Button(this); followPermissionButton.setText("打开桌面跟随授权设置");
        followPermissionButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(followPermissionButton); refreshFollowStatus();
        LinearLayout actions = new LinearLayout(this);
        previewButton = new Button(this); previewButton.setText("返回桌面看效果"); previewButton.setOnClickListener(v -> {
            saveAppearance();
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }); actions.addView(previewButton, new LinearLayout.LayoutParams(0, -2, 1));
        resetButton = new Button(this); resetButton.setText("恢复默认尺寸和渐变"); resetButton.setOnClickListener(v -> {
            widthControl.setProgress((520 - 360) / 10);
            heightControl.setProgress((340 - 240) / 10);
            featherControl.setProgress(100 / 5);
            saveAppearance();
        }); actions.addView(resetButton, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(actions);
        LinearLayout serviceActions = new LinearLayout(this);
        startButton = new Button(this); startButton.setText(preferences.getBoolean("ownership_claimed", false) ? "开启浮窗" : "接管／开启桌面地图"); startButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); return; }
            if (!usageAllowed(this)) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return; }
            saveAppearance(); setBusy(true, "正在接管桌面地图…");
            HomeMapController.enable(this, (success, message) -> {
                if (isDestroyed()) return;
                setBusy(false, message);
                handoff.setVisibility(preferences.getBoolean("ownership_claimed", false) ? android.view.View.GONE : android.view.View.VISIBLE);
                if (success) startButton.setText("开启浮窗");
            });
        }); serviceActions.addView(startButton, new LinearLayout.LayoutParams(0, -2, 1));
        stopButton = new Button(this); stopButton.setText("关闭浮窗"); stopButton.setOnClickListener(v -> {
            HomeMapController.disable(this); operation = null;
        }); serviceActions.addView(stopButton, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(serviceActions);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        appearanceReady = preferences.getBoolean("migration_done", false);
        if (!appearanceReady) {
            setBusy(true, "正在读取现有浮窗设置…");
            HomeMapController.importAppearance(this, (success, message) -> {
                if (isDestroyed()) return;
                widthControl.setProgress((Math.max(360, Math.min(1000, preferences.getInt("width", 520))) - 360) / 10);
                heightControl.setProgress((Math.max(240, Math.min(560, preferences.getInt("height", 340))) - 240) / 10);
                featherControl.setProgress(Math.max(0, Math.min(200, preferences.getInt("feather_percent", 100))) / 5);
                appearanceReady = true; setBusy(false, message);
            });
        }
    }
    private void setBusy(boolean value, String message) {
        busy = value; operation = message;
        for (android.view.View view : new android.view.View[]{startButton, stopButton, previewButton, resetButton, widthControl, heightControl, featherControl, followControl, followPermissionButton, avoidBarsControl})
            if (view != null) view.setEnabled(!value);
        if (status != null) status.setText(message);
    }
    private void refreshFollowStatus() {
        if (followStatus == null || followPermissionButton == null) return;
        followStatus.setText(HomeMapScrollService.status(this));
        followPermissionButton.setVisibility(followControl.isChecked() && !HomeMapScrollService.authorized(this)
            ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private SeekBar addControl(LinearLayout body, String title, int min, int max, int step, int value, String unit) {
        TextView label = new TextView(this); label.setTextSize(18); label.setTextColor(0xffe5f5f2); body.addView(label);
        SeekBar slider = new SeekBar(this); slider.setMax((max - min) / step);
        slider.setProgress((Math.max(min, Math.min(max, value)) - min) / step);
        label.setText(title + "：" + (min + slider.getProgress() * step) + unit);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(title + "：" + (min + progress * step) + unit);
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { saveAppearance(); }
        }); body.addView(slider, new LinearLayout.LayoutParams(-1, 48));
        return slider;
    }
    private void saveAppearance() {
        if (!appearanceReady || busy || widthControl == null || heightControl == null || featherControl == null) return;
        operation = null;
        preferences.edit().putInt("width", 360 + widthControl.getProgress() * 10)
            .putInt("height", 240 + heightControl.getProgress() * 10)
            .putInt("feather_percent", featherControl.getProgress() * 5).putInt("geometry_version", 2).apply();
    }
    protected void onResume() { super.onResume(); if (!busy) operation = null; main.post(refresh); }
    protected void onPause() { saveAppearance(); main.removeCallbacks(refresh); super.onPause(); }
}
