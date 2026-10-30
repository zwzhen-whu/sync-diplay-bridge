package com.local.diplay.clusterbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private SharedPreferences preferences;
    private TextView diagnosticStatus;
    private Button diagnosticStart, diagnosticFinish, diagnosticRetry;
    private String pendingDiagnosticAction;
    private Dialog donationDialog;
    private final Runnable refresh = new Runnable() {
        public void run() {
            status.setText(preferences.getString("status", "正在启动小组件读取…"));
            refreshDiagnostics();
            handler.postDelayed(this, 1000);
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingDiagnosticAction = state.getString("diagnostic_pending_action");
        preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(16, 23, 32)); page.setPadding(dp(24), dp(14), dp(24), dp(18));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); page.addView(heading);
        TextView title = label("CarPlay 仪表桥接", 28, "#E8EDF5"); title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button donate = new Button(this); donate.setText("对您有帮助？给作者加鸡腿");
        donate.setTextSize(18); donate.setSingleLine(true); styleButton(donate, "#2A3A4D", "#61D9BB");
        LinearLayout.LayoutParams donateLayout = new LinearLayout.LayoutParams(-2, dp(48)); donateLayout.setMargins(dp(12), 0, 0, 0);
        heading.addView(donate, donateLayout); donate.setOnClickListener(v -> showDonationCode());
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
        body.addView(label("桌面地图", 20, "#61D9BB"));
        Button homeMap = new Button(this); homeMap.setText("桌面地图浮窗与外观设置");
        styleButton(homeMap, "#2A3A4D", "#E8EDF5"); body.addView(homeMap);
        homeMap.setOnClickListener(v -> startActivity(new Intent(this,
            com.local.diplay.clusterbridge.homemap.MapSettingsActivity.class)));
        body.addView(label("在原车桌面显示 CarPlay 地图，可调整宽高和渐变，并接管原独立浮窗的设置。", 15, "#91A2B6"));
        addDiagnostics(body);
        body.addView(label("转发设置", 20, "#61D9BB"));
        CheckBox autostart = new CheckBox(this); autostart.setText("车机开机后自动启动桥接（后台运行）");
        autostart.setChecked(preferences.getBoolean("autostart", true)); body.addView(autostart);
        autostart.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("autostart", checked).apply());
        CheckBox nav = new CheckBox(this); nav.setText("转发 CarPlay 导航箭头、距离和道路");
        nav.setChecked(preferences.getBoolean("nav", false)); body.addView(nav);
        body.addView(label("仪表道路文字", 17, "#AAB7C7"));
        RadioGroup roadMode = new RadioGroup(this); roadMode.setOrientation(RadioGroup.VERTICAL); body.addView(roadMode);
        String[] roadModes = {Protocol.ROAD_ORIGINAL, Protocol.ROAD_CLEAN};
        String[] roadLabels = {"保留原文", "去掉导航提示语（保留全部路名）"};
        int[] roadIds = new int[roadModes.length]; int selectedRoad = 0;
        for (int i = 0; i < roadModes.length; i++) {
            RadioButton button = new RadioButton(this); roadIds[i] = android.view.View.generateViewId(); button.setId(roadIds[i]);
            button.setText(roadLabels[i]); button.setTextSize(18); button.setTextColor(Color.parseColor("#E8EDF5")); roadMode.addView(button);
            if (roadModes[i].equals(Protocol.roadDisplayMode(preferences.getString("road_display_mode", Protocol.ROAD_ORIGINAL)))) selectedRoad = i;
        }
        roadMode.check(roadIds[selectedRoad]);
        roadMode.setOnCheckedChangeListener((group, id) -> {
            for (int i = 0; i < roadIds.length; i++) if (roadIds[i] == id) {
                preferences.edit().putString("road_display_mode", roadModes[i]).apply(); startReader(); break;
            }
        });
        body.addView(label("去掉开头独立的“去往”“进入”等提示，保留后面全部路名和空格。例如“去往 八一路 洪山侧路”显示“八一路 洪山侧路”。", 15, "#91A2B6"));
        CheckBox remaining = new CheckBox(this); remaining.setText("林肯 SYNC+：剩余时间与里程");
        remaining.setChecked(preferences.getBoolean("lincoln_remaining", false)); body.addView(remaining);
        remaining.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("lincoln_remaining", checked).apply(); startReader(); });
        TextView remainingHint = label("默认关闭，需同时开启导航转发。向林肯仪表同步 DiPlay 提供的剩余时间与里程。", 15, "#91A2B6");
        remainingHint.setPadding(0, dp(4), 0, dp(8)); body.addView(remainingHint);
        CheckBox music = new CheckBox(this); music.setText("转发 CarPlay 歌曲文字和播放状态");
        music.setChecked(preferences.getBoolean("music", false)); body.addView(music);
        CheckBox lyricsOnly = new CheckBox(this); lyricsOnly.setText("只显示歌词（用于歌词模式）");
        lyricsOnly.setTextSize(18); lyricsOnly.setTextColor(Color.parseColor("#E8EDF5"));
        lyricsOnly.setChecked(preferences.getBoolean(MusicTextPolicy.LYRICS_ONLY, false)); body.addView(lyricsOnly);
        lyricsOnly.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(MusicTextPolicy.LYRICS_ONLY, checked).apply(); startReader();
        });
        body.addView(label("将“歌词 — 歌名”显示为“歌词”。需在音乐 App 开启歌词模式；普通歌曲信息请关闭此选项。", 15, "#91A2B6"));
        CheckBox wiredMic = new CheckBox(this); wiredMic.setText("有线 CarPlay 微信语音兼容");
        wiredMic.setTextSize(18); wiredMic.setTextColor(Color.parseColor("#E8EDF5"));
        wiredMic.setChecked(preferences.getBoolean(com.local.diplay.clusterbridge.audio.MicPacketController.ENABLED, false));
        body.addView(wiredMic);
        wiredMic.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(com.local.diplay.clusterbridge.audio.MicPacketController.ENABLED, checked).apply(); startReader();
        });
        body.addView(label("用于有线连接时微信发语音无声的情况。下次录音生效，关闭后使用原有处理；仅适配原版 DiPlay 0.2.21 和 Android 8.1，需本机调试接口。", 15, "#91A2B6"));
        CheckBox callVolume = new CheckBox(this); callVolume.setText("修正 CarPlay 通话音量调节");
        callVolume.setTextSize(18); callVolume.setTextColor(Color.parseColor("#E8EDF5"));
        callVolume.setChecked(preferences.getBoolean(com.local.diplay.clusterbridge.audio.CallAudioController.ENABLED, false));
        body.addView(callVolume);
        callVolume.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(com.local.diplay.clusterbridge.audio.CallAudioController.ENABLED, checked).apply(); startReader();
        });
        body.addView(label("用于音量条变化但通话声音不变的情况。只调节 CarPlay 通话，音乐与导航保持原样；0 为静音，30 为原始音量。需本机调试，当前适配 Android 8.1 和原版 DiPlay 0.2.21。关闭后恢复原音频。", 15, "#91A2B6"));
        CheckBox calls = new CheckBox(this); calls.setText("转发 CarPlay 通话姓名、号码和计时（测试）");
        calls.setTextSize(18); calls.setTextColor(Color.parseColor("#E8EDF5"));
        calls.setChecked(preferences.getBoolean(CarPlayCallDisplay.ENABLED, false)); body.addView(calls);
        calls.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean(CarPlayCallDisplay.ENABLED, checked).apply(); startReader(); });
        body.addView(label("默认关闭。连接 CarPlay 后从下一次通话开始显示；挂断或断开后清除。蓝牙通话仍连接时交由原车显示。联系人信息不保存。", 15, "#91A2B6"));
        CheckBox mediaKeys = new CheckBox(this); mediaKeys.setText("接收 Key Mapper 的上一曲／下一曲（测试）");
        mediaKeys.setTextSize(18); mediaKeys.setTextColor(Color.parseColor("#E8EDF5"));
        mediaKeys.setChecked(preferences.getBoolean(BridgeMediaKeys.ENABLED, false)); body.addView(mediaKeys);
        mediaKeys.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean(BridgeMediaKeys.ENABLED, checked).apply(); startReader(); });
        body.addView(label("CarPlay 正在播放时切 CarPlay 的歌曲；其他情况交给随心听当前音源。未连接 CarPlay 也可切歌。来电时上一曲接听、下一曲拒接；通话中下一曲挂断，上一曲忽略。需日志授权和本机接口，接听挂断需在 DiPlay 开启 CarPlay 通话按键。", 15, "#91A2B6"));
        Button keyHelp = new Button(this); keyHelp.setText("Key Mapper 设置方法"); styleButton(keyHelp, "#2A3A4D", "#E8EDF5"); body.addView(keyHelp);
        Button mediaVolume = new Button(this);mediaVolume.setText("媒体音量记忆与开机音量");styleButton(mediaVolume,"#2A3A4D","#E8EDF5");body.addView(mediaVolume);
        mediaVolume.setOnClickListener(v->com.local.diplay.clusterbridge.audio.MediaVolumeSettings.show(this));
        keyHelp.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("把两个按键交给桥接")
            .setMessage("1. 开启上方按键入口并保持桥接运行。\n\n2. 在 Key Mapper 中，将两个实体键分别设为“控制指定应用媒体 → CarPlay仪表桥接 → 上一曲／下一曲”，替换原来直接控制 DiPlay 的两条动作。\n\n3. 在 DiPlay 开启“CarPlay call keys／CarPlay 通话按键（实验性）”。此项与仪表通话显示分开控制。\n\n首次使用请在停车时验证切歌、接听、拒接和挂断。接听时会先确认 CarPlay 取得输入焦点。电话状态来自 DiPlay，蓝牙通话关闭也可使用。同时开启仪表通话显示时，也尝试转发仪表电话菜单的确定操作；需停车验证。")
            .setPositiveButton("知道了", null).show());
        CheckBox bluetoothMedia = new CheckBox(this); bluetoothMedia.setText("插入 iPhone 时关闭手机蓝牙媒体音频");
        bluetoothMedia.setTextSize(18); bluetoothMedia.setTextColor(Color.parseColor("#E8EDF5"));
        bluetoothMedia.setChecked(preferences.getBoolean(BluetoothMediaGuard.ENABLED, false)); body.addView(bluetoothMedia);
        body.addView(label("默认只关闭媒体，可选择同时断开通话。拔出或关闭此开关时恢复原设置；自动模式仅在一台已配对时执行。", 15, "#91A2B6"));
        Button bluetoothPhone = new Button(this); bluetoothPhone.setText(bluetoothPhoneLabel());
        styleButton(bluetoothPhone, "#2A3A4D", "#E8EDF5"); body.addView(bluetoothPhone);
        bluetoothPhone.setOnClickListener(v -> selectBluetoothPhone(bluetoothPhone));
        CheckBox bluetoothCalls = new CheckBox(this); bluetoothCalls.setText("同时关闭蓝牙通话连接（可选）");
        bluetoothCalls.setTextSize(18); bluetoothCalls.setTextColor(Color.parseColor("#E8EDF5"));
        bluetoothCalls.setChecked(preferences.getBoolean(BluetoothMediaGuard.CALLS, false));
        bluetoothCalls.setEnabled(bluetoothMedia.isChecked()); body.addView(bluetoothCalls);
        bluetoothCalls.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(BluetoothMediaGuard.CALLS, checked).apply(); startReader();
        });
        bluetoothMedia.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(BluetoothMediaGuard.ENABLED, checked).apply();
            bluetoothCalls.setEnabled(checked); startReader();
        });
        body.addView(label("默认关闭，需先开启上方功能。拔出后恢复原通话设置；正在通话时暂缓断开。", 15, "#91A2B6"));
        CheckBox autoEnter = new CheckBox(this); autoEnter.setText("连接后停留在 DiPlay 时自动进入 CarPlay（一次）");
        autoEnter.setChecked(preferences.getBoolean("auto_enter", false)); body.addView(autoEnter);
        autoEnter.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("auto_enter", checked).apply(); startReader(); });
        for (CheckBox check : new CheckBox[]{autostart, nav, remaining, music, autoEnter}) { check.setTextSize(18); check.setTextColor(Color.parseColor("#E8EDF5")); }
        nav.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("nav", checked).apply(); startReader(); });
        music.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("music", checked).apply(); startReader(); });
        TextView entryHint = label("默认关闭，需授权使用情况访问。每次连接最多自动进入一次；CarPlay 已稳定显示后返回首页不再跳转。其他应用在前台时不跳转，90 秒后结束检查。", 15, "#91A2B6");
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
            nav.setChecked(false); music.setChecked(false); calls.setChecked(false); mediaKeys.setChecked(false);
            stopService(new Intent(this, BridgeService.class));
        });
        CheckBox audioRecovery = new CheckBox(this); audioRecovery.setText("首次开机无声时恢复一次");
        audioRecovery.setTextSize(18); audioRecovery.setTextColor(Color.parseColor("#E8EDF5"));
        audioRecovery.setChecked(preferences.getBoolean("startup_audio_recovery", false)); body.addView(audioRecovery);
        audioRecovery.setOnCheckedChangeListener((button, checked) -> { preferences.edit().putBoolean("startup_audio_recovery", checked).apply(); startReader(); });
        body.addView(label("默认关闭，需系统诊断及使用情况授权。核对待机退出、DiPlay 正在播放但焦点缺失，且 CarPlay 在前台时，暂停后继续一次；每次开机最多一次。", 15, "#91A2B6"));
        TextView limits = label("距离为取整值，长文字可能截短。请在车辆停稳时设置与核对。", 15, "#91A2B6");
        limits.setPadding(0, dp(12), 0, 0); body.addView(limits);
        ScrollView statusScroll = new ScrollView(this); statusScroll.setBackground(panel());
        LinearLayout statusBody = new LinearLayout(this); statusBody.setOrientation(LinearLayout.VERTICAL); statusBody.setPadding(dp(20), dp(14), dp(20), dp(18));
        statusBody.addView(label("运行状态 · 2.1.9", 20, "#61D9BB"));
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

    private void showDonationCode() {
        if (donationDialog != null && donationDialog.isShowing()) return;
        int imageId = getResources().getIdentifier("donation_wechat", "drawable", getPackageName());
        if (imageId == 0) { Toast.makeText(this, "收款码暂时无法显示", Toast.LENGTH_LONG).show(); return; }
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout content = new FrameLayout(this); content.setBackgroundColor(Color.WHITE);
        ImageView code = new ImageView(this); code.setContentDescription("作者微信收款码");
        code.setScaleType(ImageView.ScaleType.FIT_CENTER); code.setImageResource(imageId);
        content.addView(code, new FrameLayout.LayoutParams(-1, -1));
        Button close = new Button(this); close.setText("关闭"); styleButton(close, "#2A3A4D", "#E8EDF5");
        FrameLayout.LayoutParams closeLayout = new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.END);
        closeLayout.setMargins(dp(8), dp(8), dp(8), dp(8)); content.addView(close, closeLayout);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(content); dialog.setCanceledOnTouchOutside(true);
        donationDialog = dialog; dialog.setOnDismissListener(ignored -> { if (donationDialog == dialog) donationDialog = null; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            Rect visible = new Rect(); getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            int width = visible.width() > 0 ? visible.width() : getResources().getDisplayMetrics().widthPixels;
            int height = visible.height() > 0 ? visible.height() : getResources().getDisplayMetrics().heightPixels;
            window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            window.setLayout(Math.min(dp(720), Math.round(width * 0.90f)), Math.round(height * 0.96f));
        }
    }

    private void startReader() { startForegroundService(new Intent(this, BridgeService.class)); }
    private void addDiagnostics(LinearLayout body) {
        body.addView(label("车辆环境、音乐与导航诊断", 20, "#61D9BB"));
        body.addView(label("1. 车辆停稳后连接 CarPlay，开启下方需要检查的导航、音乐转发。\n"
            + "2. 点“开始诊断”，在 iPhone 高德中选择目的地并点“开始导航”；只打开地图不够。\n"
            + "3. 在 CarPlay 播放音乐并切歌一次，观察仪表是否显示。可切回 CarPlay，诊断会在后台继续。\n"
            + "4. 3 分钟后自动保存，也可回来点“结束并保存”。行车时请勿操作或查看诊断界面。", 16, "#AAB7C7"));
        body.addView(label("首次需允许文件存储，才能保存到车机下载文件夹。日志含道路、歌曲及固件信息；不录音、不上传，不保存联系人或通话内容。", 15, "#91A2B6"));
        diagnosticStart = new Button(this); diagnosticStart.setText("开始诊断（3 分钟）");
        styleButton(diagnosticStart, "#61D9BB", "#10201D"); body.addView(diagnosticStart);
        diagnosticStart.setOnClickListener(v -> diagnosticCommand(VehicleDiagnostics.START));
        diagnosticFinish = new Button(this); diagnosticFinish.setText("结束并保存");
        styleButton(diagnosticFinish, "#2A3A4D", "#E8EDF5"); body.addView(diagnosticFinish);
        diagnosticFinish.setOnClickListener(v -> diagnosticCommand(VehicleDiagnostics.FINISH));
        diagnosticRetry = new Button(this); diagnosticRetry.setText("重新保存日志");
        styleButton(diagnosticRetry, "#2A3A4D", "#E8EDF5"); body.addView(diagnosticRetry);
        diagnosticRetry.setOnClickListener(v -> diagnosticCommand(VehicleDiagnostics.RETRY));
        diagnosticStatus = label("", 16, "#E8EDF5"); diagnosticStatus.setTextIsSelectable(true);
        diagnosticStatus.setPadding(0, dp(8), 0, dp(18)); body.addView(diagnosticStatus);
        refreshDiagnostics();
    }
    private void refreshDiagnostics() {
        if (diagnosticStatus == null) return;
        String phase = preferences.getString(VehicleDiagnostics.PHASE, "idle");
        boolean recording = "recording".equals(phase), saving = "saving".equals(phase);
        diagnosticStart.setEnabled(!recording && !saving);
        diagnosticFinish.setEnabled(recording);
        diagnosticRetry.setVisibility("save_failed".equals(phase) ? android.view.View.VISIBLE : android.view.View.GONE);
        diagnosticRetry.setEnabled(!recording && !saving);
        diagnosticStatus.setText(preferences.getString(VehicleDiagnostics.STATUS, "尚未开始。请按上方步骤准备，再点“开始诊断”。"));
    }
    private void diagnosticCommand(String action) {
        if (!VehicleDiagnostics.FINISH.equals(action) && Build.VERSION.SDK_INT <= 28
            && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDiagnosticAction = action;
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 31); return;
        }
        startForegroundService(new Intent(this, BridgeService.class).setAction(action));
        diagnosticStart.setEnabled(false);
    }
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 31) return;
        String action = pendingDiagnosticAction; pendingDiagnosticAction = null;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && action != null) diagnosticCommand(action);
        else Toast.makeText(this, "未开始诊断：请在应用权限中允许文件存储，再点开始或重新保存。", Toast.LENGTH_LONG).show();
    }
    protected void onSaveInstanceState(Bundle state) {
        state.putString("diagnostic_pending_action", pendingDiagnosticAction); super.onSaveInstanceState(state);
    }
    private String bluetoothPhoneLabel() {
        String address = preferences.getString(BluetoothMediaGuard.TARGET, "");
        if (address.isEmpty()) return "蓝牙手机：自动选择唯一已配对设备";
        for (BluetoothDevice device : BluetoothMediaGuard.devices()) if (address.equals(device.getAddress()))
            return "蓝牙手机：" + BluetoothMediaGuard.deviceLabel(device);
        return "蓝牙手机：原设备未配对，请重新选择";
    }
    private void selectBluetoothPhone(Button button) {
        if (!BluetoothMediaGuard.supported()) {
            Toast.makeText(this, "当前仅适配 Android 8.0/8.1 的蓝牙媒体控制", Toast.LENGTH_LONG).show(); return;
        }
        List<BluetoothDevice> devices = BluetoothMediaGuard.devices();
        String[] choices = new String[devices.size() + 1]; choices[0] = "自动选择唯一已配对设备";
        String current = preferences.getString(BluetoothMediaGuard.TARGET, ""); int selected = 0;
        for (int i = 0; i < devices.size(); i++) {
            choices[i + 1] = BluetoothMediaGuard.deviceLabel(devices.get(i));
            if (current.equals(devices.get(i).getAddress())) selected = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("选择此 iPhone 的蓝牙配对").setSingleChoiceItems(choices, selected, (dialog, which) -> {
            preferences.edit().putString(BluetoothMediaGuard.TARGET, which == 0 ? "" : devices.get(which - 1).getAddress()).apply();
            button.setText(bluetoothPhoneLabel()); startReader(); dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    protected void onActivityResult(int req, int result, Intent data) { super.onActivityResult(req, result, data); startReader(); }
    protected void onResume() { super.onResume(); handler.post(refresh); }
    protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    protected void onDestroy() {
        if (donationDialog != null) { donationDialog.dismiss(); donationDialog = null; }
        super.onDestroy();
    }
}
