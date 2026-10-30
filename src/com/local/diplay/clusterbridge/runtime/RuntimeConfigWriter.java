package com.local.diplay.clusterbridge.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/** Atomic app-readable config, including recovery from a power cut before chown/rename. */
public final class RuntimeConfigWriter {
    private RuntimeConfigWriter() { }

    public static void write(File target, File temporary, int uid, byte[] bytes) throws Exception {
        if (!target.getParentFile().equals(temporary.getParentFile()) || target.equals(temporary))
            throw new IllegalArgumentException("Config paths");
        StructStat old = stat(temporary);
        if (old != null) {
            // Older releases wrote and synced as root before changing ownership. A reboot
            // could leave exactly this root-owned temporary file. Never follow a link.
            if (!OsConstants.S_ISREG(old.st_mode) || old.st_nlink != 1 || (old.st_uid != 0 && old.st_uid != uid))
                throw new IllegalStateException("Unsafe temporary config " + temporary.getName() + " owner=" + old.st_uid);
            Os.remove(temporary.getPath());
        }
        FileDescriptor fd = Os.open(temporary.getPath(),
            OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
        try (FileOutputStream output = new FileOutputStream(fd)) {
            // Establish app ownership and SELinux categories before writing or syncing.
            Os.fchmod(fd, 0600);
            Os.fchown(fd, uid, uid);
            Process relabel = new ProcessBuilder("restorecon", "-F", temporary.getPath()).redirectErrorStream(true).start();
            if (!relabel.waitFor(3, TimeUnit.SECONDS)) { relabel.destroy(); throw new IllegalStateException("Config label timeout"); }
            if (relabel.exitValue() != 0) throw new IllegalStateException("Config label unavailable");
            output.write(bytes);
            output.getFD().sync();
        }
        Os.rename(temporary.getPath(), target.getPath());
    }

    private static StructStat stat(File path) throws ErrnoException {
        try { return Os.lstat(path.getPath()); }
        catch (ErrnoException error) { if (error.errno == OsConstants.ENOENT) return null; throw error; }
    }
}
