package com.local.diplay.clusterbridge;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Single-worker, byte-bounded report. Android delivery success is never treated as a display ACK. */
public final class DiagnosticReport implements Closeable {
    public static final long DURATION_MS = 180000;
    public static final int EVENT_LIMIT = 1024 * 1024;
    private final File folder;
    private final long startWall;
    private final BufferedWriter events;
    private final DiagnosticTrace trace;
    private final NavigationLogState navigation = new NavigationLogState();
    private final Map<String, String> last = new LinkedHashMap<>();
    private int bytes, omitted, widgets, validNav, music, songChanges, navSent, musicSent, errors;
    private int oemNav, oemMusic;
    private String previousSong;
    private boolean connected, everConnected, navEnabled, musicEnabled, widgetReady, receiverReady;
    private String receiverIssue = "尚未检查", navDecision = "尚未观察", musicDecision = "尚未观察", logStatus = "尚未检查";
    private boolean closed;

    public DiagnosticReport(File folder, long startWall) throws IOException {
        this.folder = folder; this.startWall = startWall; trace = new DiagnosticTrace(startWall);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create report folder");
        events = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(folder, "events.tsv")), StandardCharsets.UTF_8));
        try { event(startWall, "session", "开始；仅采集本次会话；不包含联系人、通话原始帧或设备标识"); events.flush(); }
        catch (IOException e) { try { events.close(); } catch (IOException ignored) { } throw e; }
    }
    public void event(long wall, String kind, String detail) throws IOException {
        if (closed) return;
        String row = wall + "\t" + clean(kind, 64) + "\t" + clean(detail, 1800) + "\n";
        int length = row.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + length > EVENT_LIMIT) { omitted++; return; }
        events.write(row); bytes += length;
    }
    public void changed(long wall, String kind, String detail) throws IOException {
        detail = clean(detail, 1800);
        if (!detail.equals(last.put(kind, detail))) event(wall, kind, detail);
    }
    public void source(String line, int uid, long nowWall) throws IOException {
        String selected = trace.accept(line, uid, nowWall);
        if (selected == null) return;
        navigation.accept(line, uid, nowWall);
        event(nowWall, "source_nav", selected);
    }
    public void oem(long wall, String selected) throws IOException {
        if (selected.startsWith("oem_nav ")) oemNav++;
        if (selected.startsWith("oem_music ")) oemMusic++;
        event(wall, "receiver_observation", selected);
    }
    public void snapshot(long wall, boolean isWidgetUpdate, boolean ready, boolean isConnected,
        boolean navOn, boolean musicOn, String receiverError, String readerStatus,
        String rawDistance, String rawRoad, String turn, Integer meters, String title, boolean playing,
        String outputRoad, String roadSource, long widgetAge) throws IOException {
        widgetReady = ready; connected = isConnected; everConnected |= connected;
        if (title != null) title = clean(title, 512);
        navEnabled = navOn; musicEnabled = musicOn; receiverReady = receiverError == null;
        receiverIssue = receiverReady ? "签名检查通过" : clean(receiverError, 300); logStatus = clean(readerStatus, 300);
        if (isWidgetUpdate) widgets++;
        boolean current = ready && connected && widgetAge >= 0 && widgetAge <= 5000;
        if (current && meters != null && Protocol.knownTurn(turn)) validNav++;
        // Music widgets need not refresh while the same song keeps playing.
        if (ready && connected && title != null && !title.isEmpty()) {
            music++;
            if (previousSong != null && !previousSong.equals(title)) songChanges++;
            previousSong = title;
        }
        changed(wall, "bridge", "widget=" + ready + " connected=" + connected + " nav_enabled=" + navOn
            + " music_enabled=" + musicOn + " receiver=" + receiverIssue + " reader=" + logStatus);
        changed(wall, "widget", "distance=" + rawDistance + " road=" + rawRoad + " turn=" + turn + " meters=" + meters
            + " title=" + title + " playing=" + playing + " outputRoad=" + outputRoad + " roadSource=" + roadSource);
        if (current && meters != null && Protocol.knownTurn(turn) && rawRoad != null && !rawRoad.isEmpty())
            changed(wall, "road_characters", Protocol.roadDiagnostic(rawRoad));
    }
    public void decision(long wall, boolean nav, String detail) throws IOException {
        detail = clean(detail, 1200);
        if (nav) navDecision = detail; else musicDecision = detail;
        changed(wall, nav ? "navigation_decision" : "music_decision", detail);
    }
    public void dispatch(long wall, String action, String payload, boolean success) throws IOException {
        if (success && "com.baidu.car.navi.INFO".equals(action)) navSent++;
        if (success && "com.baidu.car.radio.notify.PLAY_INFO".equals(action) && !payload.contains(" clear=true ")) musicSent++;
        if (!success) errors++;
        event(wall, success ? "broadcast_submitted" : "broadcast_failed", "action=" + action + " " + payload
            + (success ? "；系统调用已返回，不代表仪表已接收/显示" : ""));
    }
    public String progress(long elapsed) {
        String next;
        if (!widgetReady) next = "请先授权小组件；若仍未就绪，结束保存后把日志交给开发者。";
        else if (!receiverReady) next = "仪表或车机助手校验未通过，当前会阻止转发。请结束保存，把日志交给开发者检查版本与接口。";
        else if (!connected) next = "请连接 iPhone 的 CarPlay，确认车机能显示 CarPlay 画面。";
        else if (!navEnabled || !musicEnabled) next = "导航或音乐转发未开启；如需检查对应功能，请先开启，再继续导航与播放。";
        else if (validNav == 0) next = "请在 iPhone 高德中选择目的地并点击“开始导航”，只打开地图不能采到导航指令。";
        else if (music == 0) next = "请在 CarPlay 播放一首音乐。";
        else if (songChanges == 0) next = "已收到导航和音乐，请切换一首歌曲，记录音乐变化后的分发结果。";
        else next = "已收到导航、音乐和切歌变化。请核对仪表显示；可等待自动保存，或点“结束并保存”。";
        return "正在诊断 · 剩余 " + Math.max(0, (DURATION_MS - elapsed + 999) / 1000) + " 秒\n"
            + "导航输入：" + (validNav > 0 ? "已收到" : "未观察到") + "；源导航帧：" + navigation.framesSeen()
            + "，解析：" + navigation.framesParsed() + "\n音乐输入：" + (music > 0 ? "已收到" : "未观察到")
            + "；切歌变化：" + songChanges + " 次\n导航分发：" + navSent + " 次；音乐分发：" + musicSent + " 次\n下一步：" + next
            + (logStatus.contains("未授权") ? "\n源日志未授权：仍采集小组件和分发记录；结束保存后请开发者核对日志授权。" : "");
    }
    public String overview() {
        return "导航输入：" + (validNav > 0 ? "已收到" : "未观察到，请开始真实导航后重采")
            + "；音乐输入：" + (music > 0 ? "已收到" : "未观察到，请播放后重采")
            + "\n导航分发：" + navSent + " 次；音乐分发：" + musicSent + " 次；切歌变化：" + songChanges + " 次"
            + (songChanges == 0 ? "（本次未验证切歌变化）" : "")
            + "\n仪表实际显示仍需人工核对，详细缺项与异常见 ZIP 内 summary.txt。";
    }
    public String summary(long endWall, String reason, int dropped) {
        return "CarPlay 仪表桥接 · 车辆环境、音乐与导航诊断\n"
            + "开始时间（Unix毫秒）：" + startWall + "\n结束时间（Unix毫秒）：" + endWall + "\n结束原因：" + clean(reason, 200)
            + "\n\n环境和接口以 environment.txt 为准；逐条输入、处理与分发见 events.tsv。\n"
            + "仪表签名门禁：" + receiverIssue + "\n小组件：" + (widgetReady ? "接口已创建" : "未就绪")
            + "；本次更新 " + widgets + " 次\nCarPlay：" + (everConnected ? "本次观察到连接" : "本次未观察到连接")
            + "\n导航转发设置（结束时）：" + navEnabled + "；音乐转发设置：" + musicEnabled
            + "\n导航日志读取：" + logStatus + "\n新导航帧：" + navigation.framesSeen() + "；完整解析：" + navigation.framesParsed()
            + "\n有效导航快照：" + validNav + "；导航分发提交：" + navSent + " 次"
            + "\n音乐快照：" + music + "；切歌变化：" + songChanges + "；音乐分发提交：" + musicSent + " 次"
            + "\n最近导航决策：" + navDecision + "\n最近音乐决策：" + musicDecision
            + "\n分发异常：" + errors + "；队列丢弃：" + dropped + "；文件达到上限省略：" + omitted
            + "\n原车接收端导航记录：" + oemNav + "；音乐记录：" + oemMusic
            + "\n\n分析提示：\n" + assessment()
            + "\n仪表接收/显示回执：当前广播接口没有确认回执，未验证。发送调用返回仅说明已向系统提交。"
            + "\n原车接收端日志是处理过程观察，不是显示回执；可能包含其他音源或导航，请按时间和文字与分发记录对照。未观察到日志不等于未接收。"
            + "\n缺少数据表示本次未观察到，不能据此认定车型不支持。日志权限缺失时，小组件导航仍可能正常。"
            + "\n请同时说明仪表实际现象、故障时间及手机导航软件版本，便于对照。"
            + "\n\n隐私：包含道路名称、歌曲文字和固件信息；不录音、不上传，不保存联系人、通话帧、蓝牙地址或设备序列号。"
            + "\n文件上限：events.tsv 1 MiB，environment.txt 128 KiB。\n";
    }
    private String assessment() {
        StringBuilder result = new StringBuilder();
        if (!receiverReady) result.append("- 仪表/助手校验未通过，桥接会阻止转发。\n");
        if (!widgetReady) result.append("- 小组件未就绪，检查源应用、资源和小组件授权。\n");
        if (!everConnected) result.append("- 未观察到 CarPlay 连接，请连接后重新采集。\n");
        if (!navEnabled) result.append("- 导航转发关闭，没有导航分发属于设置结果。\n");
        if (navigation.framesSeen() > 0 && navigation.framesParsed() == 0) result.append("- 收到源导航帧但未形成完整解析，检查帧参数、日志截断和源版本。\n");
        if (validNav == 0) result.append("- 未观察到有效导航指令，不能评估导航分发；检查是否已开始真实导航及箭头/距离识别。\n");
        else if (navSent == 0) result.append("- 已有有效导航输入但无导航提交，请对照转发设置、门禁和决策记录。\n");
        else result.append("- 已记录导航输入及系统分发提交；仪表是否显示需要人工核对。\n");
        if (music == 0) result.append("- 未观察到音乐文字，请播放音乐后重新采集。\n");
        else if (musicSent == 0) result.append("- 已读取音乐但本次未提交；同一歌曲/状态会去重，请对照决策并切歌。\n");
        if (songChanges == 0) result.append("- 本次未观察到切歌变化，音乐变化链路尚未验证。\n");
        if (errors > 0) result.append("- 系统分发发生异常，检查 broadcast_failed 记录。\n");
        return result.toString();
    }
    public void flush() throws IOException { events.flush(); }
    public void close() throws IOException { if (!closed) { closed = true; events.close(); } }
    public File finish(long endWall, String reason, int dropped) throws IOException {
        close();
        Files.write(new File(folder, "summary.txt").toPath(), summary(endWall, reason, dropped).getBytes(StandardCharsets.UTF_8));
        return zip(folder);
    }
    /** Only these three known files can be exported, never preferences, call data or unrelated logs. */
    public static File zip(File folder) throws IOException {
        if (!new File(folder, "summary.txt").isFile() || !new File(folder, "events.tsv").isFile())
            throw new IOException("MissingReportFiles");
        File partial = new File(folder, "report.zip.partial"), result = new File(folder, "report.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
            for (String name : new String[]{"summary.txt", "environment.txt", "events.tsv"}) {
                File source = new File(folder, name);
                if (!source.isFile()) continue;
                if (source.length() > (name.equals("events.tsv") ? EVENT_LIMIT : 128 * 1024)) throw new IOException("Report file exceeds bound");
                zip.putNextEntry(new ZipEntry(name));
                try (FileInputStream in = new FileInputStream(source)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
        if (!partial.renameTo(result)) throw new IOException("Cannot finalize archive");
        return result;
    }
    public static String clean(String value, int limit) {
        if (value == null) return "null";
        String result = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replace('\0', ' ');
        int end = Math.min(result.length(), limit);
        if (end > 0 && end < result.length() && Character.isHighSurrogate(result.charAt(end - 1))) end--;
        return result.substring(0, end);
    }
}
