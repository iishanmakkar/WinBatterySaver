package com.batterysaver.util;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single-instance enforcement via file lock.
 * Lock file: %APPDATA%\BatterySaver\.lock
 * If lock cannot be acquired, caller should signal existing instance and exit.
 * Fail-open: if any exception occurs, return true (allow launch) rather than blocking legitimate use.
 */
public class SingleInstanceGuard {
    private static FileLock lock;
    private static RandomAccessFile raf;
    private static Path lockFile;

    public static boolean acquire() {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) {
                appData = System.getProperty("java.io.tmpdir");
            }
            lockFile = Path.of(appData, "BatterySaver", ".lock");
            Files.createDirectories(lockFile.getParent());
            raf = new RandomAccessFile(lockFile.toFile(), "rw");
            lock = raf.getChannel().tryLock();
            if (lock == null) {
                // Another instance holds the lock
                try { raf.close(); } catch (Exception ignored) {}
                lock = null;
                raf = null;
                return false;
            }
            // Keep lock held; add shutdown hook to release
            Runtime.getRuntime().addShutdownHook(new Thread(() -> release()));
            return true;
        } catch (Exception e) {
            System.err.println("SingleInstanceGuard.acquire failed (fail-open): " + e.getMessage());
            return true;
        }
    }

    public static void release() {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (raf != null) {
                raf.close();
                raf = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Signal existing instance to focus itself by writing a flag file it polls.
     */
    public static void signalFocus() {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) appData = System.getProperty("java.io.tmpdir");
            Path flag = Path.of(appData, "BatterySaver", "focus-me.flag");
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            System.err.println("SingleInstanceGuard.signalFocus failed: " + e.getMessage());
        }
    }

    public static Path getFocusFlagPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) appData = System.getProperty("java.io.tmpdir");
        return Path.of(appData, "BatterySaver", "focus-me.flag");
    }

    public static Path getLockFile() { return lockFile; }
}
