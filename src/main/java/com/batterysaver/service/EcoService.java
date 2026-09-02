package com.batterysaver.service;

import com.batterysaver.interop.User32Ext;
import com.batterysaver.util.PortableMode;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * EnergyStarX-inspired Eco service for WBS, with max improvements for any laptop.
 * - Uses EcoQoS (PROCESS_POWER_THROTTLING_EXECUTION_SPEED) on Win11 22000+, fallback to IDLE_PRIORITY on Win10
 * - Event-driven foreground hook (SetWinEventHook) + PowerSource poll (battery) + 5-min housekeeping
 * - Session-isolated, whitelist/blacklist wildcards, low overhead, cross-OEM
 */
public class EcoService {

    public enum ThrottleStatus { STOPPED, ONLY_BLACKLIST, FULL }

private static final int EVENT_SYSTEM_FOREGROUND = 3;
    private static final int WINEVENT_OUTOFCONTEXT = 0x0000;
    private static final int PROCESS_POWER_THROTTLING = 4;
    private static final int PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1;
    private static final int IDLE_PRIORITY_CLASS = 0x40;
    private static final int NORMAL_PRIORITY_CLASS = 0x20;

    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService housekeeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eco-housekeeper");
        t.setDaemon(true);
        return t;
    });

    // Written under lock in updateThrottleStatus, read from housekeeper thread -> volatile
    private volatile ThrottleStatus status = ThrottleStatus.STOPPED;

    // Pids where we lowered the priority class (fallback path only), so we only
    // restore NORMAL on processes we actually changed (don't clobber user priorities).
    private final Set<Long> priorityLoweredPids = ConcurrentHashMap.newKeySet();
    // Pids we applied EcoQoS to. Unthrottling is scoped to this set so we never
    // clear an Efficiency Mode the user set manually via Task Manager.
    private final Set<Long> ecoThrottledPids = ConcurrentHashMap.newKeySet();

    private volatile boolean pauseThrottling = false;
    private volatile boolean throttleWhenPluggedIn = false;

    // Whitelist/blacklist - volatile for thread safety.
    // Defaults = shell/system processes + the EcoQoS engine's known-conflict list
    // (discord/steam/obs/mouse software) so the two engines never disagree about
    // what is safe to throttle.
    private static final Set<String> DEFAULT_ECO_WHITELIST = Set.of(
            "wbs.exe", "wbs", "batterysaver", "msedge.exe", "firefox.exe", "taskmgr.exe",
            "dwm.exe", "explorer.exe", "sihost.exe", "searchhost.exe", "startmenuexperiencehost.exe",
            "shellexperiencehost.exe", "applicationframehost.exe", "textinputhost.exe", "ctfmon.exe",
            "csrss.exe", "winlogon.exe", "services.exe", "svchost.exe", "lsass.exe",
            "logioptionsplus.exe", "logioptionsplus_agent.exe", "steam.exe", "steamwebhelper.exe",
            "discord.exe", "startallback.exe", "explorerpatcher.exe", "obs64.exe", "obs32.exe",
            // Console infrastructure - throttling the console host stalls pipe I/O
            "conhost.exe", "openconsole.exe", "windowsterminal.exe",
            // Java launchers - protects dev/fat-jar runs (app must not throttle itself)
            "java.exe", "javaw.exe", "windowsbatterysaver.exe"
    );
    private volatile Set<String> whitelist = new HashSet<>(DEFAULT_ECO_WHITELIST);
    private volatile Set<String> blacklist = new HashSet<>();
    private volatile Set<String> wildcardWhitelist = new HashSet<>();
    private volatile Set<String> wildcardBlacklist = new HashSet<>();

    // Native function pointers (loaded reflectively for fallback)
    private com.sun.jna.Function setProcessInformationFn;
    private com.sun.jna.Function setPriorityClassFn;
    private boolean ecoSupported = true;
    private int currentSessionId = -1;

    private final Pointer pThrottleOn;
    private final Pointer pThrottleOff;
    // PROCESS_POWER_THROTTLING_STATE = Version(4) + ControlMask(4) + StateMask(4) = 12 bytes
    private static final int CONTROL_BLOCK_SIZE = 12;

    public EcoService() {
        // Detect Windows build for EcoQoS support.
        // GetVersionEx lies (returns 6.2.9200) without a compat manifest, so read the
        // build number from the registry instead.
        try {
            String build = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
                    "CurrentBuildNumber");
            if (build != null) {
                ecoSupported = Integer.parseInt(build.trim()) >= 22000;
            }
        } catch (Exception ignored) {
            // Keep default (true); SetProcessInformation failure falls back to priority anyway
        }

        // Pre-allocate control blocks for SetProcessInformation
        // PROCESS_POWER_THROTTLING_STATE {Version=1, ControlMask=1, StateMask=0/1}
        pThrottleOn = new Memory(12);
        pThrottleOn.setInt(0, 1); // Version
        pThrottleOn.setInt(4, PROCESS_POWER_THROTTLING_EXECUTION_SPEED); // ControlMask
        pThrottleOn.setInt(8, PROCESS_POWER_THROTTLING_EXECUTION_SPEED); // StateMask = enabled

        pThrottleOff = new Memory(12);
        pThrottleOff.setInt(0, 1);
        pThrottleOff.setInt(4, PROCESS_POWER_THROTTLING_EXECUTION_SPEED);
        pThrottleOff.setInt(8, 0); // StateMask = disabled

        try {
            NativeLibrary k32 = NativeLibrary.getInstance("kernel32");
            setProcessInformationFn = k32.getFunction("SetProcessInformation");
            setPriorityClassFn = k32.getFunction("SetPriorityClass");
        } catch (Exception e) {
            System.err.println("EcoService: SetProcessInformation not available, fallback to priority only: " + e.getMessage());
            ecoSupported = false;
        }

        try {
            currentSessionId = Kernel32.INSTANCE.GetCurrentProcessId(); // placeholder, will resolve via ProcessIdToSessionId
            IntByReference sess = new IntByReference();
            if (Kernel32.INSTANCE.ProcessIdToSessionId(Kernel32.INSTANCE.GetCurrentProcessId(), sess)) {
                currentSessionId = sess.getValue();
            }
        } catch (Exception ignored) {}

        loadListsFromDisk();
    }

    public void initialize() {
        lock.lock();
        try {
            // Housekeeping every 5 minutes (like EnergyStarX)
            housekeeper.scheduleWithFixedDelay(() -> {
                try {
                    throttleUserBackgroundProcesses();
                } catch (Exception e) {
                    System.err.println("Eco housekeeping error: " + e.getMessage());
                }
            }, 5, 5, TimeUnit.MINUTES);

            System.out.println("EcoService initialized, ecoSupported=" + ecoSupported + " session=" + currentSessionId);
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            housekeeper.shutdownNow();
            // Unthrottle all on exit
            setPauseThrottling(true);
            updateThrottleStatus(true, false);
        } finally {
            lock.unlock();
        }
    }

    public void setPauseThrottling(boolean pause) {
        lock.lock();
        try {
            pauseThrottling = pause;
        } finally {
            lock.unlock();
        }
    }

    public void setThrottleWhenPluggedIn(boolean v) {
        throttleWhenPluggedIn = v;
        saveListsToDisk();
    }

    public boolean isThrottleWhenPluggedIn() { return throttleWhenPluggedIn; }
    public boolean isPaused() { return pauseThrottling; }
    public ThrottleStatus getStatus() { return status; }

    /**
     * Called from MainViewModel poll when AC status changes or periodically.
     * Determines status and throttles accordingly.
     */
    public void updateThrottleStatus(boolean isOnBattery, boolean isPowerSaverOn) {
        lock.lock();
        try {
            // Improved logic over EnergyStarX: also throttle when Power Saver is ON even if plugged, to save battery
            ThrottleStatus newStatus;
            if (pauseThrottling) {
                newStatus = ThrottleStatus.STOPPED;
            } else if (isOnBattery) {
                // On battery: always full throttle (blacklist + all but whitelist), regardless of plugged pref
                newStatus = ThrottleStatus.FULL;
            } else if (isPowerSaverOn || throttleWhenPluggedIn) {
                // Plugged but user wants eco (or Power Saver implies eco)
                newStatus = ThrottleStatus.FULL;
            } else {
                newStatus = ThrottleStatus.ONLY_BLACKLIST;
            }

            if (newStatus != status) {
                System.out.println("EcoService: status " + status + " -> " + newStatus + " (onBattery=" + isOnBattery + " saver=" + isPowerSaverOn + ")");
                status = newStatus;
                applyStatus(newStatus);
            }
        } finally {
            lock.unlock();
        }
    }

    private void applyStatus(ThrottleStatus s) {
        switch (s) {
            case STOPPED:
                // Unthrottle all (set to normal)
                unthrottleAll();
                break;
            case ONLY_BLACKLIST:
                // Only blacklist - unthrottle all then apply blacklist
                unthrottleAll();
                // Note: full blacklist/throttleList functionality available in full EcoService
                // For Phase 13, using EcoQoS throttling instead
                break;
            case FULL:
                // Throttle blacklist + all but whitelist
                throttleUserBackgroundProcesses();
                break;
        }
    }

    private void unthrottleAll() {
        // Only iterate processes we actually touched - no full system scan, and we
        // never reset state on processes the user configured themselves.
        Set<Long> ours = new HashSet<>(ecoThrottledPids);
        ours.addAll(priorityLoweredPids);
        for (Long pid : ours) {
            if (pid.intValue() == ProcessHandle.current().pid()) continue;
            try {
                toggleEfficiencyMode(pid.intValue(), "", false);
            } catch (Exception ignored) {}
        }
    }

    public void throttleUserBackgroundProcesses() {
        // Prune dead PIDs first (PID reuse would make stale entries unthrottle an
        // unrelated process later)
        ecoThrottledPids.removeIf(pid -> !ProcessHandle.of(pid).isPresent());
        priorityLoweredPids.removeIf(pid -> !ProcessHandle.of(pid).isPresent());

        // NEVER throttle the app the user is actively using (same rule as the
        // EcoQoS engine): skip the foreground process, its exe-name siblings,
        // its process tree (focused terminal's child shells), and ANY process
        // that owns a visible non-minimized window (Notepad, Settings, ...)
        long foregroundPid = getForegroundPid();
        String foregroundExe = "";
        if (foregroundPid > 0) {
            foregroundExe = ProcessHandle.of(foregroundPid)
                    .flatMap(ph -> ph.info().command())
                    .map(EcoService::extractExeName)
                    .orElse("");
        }
        Set<Long> visiblePids = com.batterysaver.util.VisibleWindows.visibleWindowPids();

        int throttled = 0;
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            try {
                long pidL = ph.pid();
                if (pidL == ProcessHandle.current().pid()) continue;
                if (pidL == foregroundPid) continue;
                if (visiblePids.contains(pidL)) continue;
                int pid = (int) pidL;
                // Session isolation
                try {
                    IntByReference sess = new IntByReference();
                    if (Kernel32.INSTANCE.ProcessIdToSessionId(pid, sess) && sess.getValue() != currentSessionId) continue;
                } catch (Exception ignored) {}

                String cmd = ph.info().command().orElse("");
                String name = extractExeName(cmd);
                if (name.isEmpty()) {
                    // commandLine() includes arguments - strip them so "chrome.exe --type=renderer"
                    // still matches the "chrome.exe" whitelist entry
                    name = extractExeName(ph.info().commandLine().orElse("pid:" + pid));
                }
                boolean foregroundTree = (!foregroundExe.isEmpty() && name.equals(foregroundExe))
                        || com.batterysaver.util.ProcessTree.isInTree(pidL, foregroundPid);
                if (foregroundTree) continue;

                // Whitelist check
                boolean inWhitelist = isInList(name, whitelist, wildcardWhitelist);
                boolean inBlacklist = isInList(name, blacklist, wildcardBlacklist);

                if (status == ThrottleStatus.FULL) {
                    if (inWhitelist) {
                        toggleEfficiencyMode(pid, name, false);
                    } else {
                        // Throttle blacklist + all others
                        toggleEfficiencyMode(pid, name, true);
                        throttled++;
                    }
                } else if (status == ThrottleStatus.ONLY_BLACKLIST) {
                    if (inBlacklist) {
                        toggleEfficiencyMode(pid, name, true);
                        throttled++;
                    }
                }
            } catch (Exception ignored) {}
        }
        System.out.println("EcoService: throttled " + throttled + " processes for status " + status);
    }

    private long getForegroundPid() {
        try {
            com.sun.jna.platform.win32.WinDef.HWND fg = User32Ext.INSTANCE.GetForegroundWindow();
            if (fg == null) return -1;
            IntByReference pidRef = new IntByReference();
            int threadId = User32Ext.INSTANCE.GetWindowThreadProcessId(fg, pidRef);
            return threadId > 0 ? pidRef.getValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Extracts the bare exe name from a command line / path, lowercased, arguments stripped.
     *  Handles quoted paths and paths containing spaces ("C:\Program Files\...\app.exe" --arg).
     *  Package-private for testing. */
    static String extractExeName(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        String s = cmd.trim();
        if (s.startsWith("\"")) {
            // Quoted executable: take everything inside the first pair of quotes
            int end = s.indexOf('"', 1);
            s = end > 0 ? s.substring(1, end) : s.substring(1);
        } else {
            // Unquoted: the exe ends at ".exe" followed by whitespace/quote/end.
            // (A naive first-space split turns "C:\Program Files\..." into "program".)
            String lower = s.toLowerCase();
            int dot = lower.indexOf(".exe");
            while (dot >= 0) {
                int after = dot + 4;
                if (after >= s.length() || s.charAt(after) == ' ' || s.charAt(after) == '"') break;
                dot = lower.indexOf(".exe", dot + 1);
            }
            if (dot >= 0) {
                s = s.substring(0, dot + 4);
            } else {
                int sp = s.indexOf(' ');
                if (sp > 0) s = s.substring(0, sp);
            }
        }
        int slash = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        return name.toLowerCase();
    }

    private boolean isInList(String name, Set<String> exact, Set<String> wildcards) {
        if (exact.contains(name)) return true;
        String lower = name.toLowerCase();
        for (String w : exact) {
            if (w.toLowerCase().equals(lower)) return true;
        }
        for (String pat : wildcards) {
            String regex = pat.toLowerCase().replace(".", "\\.").replace("*", ".*").replace("?", ".");
            if (Pattern.matches(regex, lower)) return true;
        }
        return false;
    }

    private void toggleEfficiencyMode(int pid, String name, boolean enable) {
        HANDLE h = null;
        try {
            int access = 0x0200 | 0x0400; // SetInformation | QueryLimited
            h = Kernel32.INSTANCE.OpenProcess(access, false, pid);
            if (h == null || h.equals(WinBase.INVALID_HANDLE_VALUE)) return;

            if (enable) {
                boolean ecoOk = false;
                if (ecoSupported && setProcessInformationFn != null) {
                    try {
                        int res = setProcessInformationFn.invokeInt(new Object[]{h, PROCESS_POWER_THROTTLING, pThrottleOn, CONTROL_BLOCK_SIZE});
                        ecoOk = res != 0;
                    } catch (Exception e) {
                        ecoOk = false;
                    }
                }
                if (ecoOk) {
                    ecoThrottledPids.add((long) pid);
                } else {
                    // Only degrade priority when EcoQoS is unavailable (Win10 fallback).
                    // Applying IDLE unconditionally would be far more aggressive than EcoQoS.
                    try {
                        if (setPriorityClassFn != null) {
                            setPriorityClassFn.invokeInt(new Object[]{h, IDLE_PRIORITY_CLASS});
                        } else {
                            Kernel32.INSTANCE.SetPriorityClass(h, new WinDef.DWORD(IDLE_PRIORITY_CLASS));
                        }
                        priorityLoweredPids.add((long) pid);
                    } catch (Exception ignored) {}
                }
            } else {
                // Unthrottle ONLY processes we throttled ourselves
                if (!ecoThrottledPids.remove((long) pid) && !priorityLoweredPids.contains((long) pid)) {
                    return;
                }
                if (ecoSupported && setProcessInformationFn != null) {
                    try {
                        setProcessInformationFn.invokeInt(new Object[]{h, PROCESS_POWER_THROTTLING, pThrottleOff, CONTROL_BLOCK_SIZE});
                    } catch (Exception ignored) {}
                }
                // Restore priority ONLY on processes we lowered ourselves
                if (priorityLoweredPids.remove((long) pid)) {
                    try {
                        if (setPriorityClassFn != null) {
                            setPriorityClassFn.invokeInt(new Object[]{h, NORMAL_PRIORITY_CLASS});
                        } else {
                            Kernel32.INSTANCE.SetPriorityClass(h, new WinDef.DWORD(NORMAL_PRIORITY_CLASS));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // Ignore access denied for protected processes
        } finally {
            if (h != null) Kernel32.INSTANCE.CloseHandle(h);
        }
    }

    // Persistence for lists - colocate with the app config dir (portable-aware)
    private Path getEcoDir() {
        return PortableMode.getConfigPath().getParent();
    }

    private void loadListsFromDisk() {
        try {
            Path dir = getEcoDir();
            Path wFile = dir.resolve("eco_whitelist.txt");
            Path bFile = dir.resolve("eco_blacklist.txt");
            if (Files.exists(wFile)) {
                List<String> lines = Files.readAllLines(wFile);
                // Merge with defaults so a file saved by an older version doesn't
                // drop the shell/known-conflict entries
                Set<String> exact = new HashSet<>(DEFAULT_ECO_WHITELIST);
                Set<String> wild = new HashSet<>();
                for (String l : lines) {
                    String s = l.trim();
                    if (s.isEmpty() || s.startsWith("//")) continue;
                    if (s.contains("*") || s.contains("?")) wild.add(s);
                    else exact.add(s);
                }
                whitelist = exact;
                wildcardWhitelist = wild;
            }
            if (Files.exists(bFile)) {
                List<String> lines = Files.readAllLines(bFile);
                Set<String> exact = new HashSet<>();
                Set<String> wild = new HashSet<>();
                for (String l : lines) {
                    String s = l.trim();
                    if (s.isEmpty() || s.startsWith("//")) continue;
                    if (s.contains("*") || s.contains("?")) wild.add(s);
                    else exact.add(s);
                }
                blacklist = exact;
                wildcardBlacklist = wild;
            }
            // Settings for plugged-in
            Path cfg = dir.resolve("eco_config.txt");
            if (Files.exists(cfg)) {
                String txt = Files.readString(cfg);
                throttleWhenPluggedIn = txt.contains("throttleWhenPluggedIn=true");
                pauseThrottling = txt.contains("pause=true");
            }
        } catch (Exception e) {
            System.err.println("Eco load failed: " + e.getMessage());
        }
    }

    private void saveListsToDisk() {
        try {
            Path dir = getEcoDir();
            Files.createDirectories(dir);
            // Save whitelist
            StringBuilder sb = new StringBuilder();
            for (String s : whitelist) sb.append(s).append("\n");
            for (String s : wildcardWhitelist) sb.append(s).append("\n");
            Files.writeString(dir.resolve("eco_whitelist.txt"), sb.toString());
            sb = new StringBuilder();
            for (String s : blacklist) sb.append(s).append("\n");
            for (String s : wildcardBlacklist) sb.append(s).append("\n");
            Files.writeString(dir.resolve("eco_blacklist.txt"), sb.toString());
            String cfg = "throttleWhenPluggedIn=" + throttleWhenPluggedIn + "\npause=" + pauseThrottling + "\n";
            Files.writeString(dir.resolve("eco_config.txt"), cfg);
        } catch (Exception e) {
            System.err.println("Eco save failed: " + e.getMessage());
        }
    }

    public void setWhitelist(List<String> list) {
        lock.lock();
        try {
            Set<String> exact = new HashSet<>();
            Set<String> wild = new HashSet<>();
            for (String s : list) {
                String t = s.trim();
                if (t.isEmpty() || t.startsWith("//")) continue;
                if (t.contains("*") || t.contains("?")) wild.add(t);
                else exact.add(t);
            }
            whitelist = exact;
            wildcardWhitelist = wild;
            saveListsToDisk();
        } finally {
            lock.unlock();
        }
    }

    public void setBlacklist(List<String> list) {
        lock.lock();
        try {
            Set<String> exact = new HashSet<>();
            Set<String> wild = new HashSet<>();
            for (String s : list) {
                String t = s.trim();
                if (t.isEmpty() || t.startsWith("//")) continue;
                if (t.contains("*") || t.contains("?")) wild.add(t);
                else exact.add(t);
            }
            blacklist = exact;
            wildcardBlacklist = wild;
            saveListsToDisk();
        } finally {
            lock.unlock();
        }
    }

    public List<String> getWhitelist() {
        List<String> out = new ArrayList<>(whitelist);
        out.addAll(wildcardWhitelist);
        return out;
    }

    public List<String> getBlacklist() {
        List<String> out = new ArrayList<>(blacklist);
        out.addAll(wildcardBlacklist);
        return out;
    }
}
