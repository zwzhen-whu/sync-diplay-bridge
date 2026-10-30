package com.local.diplay.clusterbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;

/** Exists only during a diagnostic session; no system log clearing or root access. */
final class DiagnosticOemReader {
    private volatile Process process;
    private volatile boolean stopped;
    DiagnosticOemReader(Context context, long wall, Consumer<String> selected) {
        Thread thread = new Thread(() -> {
            try {
                if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
                    selected.accept("oem_reader 未授权 READ_LOGS，跳过原车接收端日志"); return;
                }
                int uid = context.getPackageManager().getApplicationInfo(BridgeService.CLUSTER, 0).uid;
                Process child = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime", "-v", "uid", "-v", "epoch", "-T", "1", "-s",
                    "NavPresenterImpl:I", "MediaReceiver:I").redirectErrorStream(true).start();
                process = child;
                if (stopped) { child.destroy(); return; }
                selected.accept("oem_reader 已启动；仅接收本次会话的原车导航/音乐白名单消息，无日志不能判定接口故障");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while (!stopped && (line = reader.readLine()) != null) {
                        String message = DiagnosticOemTrace.select(line, uid, wall, System.currentTimeMillis());
                        if (message != null) selected.accept(message);
                    }
                }
                if (!stopped) selected.accept("oem_reader 日志读取结束，接收端观察可能不完整");
            } catch (Exception e) {
                if (!stopped) selected.accept("oem_reader 不可用：" + e.getClass().getSimpleName());
            } finally { Process child = process; if (child != null) child.destroy(); process = null; }
        }, "vehicle-oem-diagnostic");
        thread.setDaemon(true); thread.start();
    }
    void close() { stopped = true; Process child = process; if (child != null) child.destroy(); }
}
