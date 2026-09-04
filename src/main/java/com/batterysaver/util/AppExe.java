package com.batterysaver.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the executable the app is currently running from.
 *
 * Used by autostart registration and elevation-restart: for the jpackage
 * launcher the current process IS WindowsBatterySaver.exe, but
 * ProcessHandle.command() can return blank on some configurations (and returns
 * java.exe in dev runs) - both cases previously made autostart silently fail.
 */
public final class AppExe {

    private AppExe() {}

    /**
     * Absolute path to the running app executable, or null when the app is a
     * dev run (java.exe/javaw.exe) or the path cannot be reliably resolved.
     * NEVER returns a JVM path - autostart must never register java.exe.
     */
    public static String currentExePath() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse("").trim();
            if (cmd.isBlank()) return null;
            String name = cmd.substring(Math.max(cmd.lastIndexOf('\\'), cmd.lastIndexOf('/')) + 1).toLowerCase();
            // Dev run (jar/IDE): registering java.exe would autostart a bare JVM
            if (name.equals("java.exe") || name.equals("javaw.exe")) return null;
            Path p = Path.of(cmd);
            if (!Files.isRegularFile(p)) return null;
            return p.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when running as the packaged exe (autostart/elevation supported). */
    public static boolean isPackagedExe() {
        return currentExePath() != null;
    }
}
