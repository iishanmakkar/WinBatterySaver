package com.batterysaver.service;

import com.batterysaver.interop.Kernel32Ext;
import com.batterysaver.interop.PowrProfExt;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class PowerPlanService {
    public static final String BALANCED = "381b4222-f694-41f0-9685-ff5bb260df2e";
    public static final String POWER_SAVER_DEFAULT = "a1841308-3541-4fab-bc81-f71556f20b4a";
    public static final String HIGH_PERFORMANCE = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
    private static final int TIMEOUT_SECONDS = 4;
    // powercfg /list output is only used to detect custom OEM saver schemes;
    // cache it so the poller doesn't spawn a process every 5s.
    private static final long LIST_CACHE_MS = 5 * 60 * 1000L;
    private volatile String listCache;
    private volatile long listCacheAt = 0;

    public void setActivePlan(String guid) throws Exception {
        if (guid == null || guid.isBlank()) return;
        int exit = run("powercfg", "/setactive", guid);
        if (exit != 0) {
            System.err.println("powercfg /setactive " + guid + " exit code " + exit);
        }
    }

    /** @return true when the activation actually took effect (verified via PowerGetActiveScheme). */
    public boolean activatePlanVerified(String guid) {
        if (guid == null || guid.isBlank()) return false;
        try {
            int exit = run("powercfg", "/setactive", guid);
            if (exit != 0) {
                System.err.println("powercfg /setactive " + guid + " exit code " + exit);
            }
            String now = getActivePlanGuid();
            return guid.equalsIgnoreCase(now);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Active plan GUID. Primary path is PowerGetActiveScheme via JNA (no process
     * spawn); powercfg /getactivescheme is only a fallback.
     */
    public String getActivePlanGuid() {
        try {
            PointerByReference ref = new PointerByReference();
            int rc = PowrProfExt.INSTANCE.PowerGetActiveScheme(null, ref);
            if (rc == 0 && ref.getValue() != null) {
                Pointer p = ref.getValue();
                try {
                    long d1 = Integer.toUnsignedLong(p.getInt(0));
                    int d2 = p.getShort(4) & 0xFFFF;
                    int d3 = p.getShort(6) & 0xFFFF;
                    byte[] d4 = p.getByteArray(8, 8);
                    return String.format("%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                            d1, d2, d3, d4[0], d4[1], d4[2], d4[3], d4[4], d4[5], d4[6], d4[7]);
                } finally {
                    try { Kernel32Ext.INSTANCE.LocalFree(p); } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {
            // JNA unavailable or API blocked - fall through to powercfg
        }
        return getActivePlanGuidViaPowercfg();
    }

    private String getActivePlanGuidViaPowercfg() {
        String out = runCapture("powercfg", "/getactivescheme");
        if (out == null) return null;
        Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    /**
     * True if the GUID refers to a power-saver scheme. GUID match is the primary
     * (locale-independent) mechanism; the localized-name fallback only detects
     * custom schemes on English systems and is cached to avoid process spawns.
     */
    public boolean isPowerSaverGuid(String guid) {
        if (guid == null) return false;
        if (POWER_SAVER_DEFAULT.equalsIgnoreCase(guid)) return true;
        try {
            String list = getListOutputCached();
            if (list != null) {
                for (String line : list.split("\n")) {
                    if (line.toLowerCase().contains(guid.toLowerCase())
                            && (line.toLowerCase().contains("saver") || line.toLowerCase().contains("eco"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String getListOutputCached() {
        String cache = listCache;
        if (cache != null && (System.currentTimeMillis() - listCacheAt) < LIST_CACHE_MS) {
            return cache;
        }
        String out = runCapture("powercfg", "/list");
        if (out != null) {
            listCache = out;
            listCacheAt = System.currentTimeMillis();
        }
        return out;
    }

    public void ensureAndActivatePowerSaver() throws Exception {
        // 1. A saver-named scheme already registered? activate and VERIFY.
        //    (Don't trust the exit code alone - verify the active GUID changed.)
        String existingSaverGuid = findPowerSaverGuidInList();
        if (existingSaverGuid != null && activatePlanVerified(existingSaverGuid)) {
            return;
        }

        // 2. Try the built-in Power Saver GUID (hidden-but-valid on Modern Standby)
        if (activatePlanVerified(POWER_SAVER_DEFAULT)) {
            return;
        }

        // 3. Last resort: duplicate the default saver scheme and activate the copy.
        //    (Only reachable when the built-in GUID is genuinely unusable - never
        //    create duplicates on the happy path.)
        String duplicatedGuid = duplicatePowerSaverScheme();
        if (duplicatedGuid != null) {
            activatePlanVerified(duplicatedGuid);
        }
    }

    public String findPowerSaverGuidInList() {
        try {
            String list = getListOutputCached();
            if (list == null) return null;
            for (String line : list.split("\n")) {
                String lower = line.toLowerCase();
                if (lower.contains("saver") || lower.contains("eco")) {
                    Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(line);
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String duplicatePowerSaverScheme() {
        try {
            String out = runCapture("powercfg", "-duplicatescheme", POWER_SAVER_DEFAULT);
            if (out != null) {
                Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(out);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int run(String... cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            drainTo(p, null);
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, TimeUnit.SECONDS);
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private String runCapture(String... cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            Thread reader = drainTo(p, sb);
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, TimeUnit.SECONDS);
                return null;
            }
            reader.join(500);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Drain output on a daemon thread so the waitFor timeout can actually fire
     *  even if the child process streams without exiting.
     *  NOTE: setDaemon MUST be called BEFORE start() - calling it after throws
     *  IllegalThreadStateException, which used to make run() return -1 even when
     *  the command had actually succeeded. */
    private Thread drainTo(Process p, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb != null) sb.append(line).append("\n");
                }
            } catch (Exception ignored) {}
        }, "powercfg-io");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
