package com.local.diplay.clusterbridge.homemap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Own-content-only presentation surface: receives DiPlay's map, never captures the centre display. */
public final class MapService extends Service {
    private static volatile MapService active;
    static boolean isDisplayReady() {
        MapService service = active;
        return service != null && !service.stopped && service.display != null;
    }
    private static final String TAG = "HomeCarPlayMap";
    private static final String SOURCE = "com.shihab.diplay";
    private static final int SOURCE_WIDTH = 1280, SOURCE_HEIGHT = 480;
    private static final Pattern STREAM = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s+(\\d+):?\\s+\\d+\\s+\\d+\\s+I\\s+DiPlay-Cluster\\s*:\\s*cluster stream active=(true|false)\\s*$");
    private final Handler main = new Handler();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService runtimePoller = Executors.newSingleThreadScheduledExecutor();
    private volatile MapAdbShell runtime;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private FrameLayout card;
    private volatile boolean stopped;
    private MapFollowPosition placement;
    private MapFollowAnchor followAnchor;
    private MapViewport viewport;
    private final Runnable boundsUpdate = this::refreshScreenBounds;
    private final DisplayManager.DisplayListener screenListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { }
        public void onDisplayRemoved(int id) { }
        public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) queueBoundsUpdate(); }
    };
    private MapFollowFrame followFrames;
    private final HomeMapScrollService.Listener scrollListener = snapshot -> {
        if (!stopped && followFrames != null) followFrames.request();
    };
    private final Runnable appearanceUpdate = this::applyAppearance;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener = (prefs, key) -> {
        if ("width".equals(key) || "height".equals(key) || "feather_percent".equals(key)
                || MapWindowMetrics.AVOID_BARS_KEY.equals(key)) {
            main.removeCallbacks(appearanceUpdate); main.post(appearanceUpdate);
        } else if (HomeMapScrollService.FOLLOW_KEY.equals(key)) {
            main.post(() -> {
                if (stopped) return;
                followAnchor = new MapFollowAnchor(prefs.getInt(MapFollowAnchor.KEY, -1));
                if (followFrames != null) followFrames.request();
            });
        }
    };
    private Surface surface;
    private ImageReader idleReader;
    private HandlerThread idleThread;
    private long idleFrames;
    private volatile VirtualDisplay display;
    private final MapDisplayLease displayLease = new MapDisplayLease();
    private SharedPreferences preferences;
    private Process logs;
    private boolean home, stream, shown, iphone;
    private String homePackage, homeActivity;
    private long usageSince, newestUsage, startedAt;
    private int sourceUid;
    private final MapDisplayLease.Port displayPort = new MapDisplayLease.Port() {
        public boolean create(boolean rendering) {
            display = getSystemService(DisplayManager.class).createVirtualDisplay(
                "fission_bg_xdjaVirtualSurface", SOURCE_WIDTH, SOURCE_HEIGHT, 160, rendering ? surface : idleReader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (display == null) return false;
            preferences.edit().putInt("display_id", display.getDisplay().getDisplayId()).apply();
            Log.i(TAG, "Stable map display id=" + display.getDisplay().getDisplayId() + " rendering=" + rendering);
            return true;
        }
        public void render(boolean rendering) {
            display.setSurface(rendering ? surface : idleReader.getSurface());
            Log.i(TAG, "Map output attached=" + rendering + " display=" + display.getDisplay().getDisplayId());
        }
        public void release() {
            if (display != null) { display.release(); display = null; }
            preferences.edit().remove("display_id").apply();
        }
    };

    public void onCreate() {
        super.onCreate(); preferences = getSharedPreferences("home_map", MODE_PRIVATE); active = this;
        followAnchor = new MapFollowAnchor(preferences.getInt(MapFollowAnchor.KEY, -1));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("home-map", "桌面地图", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MapSettingsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(2, new Notification.Builder(this, "home-map").setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("桌面地图浮窗").setContentText("仅在原车桌面显示").setContentIntent(open).build());
        startedAt = System.currentTimeMillis(); usageSince = startedAt - 600_000;
        int boot = Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        stream = boot >= 0 && preferences.getInt("stream_boot", -2) == boot
            && startedAt - preferences.getLong("stream_at", 0) >= 0
            && startedAt - preferences.getLong("stream_at", 0) < 60_000
            && preferences.getBoolean("stream_active", false);
        try {
            if (!preferences.getBoolean("ownership_claimed", false)) throw new IllegalStateException("请从桥接设置接管桌面地图");
            if (!Settings.canDrawOverlays(this) || !MapSettingsActivity.usageAllowed(this)) throw new IllegalStateException("需要浮窗及使用情况访问权限");
            if (checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("需要地图连接状态读取授权");
            sourceUid = getPackageManager().getApplicationInfo(SOURCE, 0).uid;
            android.content.pm.ResolveInfo info = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            homePackage = info == null ? null : info.activityInfo.packageName;
            homeActivity = info == null ? null : info.activityInfo.name;
            if (homePackage == null || homePackage.equals("android")) throw new IllegalStateException("未找到原车桌面");
            createIdleSink(); createCard(); startStreamReader();
            getSystemService(DisplayManager.class).registerDisplayListener(screenListener, main);
            preferences.registerOnSharedPreferenceChangeListener(appearanceListener);
            HomeMapScrollService.listen(scrollListener);
            poller.scheduleWithFixedDelay(this::pollHome, 0, 500, TimeUnit.MILLISECONDS);
            runtimePoller.scheduleWithFixedDelay(this::pollRuntime, 0, 3, TimeUnit.SECONDS);
            status("已开启，等待 CarPlay 地图");
        } catch (Exception error) {
            status("浮窗未启动：" + error.getMessage()); Log.e(TAG, "Map startup failed", error); stopSelf();
        }
    }
    public int onStartCommand(Intent intent, int flags, int id) {
        if (card != null) preferences.edit().putBoolean("enabled", true).apply();
        return card == null ? START_NOT_STICKY : START_STICKY;
    }
    public IBinder onBind(Intent intent) { return null; }
    private void createIdleSink() {
        // A null output stalls the map SurfaceView decoder on this Android 8.1 head unit.
        // Drain into a tiny buffer while hidden so it cannot block the main CarPlay decoder.
        idleThread = new HandlerThread("map-idle-drain"); idleThread.start();
        idleReader = ImageReader.newInstance(64, 24, PixelFormat.RGBA_8888, 3);
        idleReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null && ++idleFrames % 100 == 0)
                    preferences.edit().putLong("idle_frames", idleFrames).apply();
            } catch (IllegalStateException ignored) {
                // Shutdown may already have closed the reader.
            } finally { if (image != null) image.close(); }
        }, new Handler(idleThread.getLooper()));
    }
    private void createCard() {
        windows = getSystemService(WindowManager.class);
        // Migrate once; subsequent launches retain the owner's dragged position.
        if (preferences.getInt("geometry_version", 0) < 2)
            preferences.edit().putInt("width", 520).putInt("height", 340).putInt("geometry_version", 2).apply();
        int width = Math.max(360, Math.min(1000, preferences.getInt("width", 520)));
        int height = Math.max(240, Math.min(560, preferences.getInt("height", 340)));
        viewport = MapWindowMetrics.read(this, null, false);
        placement = new MapFollowPosition(viewport);
        placement.geometry(preferences.getInt("x", 24), preferences.getInt("y", 296), width, height);
        width = placement.width(); height = placement.height();
        params = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = placement.baseX(); params.y = placement.baseY();
        params.alpha = 0f; params.setTitle("CarPlay home map");
        card = new SoftMapCard(this);
        card.setOnApplyWindowInsetsListener((view, insets) -> {
            if (preferences.getBoolean(MapWindowMetrics.AVOID_BARS_KEY, false)) queueBoundsUpdate();
            return insets;
        });
        ((SoftMapCard)card).setFeatherPercent(preferences.getInt("feather_percent", 100));
        TextureView video = new TextureView(this);
        video.setOpaque(false);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                try {
                    texture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT); surface = new Surface(texture);
                    fitMap(video, width, height);
                    updateVisibility();
                } catch (RuntimeException error) { status("地图区域创建失败"); Log.e(TAG, "Display failed", error); stopSelf(); }
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
                texture.setDefaultBufferSize(SOURCE_WIDTH, SOURCE_HEIGHT); fitMap(video, w, h);
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                displayLease.update(false, false, displayPort);
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
        });
        card.addView(video, new FrameLayout.LayoutParams(-1, -1));
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        card.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int left, top; boolean moved;
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY(); left = placement.baseX(); top = placement.baseY(); moved = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - x, dy = event.getRawY() - y;
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true;
                        placement.geometry(left + (int)dx, top + (int)dy, params.width, params.height);
                        applyPlacement(false);
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (moved) preferences.edit().putInt("x", placement.baseX()).putInt("y", placement.baseY()).apply();
                    else try {
                        startActivity(new Intent().setClassName(SOURCE, "com.shilapi.xcertplay.CarPlayHostActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    } catch (RuntimeException error) { Log.w(TAG, "Could not open CarPlay", error); }
                }
                return true;
            }
        });
        windows.addView(card, params);
        followFrames = new MapFollowFrame(new MapFollowFrame.Port() {
            public void post(Runnable frame) { card.postOnAnimation(frame); }
            public void remove(Runnable frame) { card.removeCallbacks(frame); }
            public void apply() { applyPlacement(false); }
        });
        card.requestApplyInsets(); queueBoundsUpdate();
    }
    private void applyAppearance() {
        if (stopped || card == null || params == null) return;
        int width = Math.max(360, Math.min(1000, preferences.getInt("width", 520)));
        int height = Math.max(240, Math.min(560, preferences.getInt("height", 340)));
        try { viewport = MapWindowMetrics.read(this, card, preferences.getBoolean(MapWindowMetrics.AVOID_BARS_KEY, false)); }
        catch (RuntimeException error) { Log.w(TAG, "Screen bounds unavailable; retain previous bounds", error); }
        placement.viewport(viewport);
        placement.geometry(preferences.getInt("x", 24), preferences.getInt("y", 296), width, height);
        params.width = placement.width(); params.height = placement.height();
        ((SoftMapCard)card).setFeatherPercent(preferences.getInt("feather_percent", 100));
        applyPlacement(true);
    }
    private void queueBoundsUpdate() {
        if (stopped) return;
        main.removeCallbacks(boundsUpdate); main.post(boundsUpdate);
    }
    private void refreshScreenBounds() {
        if (stopped || card == null || placement == null) return;
        try {
            MapViewport next = MapWindowMetrics.read(this, card, preferences.getBoolean(MapWindowMetrics.AVOID_BARS_KEY, false));
            if (!next.same(viewport)) applyAppearance();
        } catch (RuntimeException error) { Log.w(TAG, "Screen bounds update deferred", error); }
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); queueBoundsUpdate();
    }
    private void fitMap(TextureView view, int width, int height) {
        if (width <= 0 || height <= 0) return;
        float sourceAspect = (float)SOURCE_WIDTH / SOURCE_HEIGHT;
        float viewAspect = (float)width / height;
        Matrix transform = new Matrix();
        if (sourceAspect > viewAspect)
            transform.setScale(sourceAspect / viewAspect, 1f, width * 0.85f, height * 0.5f);
        else transform.setScale(1f, viewAspect / sourceAspect, width * 0.5f, height * 0.5f);
        view.setTransform(transform);
    }
    private void pollHome() {
        if (stopped) return;
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = getSystemService(UsageStatsManager.class).queryEvents(usageSince, now);
            UsageEvents.Event event = new UsageEvents.Event(); String newestPackage = null, newestClass = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && event.getTimeStamp() >= newestUsage) {
                    newestUsage = event.getTimeStamp(); newestPackage = event.getPackageName(); newestClass = event.getClassName();
                }
            }
            usageSince = Math.max(usageSince, now - 2000);
            boolean attached = false;
            for (UsbDevice device : getSystemService(UsbManager.class).getDeviceList().values())
                if (device.getVendorId() == 0x05ac && "iPhone".equalsIgnoreCase(device.getProductName())) attached = true;
            final Boolean nextHome = newestPackage == null ? null
                : HomeMapVisibilityPolicy.isDesktop(newestPackage, newestClass, homePackage, homeActivity);
            final boolean nextIphone = attached;
            main.post(() -> {
                if (stopped) return;
                if (nextHome != null) {
                    boolean entering = nextHome && !home; home = nextHome;
                    if (entering) { HomeMapScrollService.requestSnapshot(); queueBoundsUpdate(); }
                }
                iphone = nextIphone;
                if (!iphone) stream = false;
                updateVisibility();
            });
        } catch (RuntimeException error) { main.post(() -> { home = false; updateVisibility(); }); }
    }
    private void startStreamReader() throws Exception {
        logs = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch", "-T", "1", "-s", "DiPlay-Cluster:I").start();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(logs.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!stopped && (line = input.readLine()) != null) {
                    Matcher match = STREAM.matcher(line);
                    if (!match.matches() || Integer.parseInt(match.group(3)) != sourceUid) continue;
                    long wall = Long.parseLong(match.group(1)) * 1000 + Integer.parseInt((match.group(2) + "000").substring(0, 3));
                    if (wall < startedAt) continue;
                    final boolean next = Boolean.parseBoolean(match.group(4));
                    main.post(() -> {
                        if (!stopped) {
                            stream = next;
                            preferences.edit().putBoolean("stream_active", next)
                                .putLong("stream_at", System.currentTimeMillis())
                                .putInt("stream_boot", Settings.Global.getInt(getContentResolver(), "boot_count", -1)).apply();
                            updateVisibility();
                        }
                    });
                }
            } catch (Exception error) { if (!stopped) Log.w(TAG, "Map state reader stopped", error); }
            finally { main.post(() -> { if (!stopped) { stream = false; updateVisibility(); } }); }
        }, "map-state-reader"); reader.setDaemon(true); reader.start();
    }
    private void pollRuntime() {
        if (stopped) return;
        try {
            MapAdbShell shell = runtime;
            if (shell == null) {
                shell = new MapAdbShell(getApplicationInfo().sourceDir);
                if (stopped) { shell.close(); return; }
                runtime = shell;
            }
            String result = shell.exchange("P");
            boolean active = result.equals("RESULT ACTIVE") || result.equals("RESULT ACTIVE_MAP");
            if (active) {
                final boolean mapActive = result.equals("RESULT ACTIVE_MAP");
                main.post(() -> {
                    if (!stopped) {
                        // Restore a stream whose activation log preceded this helper process.
                        stream = mapActive; updateVisibility();
                    }
                });
            }
            String message = active ? "性能优化已启用" :
                result.equals("RESULT WAITING") ? "等待 DiPlay 启动" :
                result.equals("RESULT WAITING_SESSION") ? "等待 CarPlay 连接" :
                result.equals("RESULT RESTART_NEEDED") ? "需要重新打开 DiPlay" : "性能优化尚未就绪";
            if (!message.equals(preferences.getString("runtime_status", "")))
                preferences.edit().putString("runtime_status", message).apply();
        } catch (Exception error) {
            MapAdbShell shell = runtime; runtime = null; if (shell != null) shell.close();
            preferences.edit().putString("runtime_status", "性能优化正在重试").apply();
        }
    }
    private void updateVisibility() {
        if (stopped || card == null) return;
        displayLease.update(surface != null, home && iphone, displayPort);
        applyPlacement(false);
        status(!iphone || !stream ? "已开启，等待 CarPlay 地图" : home ? "地图显示中" : "已连接，返回原车桌面显示地图");
    }
    private void applyPlacement(boolean force) {
        if (stopped || card == null || params == null) return;
        boolean requested = preferences.getBoolean(HomeMapScrollService.FOLLOW_KEY, false);
        LauncherScrollState.Snapshot scroll = HomeMapScrollService.snapshot();
        if (requested && home && followAnchor.capture(scroll))
            preferences.edit().putInt(MapFollowAnchor.KEY, followAnchor.origin()).apply();
        placement.scrollOrigin(followAnchor.origin());
        placement.follow(requested && HomeMapScrollService.isObserving(), scroll);
        int x = placement.x(), y = placement.y();
        boolean next = home && iphone && stream && display != null && placement.inViewport();
        int flags = next && placement.touchable() ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        float alpha = next ? 1f : 0f;
        boolean changed = params.x != x || params.y != y || params.flags != flags || params.alpha != alpha;
        if (next != shown) {
            shown = next; Log.i(TAG, "Map visible=" + shown + " home=" + home + " stream=" + stream);
        }
        params.x = x; params.y = y; params.flags = flags; params.alpha = alpha;
        if (changed || force) windows.updateViewLayout(card, params);
    }
    private void status(String value) {
        if (!value.equals(preferences.getString("status", ""))) preferences.edit().putString("status", value).apply();
    }
    private void releaseDisplay() {
        displayLease.close(displayPort);
    }
    public void onDestroy() {
        if (active == this) active = null;
        stopped = true; main.removeCallbacksAndMessages(null); poller.shutdownNow();
        HomeMapScrollService.unlisten(scrollListener);
        getSystemService(DisplayManager.class).unregisterDisplayListener(screenListener);
        if (followFrames != null) followFrames.close();
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceListener);
        runtimePoller.shutdownNow();
        MapAdbShell shell = runtime; runtime = null; if (shell != null) shell.close();
        if (logs != null) logs.destroy(); releaseDisplay();
        if (card != null) { windows.removeViewImmediate(card); card = null; }
        if (surface != null) { surface.release(); surface = null; }
        if (idleReader != null) { idleReader.close(); idleReader = null; }
        if (idleThread != null) { idleThread.quitSafely(); idleThread = null; }
        preferences.edit().remove("display_id").apply(); status("浮窗已关闭"); stopForeground(true); super.onDestroy();
    }
}
