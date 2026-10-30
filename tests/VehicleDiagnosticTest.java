package com.local.diplay.clusterbridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Captured-format replay, missing-input diagnosis, privacy boundaries and bounded export. */
public final class VehicleDiagnosticTest {
    private static final long BASE = 1792728000000L;
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static String line(long offset, int uid, int thread, String message) {
        long wall = BASE + offset;
        return String.format(java.util.Locale.ROOT, "%d.%03d %d 6720 %d I xcertplay-usb: %s", wall / 1000, wall % 1000, uid, thread, message);
    }
    private static void frame(DiagnosticReport report, long offset, String kind, String... parameters) throws Exception {
        report.source(line(offset, 10087, 7784, "IAP2 RX [wired] 0x" + kind + " Message"), 10087, BASE + offset);
        for (String parameter : parameters) report.source(line(offset, 10087, 7784, parameter), 10087, BASE + offset);
        report.source(line(offset, 10087, 7784, "raw-body=00 06 SECRET_RAW"), 10087, BASE + offset);
    }
    private static void snapshot(DiagnosticReport report, boolean widget, boolean connected, boolean nav, boolean music,
        String error, Integer meters, String song, long age) throws Exception {
        report.snapshot(BASE + 1000, true, widget, connected, nav, music, error, "日志已授权", "50米", "去往 八一路 洪山侧路",
            "turn_left", meters, song, true, "八一路 洪山侧路", "DiPlay 小组件", age);
    }
    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vehicle-diagnostic-");
        try {
            DiagnosticTrace filter = new DiagnosticTrace(BASE);
            check(filter.accept(line(-1, 10087, 1, "IAP2 RX [wired] 0x5201 Message"), 10087, BASE) == null, "Exclude historical frames");
            check(filter.accept(line(1001, 10087, 1, "IAP2 RX [wired] 0x5201 Message"), 10087, BASE) == null, "Exclude future frames");
            check(filter.accept(line(0, 10088, 1, "IAP2 RX [wired] 0x5201 Message"), 10087, BASE) == null, "Exclude wrong UID");
            check(filter.accept(line(0, 10087, 1, "IAP2 RX [wired] 0x5202 Message"), 10087, BASE) != null, "Accept source frame");
            check(filter.accept(line(0, 10087, 2, "[2] Description: \"UNTRUSTED_THREAD\""), 10087, BASE) == null, "Separate threads");
            check(filter.accept(line(0, 10087, 1, "[4] Road: \"八一路\""), 10087, BASE).contains("八一路"), "Keep navigation road");
            check(filter.accept(line(0, 10087, 1, "IAP2 RX [wired] 0x4155 CallStateUpdate"), 10087, BASE) == null, "Exclude call header");
            check(filter.accept(line(0, 10087, 1, "[4] Name: \"CONTACT_SECRET\""), 10087, BASE) == null, "Call frame resets navigation context");
            check(filter.accept(line(0, 10087, 1, "raw-body=SECRET"), 10087, BASE) == null, "Exclude call bytes");
            check(filter.accept(line(0, 10087, 1, "IAP2 RX [wired] 0x5201 Message").replace("xcertplay-usb", "DiPlay-MediaKeys"), 10087, BASE) == null, "Exclude other tags");
            String receiverRoad = line(0, 1000, 1, "hqj Send to YF sendStreetName 八一路").replace("xcertplay-usb", "NavPresenterImpl");
            check(DiagnosticOemTrace.select(receiverRoad, 1000, BASE, BASE).startsWith("oem_nav "), "Accept actual OEM street processing log format");
            check(DiagnosticOemTrace.select(receiverRoad, 1001, BASE, BASE) == null, "Check receiver UID");
            check(DiagnosticOemTrace.select(receiverRoad, 1000, BASE + 1, BASE) == null, "No historical OEM evidence");
            check(DiagnosticOemTrace.select(line(0, 1000, 1, "hqj phone 主叫状态 s CONTACT_SECRET").replace("xcertplay-usb", "IService"), 1000, BASE, BASE) == null, "Exclude OEM phone messages");
            check(DiagnosticOemTrace.select(line(0, 1000, 1, "get home address : HOME_SECRET").replace("xcertplay-usb", "MediaReceiver"), 1000, BASE, BASE) == null, "Exclude home address log in same tag");
            check(DiagnosticOemTrace.select(line(0, 1000, 1, "hqj play_info RECEIVE_PLAY_INFO_ACTION title = 歌曲甲,artist = ,album_name = ,sourceName = CarPlay,isPlaying = true").replace("xcertplay-usb", "MediaReceiver"), 1000, BASE, BASE).startsWith("oem_music "), "Accept OEM music reception");

            File normalFolder = root.resolve("normal").toFile();
            DiagnosticReport normal = new DiagnosticReport(normalFolder, BASE);
            check(normal.progress(0).contains("授权小组件"), "Guide missing widget");
            snapshot(normal, true, false, true, true, null, null, null, 0);
            check(normal.progress(1000).contains("连接 iPhone"), "Guide connection");
            snapshot(normal, true, true, false, true, null, null, null, 0);
            check(normal.progress(1000).contains("转发未开启"), "Guide disabled setting");
            snapshot(normal, true, true, true, true, null, null, null, 0);
            check(normal.progress(1000).contains("开始导航"), "Guide actual route");
            snapshot(normal, true, true, true, true, null, 50, null, 0);
            check(normal.progress(1000).contains("播放一首音乐"), "Guide music");
            snapshot(normal, true, true, true, true, null, 50, "歌曲甲", 0);
            check(normal.progress(1000).contains("切换一首歌曲"), "Guide song change");
            snapshot(normal, true, true, true, true, null, 50, "歌曲乙", 0);
            check(normal.progress(1000).contains("可等待自动保存"), "Guide completion");
            check(normal.progress(999999).contains("剩余 0 秒"), "Timer never negative");
            frame(normal, -1, "5202", "[0] ID: u16=42", "[1] Index: u16=4", "[2] Description: \"HISTORICAL_SECRET\"", "[3] Type: u8=1");
            frame(normal, 2000, "4155", "[0] ID: u16=42", "[4] Name: \"CONTACT_SECRET\"", "[5] Number: \"13800138000\"");
            frame(normal, 3000, "5202", "[0] ID: u16=42", "[1] Index: u16=4", "[2] Description: \"去往 八一路 洪山侧路\"", "[3] Type: u8=1");
            frame(normal, 4000, "5201", "[0] ID: u16=42", "[1] State: u8=1", "[13] List: u16=4", "[10] Distance: u32=46");
            normal.decision(BASE + 4000, true, "SEND");
            normal.oem(BASE, DiagnosticOemTrace.select(receiverRoad, 1000, BASE, BASE));
            normal.dispatch(BASE + 4000, "com.baidu.car.navi.INFO", "target=cluster name=turn_left distance=50 nextRoadName=八一路", true);
            normal.dispatch(BASE + 4000, "com.baidu.car.radio.notify.PLAY_INFO", "target=cluster title=歌曲乙 is_playing=true", true);
            normal.dispatch(BASE + 5000, "com.baidu.car.radio.notify.PLAY_INFO", "target=cluster clear=true title= is_playing=false", true);
            String summary = normal.summary(BASE + 180000, "到时", 2);
            check(summary.contains("新导航帧：2；完整解析：2"), "Current trusted frames parsed including identifier");
            check(summary.contains("音乐分发提交：1 次"), "Exclude music clear from real dispatch count");
            check(summary.contains("导航分发提交：1 次"), "Count actual submission");
            check(summary.contains("没有确认回执，未验证") && summary.contains("仅说明已向系统提交"), "Summary uses explicit receipt boundary");
            check(summary.contains("队列丢弃：2"), "Expose lost events");
            check(summary.contains("原车接收端导航记录：1") && summary.contains("不是显示回执"), "OEM evidence remains separate from display ACK");
            Files.write(normalFolder.toPath().resolve("environment.txt"), "只读环境".getBytes(StandardCharsets.UTF_8));
            Files.write(normalFolder.toPath().resolve("private-contact.txt"), "NEVER_EXPORT".getBytes(StandardCharsets.UTF_8));
            File archive = normal.finish(BASE + 180000, "用户结束", 2);
            String events = new String(Files.readAllBytes(normalFolder.toPath().resolve("events.tsv")), StandardCharsets.UTF_8);
            check(events.contains("八一路"), "Retain useful source road");
            for (String secret : new String[]{"CONTACT_SECRET", "13800138000", "SECRET_RAW", "HISTORICAL_SECRET", "IAP2 RX [wired]"})
                check(!events.contains(secret), "No private/raw source data: " + secret);
            try (ZipFile zip = new ZipFile(archive)) {
                check(zip.size() == 3, "Only three explicit files exported");
                check(zip.getEntry("summary.txt") != null && zip.getEntry("environment.txt") != null && zip.getEntry("events.tsv") != null, "Expected archive layout");
                check(zip.getEntry("private-contact.txt") == null, "Never export unrelated file");
            }
            // The same archive can be rebuilt after interruption/retry.
            check(DiagnosticReport.zip(normalFolder).isFile(), "Retry archive rebuild");

            DiagnosticReport missing = new DiagnosticReport(root.resolve("missing").toFile(), BASE);
            snapshot(missing, true, true, true, true, null, 50, "歌曲甲", 6000);
            String absent = missing.summary(BASE + 1000, "结束", 0);
            check(absent.contains("未观察到有效导航指令"), "Stale widget cannot satisfy navigation evidence");
            check(absent.contains("音乐变化链路尚未验证"), "Unchanged music cannot verify change path");
            check(absent.contains("不能评估导航分发"), "Missing input must not be called passing");
            check(missing.overview().contains("开始真实导航后重采"), "Saved result prompts user to fill missing route");
            missing.close();

            DiagnosticReport blocked = new DiagnosticReport(root.resolve("blocked").toFile(), BASE);
            snapshot(blocked, true, true, false, false, "签名变化", 50, "歌曲甲", 0);
            check(blocked.progress(1000).contains("校验未通过"), "Show interface block before suggesting more song changes");
            blocked.dispatch(BASE, "com.baidu.car.navi.INFO", "SecurityException", false);
            String failed = blocked.summary(BASE, "结束", 0);
            check(failed.contains("阻止转发") && failed.contains("导航转发关闭"), "Explain settings and signature guard");
            check(failed.contains("分发异常：1") && failed.contains("broadcast_failed"), "Record dispatch failure");
            blocked.close();

            DiagnosticReport incomplete = new DiagnosticReport(root.resolve("incomplete").toFile(), BASE);
            frame(incomplete, 3000, "5202", "[0] ID: u16=42", "[1] Index: u16=4", "[3] Type: invalid");
            String incompleteSummary = incomplete.summary(BASE + 4000, "结束", 0);
            check(incompleteSummary.contains("新导航帧：1；完整解析：0") && incompleteSummary.contains("未形成完整解析"), "Frame receipt does not imply successful parse");
            incomplete.close();

            File limitedFolder = root.resolve("limited").toFile();
            DiagnosticReport limited = new DiagnosticReport(limitedFolder, BASE);
            String longChinese = new String(new char[2000]).replace('\0', '路');
            for (int i = 0; i < 2000; i++) limited.event(BASE + i, "limit", longChinese);
            limited.flush();
            check(new File(limitedFolder, "events.tsv").length() <= DiagnosticReport.EVENT_LIMIT, "UTF-8 byte limit");
            check(limited.summary(BASE, "结束", 0).matches("(?s).*文件达到上限省略：[1-9][0-9]*.*"), "Report truncation");
            limited.finish(BASE, "结束", 0);
            String clipped = DiagnosticReport.clean("A😀Z\nB", 2);
            check("A".equals(clipped), "Do not split surrogate pairs");
            check(!DiagnosticReport.clean("A\nB\tC\rD\0E", 100).matches("(?s).*[\\n\\r\\t\\x00].*"), "One safe event per row");
            Files.write(limitedFolder.toPath().resolve("environment.txt"), new byte[128 * 1024 + 1]);
            boolean tooLarge = false;
            try { DiagnosticReport.zip(limitedFolder); } catch (java.io.IOException expected) { tooLarge = true; }
            check(tooLarge, "Reject oversized environment on retry");
            File empty = root.resolve("empty").toFile(); empty.mkdirs();
            boolean emptyRejected = false;
            try { DiagnosticReport.zip(empty); } catch (java.io.IOException expected) { emptyRejected = true; }
            check(emptyRejected, "Cannot claim a saved report when required files are missing");
            System.out.println("VehicleDiagnosticTest: " + checks + " checks passed");
        } finally { delete(root.toFile()); }
    }
}
