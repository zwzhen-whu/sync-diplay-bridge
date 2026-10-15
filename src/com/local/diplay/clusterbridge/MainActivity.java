package com.local.diplay.clusterbridge;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private SharedPreferences preferences;
    private final Runnable refresh = new Runnable() {
        public void run() {
            status.setText(preferences.getString("status", "正在启动小组件读取…"));
            handler.postDelayed(this, 1000);
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(16, 23, 32)); page.setPadding(dp(24), dp(14), dp(24), dp(18));
        TextView title = label("CarPlay 仪表桥接", 28, "#E8EDF5"); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        TextView detail = label("连接 CarPlay 后转发导航与歌曲。切回原车导航或音乐前，请关闭对应转发。", 17, "#AAB7C7");
        detail.setPadding(0, dp(5), 0, dp(16)); page.addView(detail);
        LinearLayout columns = new LinearLayout(this);
        page.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView controls = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(18));
        controls.setBackground(panel()); controls.addView(body);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 0.44f); left.setMargins(0, 0, dp(16), 0);
        columns.addView(controls, left);
        body.addView(label("转发设置", 20, "#61D9BB"));
        CheckBox autostart = new CheckBox(this); autostart.setText("车机开机后自动启动桥接（后台运行）");
        autostart.setChecked(preferences.getBoolean("autostart", true)); body.addView(autostart);
        autostart.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("autostart", checked).apply());
        CheckBox nav = new CheckBox(this); nav.setText("转发 CarPlay 导航箭头、距离和道路");
        nav.setChecked(preferences.getBoolean("nav", false)); body.addView(nav);
        CheckBox music = new CheckBox(this); music.setText("转发 CarPlay 歌曲文字和播放状态");
        music.setChecked(preferences.getBoolean("music", false)); body.addView(music);
        CheckBox autoEnter = new CheckBox(this); autoEnter.setText("连接后停留在 DiPlay 时自动进入 CarPlay");
        autoEnter.setChecked(preferences.getBoolean("auto_enter", false)); body.addView(autoEnter);
        autoEnter.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("auto_enter", checked).apply(); startReader(); });
        for (CheckBox check : new CheckBox[]{autostart, nav, music, autoEnter}) { check.setTextSize(18); check.setTextColor(Color.parseColor("#E8EDF5")); }
        nav.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("nav", checked).apply(); startReader(); });
        music.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("music", checked).apply(); startReader(); });
        TextView entryHint = label("自动进入默认关闭，需授权使用情况访问。连接后 30 秒内若退回 DiPlay 入口页（含设置页），最多补跳转一次；切到其他应用后不再拉回。", 15, "#91A2B6");
        entryHint.setPadding(0, dp(8), 0, dp(6)); body.addView(entryHint);
        Button usage = new Button(this); usage.setText("授权自动进入 CarPlay"); styleButton(usage, "#2A3A4D", "#E8EDF5"); body.addView(usage);
        usage.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (RuntimeException e) { Toast.makeText(this, "系统没有此授权页面，可通过 ADB 授权", Toast.LENGTH_LONG).show(); }
        });
        LinearLayout buttons = new LinearLayout(this); body.addView(buttons);
        Button read = new Button(this); read.setText("重新读取"); buttons.addView(read);
        styleButton(read, "#61D9BB", "#10201D");
        read.setOnClickListener(v -> startReader());
        Button bind = new Button(this); bind.setText("授权小组件"); buttons.addView(bind);
        styleButton(bind, "#2A3A4D", "#E8EDF5");
        bind.setOnClickListener(v -> {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, preferences.getInt("widget", -1));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, BridgeService.PROVIDER);
            startActivityForResult(intent, 1);
        });
        Button stop = new Button(this); stop.setText("停止并关闭转发"); buttons.addView(stop);
        styleButton(stop, "#422B34", "#F1B9BC");
        stop.setOnClickListener(v -> {
            nav.setChecked(false); music.setChecked(false);
            stopService(new Intent(this, BridgeService.class));
        });
        TextView limits = label("距离为取整值，长文字可能截短。请在车辆停稳时设置与核对。", 15, "#91A2B6");
        limits.setPadding(0, dp(12), 0, 0); body.addView(limits);
        ScrollView statusScroll = new ScrollView(this); statusScroll.setBackground(panel());
        LinearLayout statusBody = new LinearLayout(this); statusBody.setOrientation(LinearLayout.VERTICAL); statusBody.setPadding(dp(20), dp(14), dp(20), dp(18));
        statusBody.addView(label("运行状态 · 0.1.7", 20, "#61D9BB"));
        status = label("正在读取…", 18, "#E8EDF5"); status.setPadding(0, dp(10), 0, 0); status.setLineSpacing(dp(3), 1); status.setTextIsSelectable(true);
        statusBody.addView(status); statusScroll.addView(statusBody);
        columns.addView(statusScroll, new LinearLayout.LayoutParams(0, -1, 0.56f));
        setContentView(page); page.setFocusableInTouchMode(true); page.requestFocus(); startReader();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, String color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.parseColor(color)); return view;
    }
    private GradientDrawable panel() {
        GradientDrawable result = new GradientDrawable(); result.setColor(Color.parseColor("#192330"));
        result.setCornerRadius(dp(14)); result.setStroke(dp(1), Color.parseColor("#2B3949")); return result;
    }
    private void styleButton(Button button, String background, String text) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.parseColor(background)); shape.setCornerRadius(dp(7));
        button.setBackgroundTintList(null); button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), shape, null));
        button.setPadding(dp(12), dp(6), dp(12), dp(6)); button.setTextColor(Color.parseColor(text)); button.setAllCaps(false);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2); layout.setMargins(0, dp(8), dp(8), 0); button.setLayoutParams(layout);
    }

    private void startReader() { startForegroundService(new Intent(this, BridgeService.class)); }
    protected void onActivityResult(int req, int result, Intent data) { super.onActivityResult(req, result, data); startReader(); }
    protected void onResume() { super.onResume(); handler.post(refresh); }
    protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
