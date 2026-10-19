package com.local.diplay.clusterbridge;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class DiagnosticLogTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("bridge-diagnostic-test-");
        try {
            BoundedDiagnosticLog log = new BoundedDiagnosticLog(folder.toFile(), 8192);
            for (int i = 0; i < 2000; i++) log.append("sample=" + i + " interactive=true mode=0 musicActive=true 中文");
            log.append(new String(new char[9000]).replace('\0', '长'));
            long total = 0; int files = 0;
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path p : stream) {
                    files++; long bytes = Files.size(p); total += bytes;
                    if (bytes > 8192) throw new AssertionError("File exceeded bound");
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (text.indexOf('\ufffd') >= 0) throw new AssertionError("UTF-8 split during clipping");
                }
            }
            if (files != 2 || total > 16384) throw new AssertionError("Rotation not bounded");
            System.out.println("Diagnostic log tests passed (two-file rotation, byte limit, UTF-8-safe long lines)");
        } finally {
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) { for (Path p : stream) Files.deleteIfExists(p); }
            Files.deleteIfExists(folder);
        }
    }
}
