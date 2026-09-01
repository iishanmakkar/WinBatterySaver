package com.batterysaver.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Portable mode detection: --portable flag or portable.flag file next to executable.
 * When portable, SettingsService writes config.json next to exe instead of %APPDATA%.
 * Startup registration is disabled/greyed out in this mode.
 */
public class PortableMode {
    private static Boolean cached = null;
    private static Path exeDirCached = null;

    public static boolean isPortable(List<String> rawArgs) {
        if (cached != null) return cached;
        // Check --portable arg
        if (rawArgs != null && rawArgs.stream().anyMatch(a -> a.equalsIgnoreCase("--portable"))) {
            cached = true;
            return true;
        }
        // Check portable.flag next to exe or next to working dir
        Path dir = getExeDir();
        if (Files.exists(dir.resolve("portable.flag"))) {
            cached = true;
            return true;
        }
        // Also check CWD for dev runs
        if (Files.exists(Path.of("portable.flag"))) {
            cached = true;
            return true;
        }
        cached = false;
        return false;
    }

    public static boolean isPortable() {
        return isPortable(null);
    }

    /** For tests: reset cache */
    static void reset() { cached = null; }

    public static Path getExeDir() {
        if (exeDirCached != null) return exeDirCached;
        try {
            String cmd = ProcessHandle.current().info().command().orElse("");
            if (!cmd.isBlank()) {
                Path p = Path.of(cmd).getParent();
                if (p != null && Files.exists(p)) {
                    exeDirCached = p;
                    return p;
                }
            }
        } catch (Exception ignored) {}
        exeDirCached = Path.of("").toAbsolutePath();
        return exeDirCached;
    }

    public static Path getConfigPath(List<String> rawArgs) {
        if (isPortable(rawArgs)) {
            return getExeDir().resolve("config.json");
        } else {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) appData = System.getProperty("java.io.tmpdir");
            return Path.of(appData, "BatterySaver", "config.json");
        }
    }

    public static Path getConfigPath() { return getConfigPath(null); }
}
