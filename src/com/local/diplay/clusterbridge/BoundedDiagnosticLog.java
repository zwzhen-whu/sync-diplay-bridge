package com.local.diplay.clusterbridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Two bounded files; no growing in-memory buffer or persistent writer queue. */
public final class BoundedDiagnosticLog {
    private final File current, previous;
    private final int limit;
    public BoundedDiagnosticLog(File directory, int limit) throws IOException {
        if (limit < 8192) throw new IllegalArgumentException("Diagnostic file limit too small");
        Files.createDirectories(directory.toPath());
        current = new File(directory, "wake-current.log"); previous = new File(directory, "wake-previous.log"); this.limit = limit;
    }
    public synchronized void append(String text) throws IOException {
        if (text == null) return;
        int end = Math.min(text.length(), 1500);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        byte[] bytes = (text.substring(0, end).replace('\n', ' ').replace('\r', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
        if (current.length() + bytes.length > limit)
            Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (FileOutputStream output = new FileOutputStream(current, true)) { output.write(bytes); }
    }
}
