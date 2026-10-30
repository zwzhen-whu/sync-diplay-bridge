package com.local.diplay.clusterbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone public-widget host and cluster broadcast adapter. */
public final class BridgeService extends Service {
    static final String SOURCE = "com.shihab.diplay";
    static final String CLUSTER = "com.baidu.xiaoduos.instrumentcluster";
    static final ComponentName PROVIDER = new ComponentName(SOURCE, "com.shilapi.xcertplay.NavigationWidget");
    private static final String CLUSTER_SHA256 = "7FBAE817B7DB24642D8959C347B561C003C6CFDE2CB10F909C932F21A4D6D804";
    private static final String ASSISTANT_SHA1 = "363F5D52145070AC479B268C3EC817B371AAD331";
    private static final long NAV_TIMEOUT = 5000;
    private static final long HOST_RETRY_INTERVAL = 10000;
    private final Handler handler = new Handler();
    private SharedPreferences preferences;
    private AppWidgetHost host;
    private AppWidgetHostView widgetView;
    private Resources sourceResources;
    private final Map<String, int[]> arrows = new LinkedHashMap<>();
    private String sourceVersion = "未知", reason = "等待小组件数据", distance = "", road = "", turn;
    private String receiverError;
    private Integer meters;
    private Protocol.Song song;
    private boolean connected, ownedNav, ownedMusic;
    private long lastUpdate;
    private long lastHostAttempt;
    private int updates, navSent, musicSent;
    private long lastStatusWrittenAt = -1;
    private int hostGeneration;
    private NavigationLogReader navigationLogs;
    private String outputRoad = "", roadSource = "暂无";
    private String lastDiagnosticRoad;
    private final MusicDelivery musicDelivery = new MusicDelivery();
    private final NavigationDelivery navigationDelivery = new NavigationDelivery();
    private final RemainingDelivery remainingDelivery = new RemainingDelivery();
    private final List<RemainingTrip.Formats> remainingFormats = new ArrayList<>();
    private RemainingTrip remainingTrip;
    private String widgetEta = "";
    private int remainingSent;
    private CarPlayAutoEntry autoEntry;
    private StartupAudioRecovery audioRecovery;
    private VehicleDiagnostics diagnostics;
    private BluetoothMediaGuard bluetoothMedia;
    private BluetoothMediaGuard bluetoothCalls;
    private CarPlayCallDisplay callDisplay;
    private BridgeMediaKeys mediaKeys;
    private com.local.diplay.clusterbridge.audio.CallAudioController callAudio;
    private com.local.diplay.clusterbridge.audio.MicPacketController micPackets;
    private com.local.diplay.clusterbridge.audio.MediaVolumeMemory mediaVolume;
    private boolean destroyed;
    private final Runnable logChanged = () -> {
        if (!destroyed) {
            // Log frames also describe future turns. They update the cache, not
            // the instrument, unless a current widget is waiting for its road.
            if (navigationDelivery.retryDelay(SystemClock.elapsedRealtime()) >= 0) forward();
            publish();
        }
    };
    private final Runnable navigationRetry = () -> { if (!destroyed) { forward(); publish(); } };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String name = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
            if (SOURCE.equals(name) || CLUSTER.equals(name) || "org.savior.sync".equals(name)) {
                clearNav(); clearMusic(); connected = false; song = null; lastUpdate = 0; updateAutoEntry();
                if (navigationLogs != null) navigationLogs.stop();
                if (audioRecovery != null) audioRecovery.tick(false, false, 0);
                navigationLogs = new NavigationLogReader(BridgeService.this, handler, logChanged, audioRecovery, callDisplay, diagnostics);
                navigationLogs.ensureStarted();
                receiverError = validateReceiver(); initializeHost();
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (navigationLogs != null) navigationLogs.ensureStarted();
            // At boot the provider or widget service may not yet be available.
            if (widgetView == null && SystemClock.elapsedRealtime() - lastHostAttempt >= HOST_RETRY_INTERVAL) {
                receiverError = validateReceiver(); initializeHost();
            }
            if (lastUpdate > 0 && meters != null && SystemClock.elapsedRealtime() - lastUpdate > NAV_TIMEOUT) {
                meters = null; turn = null; reason = "导航小组件超过 5 秒未更新，停止导航转发"; clearNav();
            }
            if (!preferences.getBoolean("nav", false)) clearNav();
            forward();
            updateAutoEntry();
            if (bluetoothMedia != null) bluetoothMedia.tick();
            if (bluetoothCalls != null) bluetoothCalls.tick();
            if (callDisplay != null) callDisplay.configure(receiverError == null, connected);
            if (mediaKeys != null) mediaKeys.configure(receiverError == null, connected);
            if (callAudio != null) callAudio.configure(receiverError == null);
            if (micPackets != null) micPackets.configure(receiverError == null);
            if (audioRecovery != null) audioRecovery.tick(connected, song != null && song.playing, lastUpdate);
            diagnosticSnapshot(false);
            publish(); handler.postDelayed(this, 1000);
        }
    };

    public void onCreate() {
        super.onCreate(); preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        mediaVolume = new com.local.diplay.clusterbridge.audio.MediaVolumeMemory(this, preferences);
        com.local.diplay.clusterbridge.audio.NativeBootVolumeController.recoverPending(this);
        com.local.diplay.clusterbridge.homemap.HomeMapController.restoreIfEnabled(this);
        preferences.edit().remove("wake_diagnostics").apply();
        diagnostics = VehicleDiagnostics.get(this, preferences);
        String oldRoadMode = preferences.getString("road_display_mode", Protocol.ROAD_ORIGINAL);
        if ("first".equals(oldRoadMode) || "comma".equals(oldRoadMode))
            preferences.edit().putString("road_display_mode", Protocol.ROAD_CLEAN).apply();
        autoEntry = new CarPlayAutoEntry(this);
        audioRecovery = new StartupAudioRecovery(this, preferences);
        bluetoothMedia = new BluetoothMediaGuard(this, preferences);
        bluetoothCalls = new BluetoothMediaGuard(this, preferences, true);
        callDisplay = new CarPlayCallDisplay(this, preferences);
        callAudio = new com.local.diplay.clusterbridge.audio.CallAudioController(this, preferences);
        micPackets = new com.local.diplay.clusterbridge.audio.MicPacketController(this, preferences);
        mediaKeys = new BridgeMediaKeys(this, preferences, handler, callDisplay);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("bridge", "CarPlay 仪表桥接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("CarPlay 仪表桥接正在读取小组件").setContentText("打开查看状态或停止转发").setContentIntent(open).build());
        receiverError = validateReceiver();
        navigationLogs = new NavigationLogReader(this, handler, logChanged, audioRecovery, callDisplay, diagnostics);
        navigationLogs.ensureStarted();
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_PACKAGE_REPLACED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED); filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addDataScheme("package");
        registerReceiver(packages, filter);
        initializeHost(); handler.post(watchdog);
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && diagnostics != null) {
            if (VehicleDiagnostics.START.equals(intent.getAction())) diagnostics.start();
            else if (VehicleDiagnostics.FINISH.equals(intent.getAction())) diagnostics.finish("用户结束并保存");
            else if (VehicleDiagnostics.RETRY.equals(intent.getAction())) diagnostics.retry();
        }
        if (bluetoothMedia != null) bluetoothMedia.tick();
        if (bluetoothCalls != null) bluetoothCalls.tick();
        if (callDisplay != null) callDisplay.configure(receiverError == null, connected);
        if (mediaKeys != null) mediaKeys.configure(receiverError == null, connected);
        if (callAudio != null) callAudio.configure(receiverError == null);
        if (micPackets != null) micPackets.configure(receiverError == null);
        if (widgetView == null) initializeHost();
        if (!preferences.getBoolean("nav", false)) clearNav();
        if (!preferences.getBoolean("music", false)) clearMusic();
        forward(); diagnosticSnapshot(false); publish();
        return START_NOT_STICKY;
    }

    private void diagnosticSnapshot(boolean update) {
        if (diagnostics != null) diagnostics.snapshot(update, widgetView != null, connected,
            preferences.getBoolean("nav", false), preferences.getBoolean("music", false), receiverError,
            navigationLogs == null ? "未启动" : navigationLogs.status(), distance, road, turn, meters, song,
            outputRoad, roadSource, lastUpdate == 0 ? -1 : SystemClock.elapsedRealtime() - lastUpdate);
    }
    private void diagnosticDecision(boolean navigation, String detail) {
        if (diagnostics != null) diagnostics.decision(navigation, detail);
    }

    @SuppressWarnings("deprecation")
    private String signature(String name, String algorithm) throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNATURES);
        if (info.signatures == null || info.signatures.length != 1) return "";
        byte[] bytes = MessageDigest.getInstance(algorithm).digest(info.signatures[0].toByteArray());
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        return result.toString();
    }

    private String validateReceiver() {
        try {
            if (!CLUSTER_SHA256.equals(signature(CLUSTER, "SHA-256"))) return "仪表应用签名变化，禁止转发";
            if (!ASSISTANT_SHA1.equals(signature("org.savior.sync", "SHA-1"))) return "车机助手签名变化或未安装，禁止转发";
            return null;
        } catch (Exception e) { return "无法验证仪表/车机助手：" + e.getClass().getSimpleName(); }
    }

    private void initializeHost() {
        lastHostAttempt = SystemClock.elapsedRealtime();
        final int generation = ++hostGeneration;
        stopHost();
        widgetView = null; arrows.clear(); meters = null; turn = null;
        remainingTrip = null; widgetEta = ""; remainingFormats.clear();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(SOURCE, 0); sourceVersion = info.versionName;
            sourceResources = getPackageManager().getResourcesForApplication(SOURCE);
            initializeRemainingFormats();
            for (String[] arrow : Protocol.ARROWS) {
                int id = sourceResources.getIdentifier(arrow[0], "drawable", SOURCE);
                if (id == 0) throw new IllegalStateException("DiPlay 缺少箭头资源 " + arrow[0]);
                arrows.put(arrow[0], pixels(sourceResources.getDrawable(id, null)));
            }
            for (String name : new String[]{"widget_arrow", "widget_distance", "widget_road", "widget_song"}) {
                if (sourceResources.getIdentifier(name, "id", SOURCE) == 0) throw new IllegalStateException("DiPlay 小组件接口变化：" + name);
            }
            host = new AppWidgetHost(this, 4201) {
                protected AppWidgetHostView onCreateView(Context context, int widgetId, AppWidgetProviderInfo widgetInfo) {
                    return new AppWidgetHostView(context) {
                        public void updateAppWidget(RemoteViews views) {
                            if (destroyed || generation != hostGeneration) return;
                            super.updateAppWidget(views);
                            if (views != null) readWidget(this);
                        }
                    };
                }
            };
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            int savedId = preferences.getInt("widget", -1);
            // Uninstalling DiPlay removes the system-side widget, but our preference
            // survives. An absent ID cannot be bound again; allocate a new host ID.
            int id = WidgetBindingIds.reusable(savedId, host.getAppWidgetIds());
            AppWidgetProviderInfo bound = id == -1 ? null : manager.getAppWidgetInfo(id);
            if (bound != null && !PROVIDER.equals(bound.provider)) { host.deleteAppWidgetId(id); id = -1; }
            if (id == -1) {
                id = host.allocateAppWidgetId(); preferences.edit().putInt("widget", id).commit();
                Log.i("DiPlayBridge", "widget ID allocated=" + id + " previous=" + savedId);
            }
            for (int previous : host.getAppWidgetIds()) if (previous != id) host.deleteAppWidgetId(previous);
            if (manager.getAppWidgetInfo(id) == null && !manager.bindAppWidgetIdIfAllowed(id, PROVIDER)) {
                reason = "尚未授权小组件，请点授权按钮或执行文档中的 ADB grantbind"; publish(); return;
            }
            AppWidgetProviderInfo provider = manager.getAppWidgetInfo(id);
            if (provider == null) throw new IllegalStateException("DiPlay 导航小组件未注册");
            Bundle options = new Bundle(); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 700); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220);
            manager.updateAppWidgetOptions(id, options);
            widgetView = host.createView(this, id, provider); host.startListening();
        } catch (Exception e) {
            stopHost(); widgetView = null; connected = false; song = null; lastUpdate = 0;
            clearNav(); clearMusic(); updateAutoEntry();
            reason = "小组件读取失败，将重试：" + e.getMessage(); Log.e("DiPlayBridge", reason, e); publish();
        }
    }

    private void stopHost() {
        if (host == null) return;
        try { host.stopListening(); }
        catch (RuntimeException e) { Log.w("DiPlayBridge", "Widget listener stop failed", e); }
        host = null;
    }

    private static int[] pixels(Drawable drawable) {
        if (drawable == null) throw new IllegalStateException("箭头为空");
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 64, 64); drawable.draw(new Canvas(bitmap)); drawable.setBounds(previous);
        int[] pixels = new int[4096]; bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64); bitmap.recycle(); return pixels;
    }

    private String recognize(ImageView image) {
        if (image == null || image.getVisibility() != View.VISIBLE || image.getColorFilter() != null) return null;
        int[] actual = pixels(image.getDrawable()); String matched = null;
        for (String[] arrow : Protocol.ARROWS) {
            if (Arrays.equals(actual, arrows.get(arrow[0]))) {
                if (matched != null && !matched.equals(arrow[1])) return null;
                matched = arrow[1];
            }
        }
        return matched;
    }

    private View view(View root, String name) { return root.findViewById(sourceResources.getIdentifier(name, "id", SOURCE)); }
    private String text(View root, String name) {
        View child = view(root, name);
        if (!(child instanceof TextView)) throw new IllegalStateException("缺少文本 " + name);
        return child.getVisibility() == View.VISIBLE ? ((TextView) child).getText().toString() : "";
    }

    private String sourceString(Resources resources, String name) {
        int id = resources.getIdentifier(name, "string", SOURCE);
        return id == 0 ? null : resources.getString(id);
    }

    private void initializeRemainingFormats() throws PackageManager.NameNotFoundException {
        // DiPlay can select its language independently of the head unit. All templates
        // still come from the installed APK, including fallback resources for each locale.
        Context sourceContext = createPackageContext(SOURCE, 0);
        for (String locale : new String[]{"", "en", "zh-CN", "zh-TW", "ar", "ja", "ru", "uk", "es"}) {
            Configuration config = new Configuration(sourceResources.getConfiguration());
            if (!locale.isEmpty()) config.setLocale(Locale.forLanguageTag(locale));
            Resources resources = sourceContext.createConfigurationContext(config).getResources();
            remainingFormats.add(new RemainingTrip.Formats(sourceString(resources, "widget_distance_meters"),
                sourceString(resources, "widget_distance_km_decimal"), sourceString(resources, "widget_distance_km"),
                sourceString(resources, "widget_minutes"), sourceString(resources, "widget_hours_minutes")));
        }
    }

    private RemainingTrip readRemainingTrip(String text) {
        for (RemainingTrip.Formats format : remainingFormats) {
            RemainingTrip value = format.parse(text);
            if (value != null) return value;
        }
        return null;
    }

    private String optionalText(View root, String name) {
        View child = view(root, name);
        return child instanceof TextView && child.getVisibility() == View.VISIBLE ? ((TextView) child).getText().toString() : "";
    }

    private void readWidget(View root) {
        try {
            distance = text(root, "widget_distance"); road = text(root, "widget_road");
            if (!road.equals(lastDiagnosticRoad)) {
                lastDiagnosticRoad = road;
                Log.i("DiPlayBridge", "Road characters: " + Protocol.roadDiagnostic(road));
            }
            widgetEta = optionalText(root, "widget_eta");
            remainingTrip = readRemainingTrip(widgetEta);
            song = Protocol.song(text(root, "widget_song")); connected = !"DiPlay".equals(distance);
            lastUpdate = SystemClock.elapsedRealtime(); updates++; meters = Protocol.meters(distance);
            View image = view(root, "widget_arrow"); turn = image instanceof ImageView ? recognize((ImageView) image) : null;
            if (!connected) { meters = null; turn = null; song = null; if (navigationLogs != null) navigationLogs.clearState(); reason = "DiPlay 未连接 CarPlay"; }
            else if ("CarPlay".equals(distance)) { meters = null; turn = null; reason = "CarPlay 已连接，暂无导航指令"; }
            else if (meters == null || !Protocol.knownTurn(turn)) { meters = null; turn = null; reason = "无法识别当前箭头或距离，停止导航转发"; }
            else reason = "已读取真实导航指令（距离为小组件显示值）";
            forward(); diagnosticSnapshot(true); updateAutoEntry(); publish();
            Log.i("DiPlayBridge", "widget update=" + updates + " connected=" + connected + " turn=" + turn + " meters=" + meters + " song=" + (song != null) + " navSent=" + navSent + " musicSent=" + musicSent);
        } catch (Exception e) {
            remainingTrip = null; widgetEta = "";
            meters = null; turn = null; song = null; connected = false; clearNav(); clearMusic(); updateAutoEntry();
            reason = "小组件数据解析失败：" + e.getMessage();
            diagnosticDecision(true, "小组件解析失败：" + e.getClass().getSimpleName()); diagnosticSnapshot(true);
            publish(); Log.e("DiPlayBridge", reason, e);
        }
    }

    private Intent clusterIntent(String action) { return new Intent(action).setPackage(CLUSTER); }

    private void forward() {
        if (receiverError != null) {
            diagnosticDecision(true, "阻止转发：" + receiverError); diagnosticDecision(false, "阻止转发：" + receiverError);
            clearNav(); clearMusic(); return;
        }
        boolean fresh = lastUpdate > 0 && SystemClock.elapsedRealtime() - lastUpdate <= NAV_TIMEOUT;
        if (preferences.getBoolean("nav", false) && connected && fresh && meters != null && Protocol.knownTurn(turn)) {
            String candidateRoad = Protocol.roadName(road), candidateSource = "DiPlay 小组件";
            if (candidateRoad.isEmpty() && navigationLogs != null) {
                candidateRoad = navigationLogs.state.road(turn, meters, System.currentTimeMillis());
                candidateSource = candidateRoad.isEmpty() ? "缺少有效路名" : "DiPlay 导航说明（日志回退）";
            }
            String displayRoad = Protocol.clusterRoad(candidateRoad, preferences.getString("road_display_mode", Protocol.ROAD_ORIGINAL));
            // Keep missing data missing for the existing bounded wait. Identity
            // follows the actual display text so toggling the option can resend.
            String deliveryRoad = candidateRoad.isEmpty() ? "" : displayRoad;
            long now = SystemClock.elapsedRealtime();
            NavigationDelivery.Action action = navigationDelivery.next(turn, meters, deliveryRoad, now);
            diagnosticDecision(true, "decision=" + action + " turn=" + turn + " meters=" + meters + " candidate=" + candidateRoad
                + " display=" + displayRoad + " road_source=" + candidateSource + "；WAIT=等待路名或最短发送间隔，NONE=未变化且未到心跳时间");
            handler.removeCallbacks(navigationRetry);
            if (action == NavigationDelivery.Action.WAIT) {
                roadSource = "等待导航说明与小组件同步（最多 1.5 秒）";
                handler.postDelayed(navigationRetry, navigationDelivery.retryDelay(now));
            } else if (action == NavigationDelivery.Action.SEND) {
                if (!ownedNav) { sendCluster(clusterIntent("com.baidu.car.navi.STATE").putExtra("state", "TRUE")); ownedNav = true; }
                outputRoad = displayRoad; roadSource = candidateSource;
                sendCluster(clusterIntent("com.baidu.car.navi.INFO").putExtra("name", turn).putExtra("distance", meters.intValue()).putExtra("nextRoadName", outputRoad)); navSent++;
                navigationDelivery.sent(turn, meters, deliveryRoad, now);
            } else roadSource = candidateSource;
            forwardRemaining(ownedNav);
        } else {
            diagnosticDecision(true, !preferences.getBoolean("nav", false) ? "导航转发关闭" : !connected ? "CarPlay 未连接"
                : !fresh ? "小组件无更新或超过 5 秒" : meters == null ? "距离缺失或解析失败，或暂无导航" : "箭头未识别");
            clearNav(); outputRoad = ""; roadSource = "暂无有效导航转发";
        }
        forwardMusic();
    }

    private boolean canForwardMusic() {
        return !destroyed && receiverError == null && widgetView != null
            && preferences.getBoolean("music", false) && connected && song != null;
    }

    private void forwardMusic() {
        if (!canForwardMusic()) {
            diagnosticDecision(false, !preferences.getBoolean("music", false) ? "音乐转发关闭" : !connected ? "CarPlay 未连接"
                : widgetView == null ? "小组件未就绪" : "未读取到歌曲");
            clearMusic(); return;
        }
        Protocol.Song output = outputSong();
        MusicDelivery.Action action = musicDelivery.next(output);
        diagnosticDecision(false, action == MusicDelivery.Action.SEND ? "歌曲或播放状态变化，提交分发" : "歌曲与播放状态未变化，正常去重；请切歌以记录新的分发");
        if (action == MusicDelivery.Action.SEND) sendCurrentMusic(output);
    }

    private Protocol.Song outputSong() {
        return MusicTextPolicy.display(song, preferences.getBoolean(MusicTextPolicy.LYRICS_ONLY, false));
    }
    private void sendCurrentMusic(Protocol.Song output) {
        sendMusic(output.text, output.playing); ownedMusic = true;
        musicDelivery.sent(output);
    }

    private void updateAutoEntry() {
        View root = null;
        if (widgetView != null && sourceResources != null) {
            int id = sourceResources.getIdentifier("widget_root", "id", SOURCE);
            if (id != 0) root = widgetView.findViewById(id);
        }
        if (autoEntry != null) autoEntry.tick(preferences.getBoolean("auto_enter", false),
            receiverError == null && widgetView != null && connected
                && ("CarPlay".equals(distance) || Protocol.meters(distance) != null), root);
    }

    private Intent musicIntent(String title, boolean playing) {
        return clusterIntent("com.baidu.car.radio.notify.PLAY_INFO").putExtra("id", "diplay-widget")
            .putExtra("title", title).putExtra("artist", "").putExtra("album_name", "").putExtra("source_name", "CarPlay")
            .putExtra("source_type", 1).putExtra("is_playing", playing);
    }

    private void sendMusic(String title, boolean playing) {
        sendCluster(musicIntent(title, playing)); musicSent++;
    }

    private void sendCluster(Intent intent) {
        String action = intent.getAction(), payload = "";
        if (diagnostics != null && diagnostics.active()) {
            if ("com.baidu.car.navi.INFO".equals(action)) payload = "name=" + intent.getStringExtra("name")
                + " distance=" + intent.getIntExtra("distance", -1) + " nextRoadName=" + intent.getStringExtra("nextRoadName");
            else if ("com.baidu.car.navi.STATE".equals(action)) payload = "state=" + intent.getStringExtra("state");
            else if ("com.baidu.car.navi.REMAIN".equals(action)) payload = "remainDistance=" + intent.getIntExtra("remainDistance", -1)
                + " remainTime=" + intent.getIntExtra("remainTime", -1);
            else if ("com.baidu.car.radio.notify.PLAY_INFO".equals(action)) payload =
                ("".equals(intent.getStringExtra("title")) ? "clear=true " : "") + "title=" + intent.getStringExtra("title")
                + " is_playing=" + intent.getBooleanExtra("is_playing", false);
            payload = "target=" + CLUSTER + " " + payload;
        }
        try { sendBroadcast(intent); }
        catch (RuntimeException e) {
            if (diagnostics != null) diagnostics.dispatch(action, payload + " exception=" + e.getClass().getSimpleName(), false);
            throw e;
        }
        if (diagnostics != null) diagnostics.dispatch(action, payload, true);
    }

    private void forwardRemaining(boolean activeNavigation) {
        RemainingDelivery.Action action = remainingDelivery.next(preferences.getBoolean("lincoln_remaining", false), activeNavigation, remainingTrip);
        if (action == RemainingDelivery.Action.NONE) return;
        boolean send = action == RemainingDelivery.Action.SEND;
        // The OEM receiver requires both integer fields: meters and seconds, never minutes.
        sendCluster(clusterIntent("com.baidu.car.navi.REMAIN")
            .putExtra("remainDistance", send ? remainingTrip.meters : 0)
            .putExtra("remainTime", send ? remainingTrip.seconds : 0));
        remainingSent++;
    }

    private void clearNav() {
        handler.removeCallbacks(navigationRetry); navigationDelivery.reset();
        forwardRemaining(false);
        if (ownedNav) { sendCluster(clusterIntent("com.baidu.car.navi.STATE").putExtra("state", "FALSE")); ownedNav = false; }
    }
    private void clearMusic() {
        musicDelivery.reset();
        if (ownedMusic) { sendMusic("", false); ownedMusic = false; }
    }

    private void publish() {
        long now = SystemClock.elapsedRealtime();
        if (lastStatusWrittenAt >= 0 && now - lastStatusWrittenAt < 1000) return;
        lastStatusWrittenAt = now;
        String age = lastUpdate == 0 ? "尚未收到" : (SystemClock.elapsedRealtime() - lastUpdate) / 1000 + " 秒前";
        Protocol.Song musicOutput = outputSong();
        String status = "仪表接口：" + (receiverError == null ? "就绪" : receiverError)
            + "\n仪表道路：" + (outputRoad.isEmpty() ? "暂无导航" : outputRoad)
            + "\n距离：" + distance + "\n方向：" + turnLabel(turn)
            + "\n歌曲：" + (song == null ? "暂无" : song.text + (song.playing ? "（播放）" : "（暂停）"))
            + "\n仪表音乐文字：" + (musicOutput == null ? "暂无" : musicOutput.text)
            + "\n音乐文字模式：" + (preferences.getBoolean(MusicTextPolicy.LYRICS_ONLY, false) ? "只显示歌词" : "完整信息")
            + "\n音乐同步：首次读取及歌曲变化时推送"
            + "\n开机无声恢复：" + (audioRecovery == null ? "关闭" : audioRecovery.status())
            + "\n车辆环境诊断：" + preferences.getString(VehicleDiagnostics.PHASE, "未开始")
            + "\n林肯剩余信息：" + (!preferences.getBoolean("lincoln_remaining", false) ? "关闭" :
                !preferences.getBoolean("nav", false) ? "等待开启导航转发" : !ownedNav ? "暂无有效导航" : remainingTrip == null ? "里程/时间缺失或格式不支持" :
                remainingTrip.meters + " 米 / " + remainingTrip.seconds / 60 + " 分钟（显示取整值）")
            + "\n自动进入 CarPlay：" + (autoEntry == null ? "未启动" : autoEntry.status())
            + "\n蓝牙媒体音频：" + (bluetoothMedia == null ? "未启动" : bluetoothMedia.status())
            + "\n蓝牙通话连接：" + (bluetoothCalls == null ? "未启动" : bluetoothCalls.status())
            + "\nCarPlay 通话显示：" + (callDisplay == null ? "未启动" : callDisplay.status())
            + "\nCarPlay 通话音量：" + (callAudio == null ? "未启动" : callAudio.status())
            + "\n有线微信语音兼容：" + (micPackets == null ? "未启动" : micPackets.status())
            + "\n媒体音量记忆：" + (mediaVolume == null ? "未启动" : mediaVolume.status())
            + "\n上一曲／下一曲入口：" + (mediaKeys == null ? "未启动" : mediaKeys.status())
            + "\n\n" + reason
            + "\n开机自启动：" + (preferences.getBoolean("autostart", true) ? "开启" : "关闭")
            + (preferences.getString("auto_start_error", "").isEmpty() ? "" : "\n自启动失败：" + preferences.getString("auto_start_error", ""))
            + "\nDiPlay 版本：" + sourceVersion
            + "\n小组件更新：" + updates + " 次，最近 " + age
            + "\n源道路：" + (Protocol.roadName(road).isEmpty() ? "（未提供）" : road)
            + "\n源剩余信息：" + (widgetEta.isEmpty() ? "（未提供）" : widgetEta)
            + "\n路名来源：" + roadSource
            + "\n仪表道路显示：" + (Protocol.ROAD_CLEAN.equals(Protocol.roadDisplayMode(preferences.getString("road_display_mode", Protocol.ROAD_ORIGINAL))) ? "去掉提示语，保留全部路名" : "保留原文")
            + "\n导航日志：" + (navigationLogs == null ? "未启动" : navigationLogs.status())
            + "\n导航发送：" + navSent + " 次；音乐发送：" + musicSent + " 次"
            + "\n剩余信息发送：" + remainingSent + " 次（含清除）"
            + "\n仪表实际显示请在车辆停稳时核对。";
        preferences.edit().putString("status", status).apply();
    }

    private String turnLabel(String value) {
        if (value == null) return "暂无指令";
        switch (value) {
            case "turn_left": return "左转"; case "turn_right": return "右转";
            case "turn_left_front": return "向左前方"; case "turn_right_front": return "向右前方";
            case "turn_left_back": return "向左后方"; case "turn_right_back": return "向右后方";
            case "turn_back": return "掉头"; case "turn_ring": return "环岛";
            case "turn_dest": return "到达目的地"; case "turn_front": return "直行";
            default: return "暂无指令";
        }
    }

    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null); clearNav(); clearMusic();
        if (navigationLogs != null) navigationLogs.stop();
        if (mediaKeys != null) mediaKeys.close();
        if (callDisplay != null) callDisplay.close();
        if (diagnostics != null) diagnostics.close();
        if (audioRecovery != null) audioRecovery.close();
        if (callAudio != null) callAudio.close();
        if (micPackets != null) micPackets.close();
        if (mediaVolume != null) mediaVolume.close();
        if (bluetoothMedia != null) bluetoothMedia.close();
        if (bluetoothCalls != null) bluetoothCalls.close();
        stopHost(); unregisterReceiver(packages);
        preferences.edit().putString("status", "读取服务已停止，转发已关闭。下次打开应用可重新启动。").apply();
        super.onDestroy();
    }
    public IBinder onBind(Intent intent) { return null; }
}
