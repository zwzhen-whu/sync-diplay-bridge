package com.local.diplay.clusterbridge;

import android.content.ContentValues;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One explicit session; all I/O stays on a single bounded worker. No navigation/audio commands. */
final class VehicleDiagnostics {
    private static VehicleDiagnostics instance;
    static synchronized VehicleDiagnostics get(Context context, SharedPreferences prefs) {
        if (instance == null) instance = new VehicleDiagnostics(context, prefs);
        return instance;
    }
    static final String START = "com.local.diplay.clusterbridge.DIAG_START";
    static final String FINISH = "com.local.diplay.clusterbridge.DIAG_FINISH";
    static final String RETRY = "com.local.diplay.clusterbridge.DIAG_RETRY";
    static final String PHASE = "diagnostic_phase", STATUS = "diagnostic_status";
    private final Context context;
    private final SharedPreferences prefs;
    private final VehicleEnvironment environment;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "vehicle-diagnostics"); thread.setDaemon(true); return thread;
    });
    private interface Entry { void write(DiagnosticReport report) throws IOException; }
    private final ArrayBlockingQueue<Entry> queue = new ArrayBlockingQueue<>(512);
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile boolean running, finishing;
    private ScheduledFuture<?> ticker;
    private volatile DiagnosticTrace gate;
    private long startElapsed, lastAudio;
    private long lastStatus;
    private File folder;
    private DiagnosticReport report;
    private volatile DiagnosticOemReader oem;

    private VehicleDiagnostics(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext(); this.prefs = prefs; environment = new VehicleEnvironment(this.context);
        worker.setKeepAliveTime(10, TimeUnit.SECONDS); worker.allowCoreThreadTimeOut(true); worker.setRemoveOnCancelPolicy(true);
        String previousPhase = prefs.getString(PHASE, "idle"), previousFolder = prefs.getString("diagnostic_private_folder", "");
        worker.execute(() -> recover(previousPhase, previousFolder));
    }
    synchronized void start() {
        if (running || finishing) return;
        running = true; queue.clear(); dropped.set(0);
        prefs.edit().remove("diagnostic_last_overview").remove("diagnostic_private_folder").apply();
        long wall = System.currentTimeMillis(); startElapsed = SystemClock.elapsedRealtime();
        gate = new DiagnosticTrace(wall);
        setStatus("recording", "正在读取车辆环境…\n请连接 CarPlay，在 iPhone 开始导航，再播放音乐并切歌一次。3 分钟后自动保存，也可提前结束。");
        worker.execute(() -> {
            try {
                String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(wall)) + "-" + UUID.randomUUID().toString().substring(0, 8);
                folder = new File(context.getFilesDir(), "vehicle-diagnostics/" + id);
                report = new DiagnosticReport(folder, wall);
                // Journal before slow probes: an interrupted session can be recovered at next startup.
                prefs.edit().putString("diagnostic_private_folder", folder.getAbsolutePath()).commit();
                prunePrivate(folder, 3);
                synchronized (VehicleDiagnostics.this) {
                    if (active()) oem = new DiagnosticOemReader(context, wall, selected -> {
                        long at = System.currentTimeMillis(); offer(report -> report.oem(at, selected));
                    });
                }
                writeEnvironment(environment.collect()); lastAudio = SystemClock.elapsedRealtime();
            } catch (Exception e) { fail(e); }
        });
        ticker = worker.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }
    boolean active() { return running && !finishing; }
    private void offer(Entry entry) { if (active() && !queue.offer(entry)) dropped.incrementAndGet(); }
    void source(String line, int uid) {
        DiagnosticTrace selected = gate; long wall = System.currentTimeMillis();
        if (!active() || selected == null || selected.accept(line, uid, wall) == null) return;
        offer(report -> report.source(line, uid, wall));
    }
    void snapshot(boolean isWidgetUpdate, boolean ready, boolean connected, boolean nav, boolean music,
        String receiverError, String logStatus, String distance, String road, String turn, Integer meters,
        Protocol.Song song, String outputRoad, String roadSource, long widgetAge) {
        if (!active()) return;
        long wall = System.currentTimeMillis(); String title = song == null ? null : song.text; boolean playing = song != null && song.playing;
        offer(report -> report.snapshot(wall, isWidgetUpdate, ready, connected, nav, music, receiverError, logStatus,
            distance, road, turn, meters, title, playing, outputRoad, roadSource, widgetAge));
    }
    void decision(boolean nav, String detail) {
        if (!active()) return;
        long wall = System.currentTimeMillis(); offer(report -> report.decision(wall, nav, detail));
    }
    void dispatch(String action, String payload, boolean success) {
        if (!active()) return;
        long wall = System.currentTimeMillis(); offer(report -> report.dispatch(wall, action, payload, success));
    }
    synchronized void finish(String reason) {
        if (!running || finishing) return;
        finishing = true; gate = null;
        stopOem();
        if (ticker != null) ticker.cancel(false);
        setStatus("saving", "正在整理诊断并保存到车机“下载”文件夹，请稍候…");
        worker.execute(() -> complete(reason));
    }
    synchronized void retry() {
        if (running || finishing) return;
        finishing = true; setStatus("saving", "正在重新保存上一次诊断…");
        worker.execute(() -> {
            try {
                File previous = savedFolder();
                if (previous == null) throw new IOException("NoSavedSession");
                File summary = new File(previous, "summary.txt");
                if (!summary.isFile()) Files.write(summary.toPath(), "上次采集或保存异常，以下仅为已保留的部分日志；请重新诊断以补全。\n".getBytes(StandardCharsets.UTF_8));
                File archive = DiagnosticReport.zip(previous);
                export(archive);
            } catch (Exception e) { fail(e); }
            finally { finishing = false; }
        });
    }
    private void tick() {
        if (!running || finishing || report == null) return;
        try {
            drain(); report.flush();
            long elapsed = SystemClock.elapsedRealtime() - startElapsed;
            if (elapsed >= DiagnosticReport.DURATION_MS) { finish("到达 3 分钟自动保存"); return; }
            if (SystemClock.elapsedRealtime() - lastAudio >= 15000) {
                writeEnvironment(environment.audio()); lastAudio = SystemClock.elapsedRealtime();
            }
            if (SystemClock.elapsedRealtime() - lastStatus >= 1000 && !finishing) {
                setStatus("recording", report.progress(elapsed)); lastStatus = SystemClock.elapsedRealtime();
            }
        } catch (Exception e) { fail(e); }
    }
    private void drain() throws IOException {
        Entry entry; int count = 0;
        while (count++ < 512 && (entry = queue.poll()) != null) entry.write(report);
    }
    private void complete(String reason) {
        try {
            if (report == null) throw new IOException("SessionUnavailable");
            drain(); writeEnvironment(environment.audio());
            prefs.edit().putString("diagnostic_last_overview", report.overview()).apply();
            File archive = report.finish(System.currentTimeMillis(), reason, dropped.get()); report = null;
            export(archive);
        } catch (Exception e) { fail(e); }
        finally { stopOem(); running = false; finishing = false; gate = null; queue.clear(); }
    }
    private void writeEnvironment(String text) throws IOException {
        File file = new File(folder, "environment.txt");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (file.length() + data.length > 128 * 1024) return;
        try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(data); }
    }
    private void export(File archive) throws IOException {
        String name = "CarPlay诊断-" + archive.getParentFile().getName() + ".zip";
        String location;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name); values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CarPlay诊断");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("DownloadInsertFailed");
            try {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("DownloadOpenFailed"); copy(archive, output);
                }
                ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, ready, null, null) < 1) throw new IOException("DownloadFinalizeFailed");
            } catch (Exception e) {
                context.getContentResolver().delete(uri, null, null);
                throw new IOException("DownloadExportFailed", e);
            }
            location = "下载/CarPlay诊断/" + name;
        } else {
            if (!environment.granted("WRITE_EXTERNAL_STORAGE")) throw new IOException("StoragePermissionMissing");
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) throw new IOException("StorageNotMounted");
            File downloads = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CarPlay诊断");
            if (!downloads.isDirectory() && !downloads.mkdirs()) throw new IOException("DownloadCreateFailed");
            File destination = new File(downloads, name);
            if (destination.exists()) destination = new File(downloads, name.replace(".zip", "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip"));
            File partial = new File(downloads, destination.getName() + ".partial");
            try {
                try (FileOutputStream output = new FileOutputStream(partial)) { copy(archive, output); output.getFD().sync(); }
                if (!partial.renameTo(destination)) throw new IOException("DownloadFinalizeFailed");
            } finally { if (partial.exists()) partial.delete(); }
            try { MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, new String[]{"application/zip"}, null); }
            catch (RuntimeException ignored) { /* File listing refresh is optional; the synced public file already exists. */ }
            location = destination.getAbsolutePath();
        }
        prefs.edit().putString("diagnostic_export_location", location).apply();
        setStatus("complete", "诊断已保存\n" + location + "\n" + prefs.getString("diagnostic_last_overview", "本次为已保留日志，采集情况请查看 ZIP 内 summary.txt。")
            + "\n请在车机文件管理器打开“下载 → CarPlay诊断”，将 ZIP 文件交给开发者，并说明仪表异常现象和时间。\n包含路名和歌曲文字，分享前可先查看。");
        prunePrivate(archive.getParentFile(), 1);
    }
    private static void copy(File source, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }
    private File savedFolder() throws IOException { return savedFolder(prefs.getString("diagnostic_private_folder", "")); }
    private File savedFolder(String name) throws IOException {
        if (name.isEmpty()) return null;
        File file = new File(name).getCanonicalFile(), parent = new File(context.getFilesDir(), "vehicle-diagnostics").getCanonicalFile();
        return parent.equals(file.getParentFile()) && file.isDirectory() ? file : null;
    }
    private void recover(String phase, String previousFolder) {
        // Recover only the previous process's journal, before any new session is initialized.
        if (!"recording".equals(phase) && !"saving".equals(phase)) return;
        try {
            File saved = savedFolder(previousFolder);
            if (saved == null) throw new IOException("NoRecoveryFiles");
            File summary = new File(saved, "summary.txt");
            if (!summary.exists()) Files.write(summary.toPath(), ("上次诊断因应用/系统中断，以下为已采集的部分信息；不能据此认定接口正常或异常。\n"
                + "不包含中断后的数据。请重新诊断以获得完整流程。\n").getBytes(StandardCharsets.UTF_8));
            DiagnosticReport.zip(saved);
            if (!running) prefs.edit().putString("diagnostic_last_overview", "上次采集被中断，报告只含中断前已记录的部分信息。请重新诊断以补全。").apply();
            if (!running) setStatus("save_failed", "上次诊断被中断，已保留部分日志。请点“重新保存日志”导出，或重新开始诊断。");
        } catch (Exception e) {
            if (!running) setStatus("idle", "上次诊断未完成，未找到可恢复日志。请重新开始诊断。");
        }
    }
    private void prunePrivate(File keep, int retained) {
        File[] files = keep.getParentFile().listFiles(); if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        // Latest private copy supports export retry; public reports remain under user control.
        for (File old : files) if (old.isDirectory() && !old.equals(keep)) {
            if (--retained > 0) continue;
            File[] children = old.listFiles(); if (children != null) for (File child : children) if (child.isFile()) child.delete();
            old.delete();
        }
    }
    private void fail(Exception e) {
        gate = null; running = false; queue.clear();
        stopOem();
        if (ticker != null) ticker.cancel(false);
        if (report != null) {
            try { report.finish(System.currentTimeMillis(), "采集或保存异常：" + e.getClass().getSimpleName(), dropped.get()); }
            catch (IOException ignored) { }
            report = null;
        }
        boolean retained = false;
        try { File previous = savedFolder(); retained = previous != null && new File(previous, "events.tsv").isFile(); }
        catch (IOException ignored) { }
        String detail = "StoragePermissionMissing".equals(e.getMessage()) ? "文件存储未授权" : "StorageNotMounted".equals(e.getMessage()) ? "存储尚未就绪"
            : "DownloadCreateFailed".equals(e.getMessage()) ? "无法创建下载目录" : e.getClass().getSimpleName();
        setStatus(retained ? "save_failed" : "idle", "诊断未保存到下载文件夹（" + detail + "）。\n"
            + (retained ? "已采集内容保留在应用中。请检查存储空间及文件权限，再点“重新保存日志”。未采到的数据需重新开始诊断。"
                : "未找到可导出的日志，请检查存储空间后重新开始诊断。"));
    }
    private void setStatus(String phase, String message) {
        SharedPreferences.Editor edit = prefs.edit().putString(PHASE, phase).putString(STATUS, message);
        edit.apply();
        if ("complete".equals(phase) || "save_failed".equals(phase)) {
            try {
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(new NotificationChannel("vehicle-diagnostics", "车辆诊断结果", NotificationManager.IMPORTANCE_LOW));
                    PendingIntent open = PendingIntent.getActivity(context, 31, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    manager.notify(31, new Notification.Builder(context, "vehicle-diagnostics").setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle("complete".equals(phase) ? "CarPlay 诊断日志已保存" : "CarPlay 诊断需要处理")
                        .setContentText("打开桥接查看保存位置和操作提示").setContentIntent(open).setAutoCancel(true).build());
                }
            } catch (RuntimeException ignored) { /* Notification failure must not change a successful export. */ }
        }
    }
    void close() {
        finish("桥接服务停止，提前保存");
    }
    private void stopOem() { DiagnosticOemReader reader = oem; oem = null; if (reader != null) reader.close(); }
}
