/* EcoQosThrottleService.java — Background process throttling via Windows EcoQoS
 * Windows 10 1709+ EcoQoS (Efficiency Mode) — throttles background processes'
 * CPU scheduling via SetProcessInformation with PROCESS_POWER_THROTTLING_EXECUTION_SPEED.
 * Foreground app is NEVER throttled — only background processes the user isn't
 * actively looking at. Same mechanism Task Manager uses for the green leaf icon.
 *
 * Hardware reality: full benefit needs Intel 10th-gen+ or AMD Ryzen 5000+ or
 * Qualcomm mobile chips, and Windows 11 22H2+. Works on older hardware with
 * reduced effect. Do not oversell in UI copy.
 *
 * Scan interval of 5s - frequent enough to un-throttle the instant a
 * background app gets focus (no perceptible lag), infrequent enough to
 * not itself waste CPU/battery.
 *
 * Known limitations documented (don't hide them):
 * - Mouse-related software may lag if not whitelisted
 * - Some taskbar enhancement tools may become unstable when their process is throttled
 *   while a tray icon is hovered — whitelist proactively
 * - Child processes don't auto-un-throttle just because their parent gets focus
 * - System/Session-0 processes silently skipped (OpenProcess fails)
 */

package com.batterysaver.service;

import com.batterysaver.interop.Kernel32Ext;
import com.batterysaver.interop.PowerThrottlingState;
import com.batterysaver.interop.User32Ext;
import com.batterysaver.util.ProcessTree;
import com.batterysaver.util.VisibleWindows;

import java.util.*;
import java.util.concurrent.*;

public class EcoQosThrottleService {
    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private volatile Set<String> whitelist;
    private final Set<Long> currentlyThrottledPids = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean enabled = false;
    // One-time diagnostics: if a scan attempts throttles and every single one fails,
    // something systemic is wrong (EDR blocking OpenProcess, OS support) - log once.
    private volatile boolean warnedAllThrottlesFailing = false;

    // Default whitelist based on EnergyStarX documented known-conflict list
    public static final Set<String> DEFAULT_WHITELIST = Set.of(
        "logioptionsplus.exe", "logioptionsplus_agent.exe",   // mouse/peripheral software — throttling causes cursor lag
        "steam.exe", "steamwebhelper.exe",                      // gaming overlay/download management
        "discord.exe",                                          // voice chat — throttling causes audio stutter
        "startallback.exe", "explorerpatcher.exe",              // taskbar enhancers — known to crash on tray hover when throttled
        "obs64.exe", "obs32.exe",                                // screen recording — must not be throttled even in background
        "wbs.exe", "windowsbatterysaver.exe",                    // WBS itself, redundant safety alongside the PID check
        "taskmgr.exe",                                           // Task Manager (prevent observer-effect lag)
        // Core shell/system processes - throttling these causes UI/compositor lag and
        // tray instability (the class of problem the header comment warns about)
        "dwm.exe", "explorer.exe", "sihost.exe", "searchhost.exe",
        "startmenuexperiencehost.exe", "shellexperiencehost.exe",
        "applicationframehost.exe", "textinputhost.exe", "ctfmon.exe",
        "csrss.exe", "winlogon.exe", "services.exe", "svchost.exe",
        "lsass.exe", "runtimebroker.exe", "taskhostw.exe", "fontdrvhost.exe",
        // Console infrastructure - throttling the console host stalls pipe I/O and
        // makes every background terminal sluggish
        "conhost.exe", "openconsole.exe", "windowsterminal.exe",
        // Java launcher names - protects dev/fat-jar runs (the app throttling itself
        // freezes its own UI); packaged exe is covered by the names + PID check above
        "java.exe", "javaw.exe"
    );

    public EcoQosThrottleService() {
        this.whitelist = new HashSet<>(DEFAULT_WHITELIST);
    }

    public EcoQosThrottleService(Set<String> whitelist) {
        this.whitelist = new HashSet<>(whitelist);
    }

    /**
     * Replace the whitelist with defaults plus user-supplied extras
     * (comma-separated exe names from settings).
     */
    public void setUserWhitelist(String csv) {
        Set<String> merged = new HashSet<>(DEFAULT_WHITELIST);
        if (csv != null) {
            for (String s : csv.split("[,;]")) {
                String t = s.trim().toLowerCase();
                if (!t.isEmpty()) merged.add(t);
            }
        }
        this.whitelist = merged;
    }

    public synchronized void start() {
        if (enabled) return; // guard: don't leak a second scheduler
        enabled = true;
        warnedAllThrottlesFailing = false;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ecoqos-scan");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scanAndApply, 0, 5, TimeUnit.SECONDS);
    }
    public synchronized void stop() {
        enabled = false;
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        // restore normal scheduling on everything we touched
        for (Long pid : currentlyThrottledPids) setThrottle(pid, false);
        currentlyThrottledPids.clear();
    }

    private void scanAndApply() {
        if (!enabled) return;
        long foregroundPid = getForegroundProcessId();

        // Find the executable name of the foreground process to unthrottle its entire process group (e.g. all Edge processes)
        String foregroundExe = "";
        if (foregroundPid > 0) {
            Optional<ProcessHandle> fgPh = ProcessHandle.of(foregroundPid);
            if (fgPh.isPresent()) {
                Optional<String> name = fgPh.get().info().command();
                foregroundExe = name.map(n -> n.substring(n.lastIndexOf('\\') + 1).toLowerCase()).orElse("");
            }
        }

        // "Background throttling" must mean BACKGROUND: any process with a visible,
        // non-minimized window (Notepad, Settings, Snipping Tool...) is in active use
        // and is exempt. Restored windows are un-throttled by the next scan.
        Set<Long> visiblePids = VisibleWindows.visibleWindowPids();

        int throttleAttempts = 0;
        int throttleSuccesses = 0;
        for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
            long pid = p.pid();
            Optional<String> name = p.info().command();
            String exeName = name.map(n -> n.substring(n.lastIndexOf('\\') + 1).toLowerCase()).orElse("");
            if (exeName.isEmpty()) {
                // Coverage: some user-session processes expose only commandLine()
                // (with arguments) - strip args the same way EcoService does so they
                // can still be whitelist-matched and throttled
                exeName = EcoService.extractExeName(p.info().commandLine().orElse(""));
            }

            // The foreground app is NEVER throttled - neither itself, nor its
            // exe-name siblings (all Edge processes), nor its process tree
            // (a focused terminal's child shells: powershell/cmd under WindowsTerminal)
            boolean isForegroundApp = (pid == foregroundPid)
                    || (!exeName.isEmpty() && exeName.equals(foregroundExe))
                    || ProcessTree.isInTree(pid, foregroundPid);

            boolean shouldThrottle = !isForegroundApp
                    && pid != ProcessHandle.current().pid()   // never throttle WBS itself
                    && !visiblePids.contains(pid)             // never throttle apps with visible windows
                    && !whitelist.contains(exeName)
                    && !exeName.isEmpty();

            boolean currentlyThrottled = currentlyThrottledPids.contains(pid);
            if (shouldThrottle && !currentlyThrottled) {
                // Re-check: stop() may have raced us mid-scan - never ADD throttles
                // after a stop, or that PID stays throttled with no scheduler running
                if (!enabled) break;
                throttleAttempts++;
                if (setThrottle(pid, true)) {
                    currentlyThrottledPids.add(pid);
                    throttleSuccesses++;
                }
            } else if (!shouldThrottle && currentlyThrottled) {
                setThrottle(pid, false);
                currentlyThrottledPids.remove(pid);
            }
        }
        // Diagnostics: distinguish "nothing to throttle" from "every call blocked"
        if (throttleAttempts >= 10 && throttleSuccesses == 0 && !warnedAllThrottlesFailing) {
            warnedAllThrottlesFailing = true;
            System.err.println("EcoQoS: all " + throttleAttempts + " throttle attempts failed this scan - "
                    + "OpenProcess/SetProcessInformation blocked (EDR, permissions, or unsupported OS). Efficiency Mode is NOT being applied.");
        }
        // prune dead pids
        currentlyThrottledPids.removeIf(pid -> !ProcessHandle.of(pid).isPresent());
        // Lost the race with stop(): clean up anything this scan added after the clear
        if (!enabled) {
            for (Long pid : currentlyThrottledPids) setThrottle(pid, false);
            currentlyThrottledPids.clear();
        }
    }

    private boolean setThrottle(long pid, boolean on) {
        Kernel32Ext kernel32 = Kernel32Ext.INSTANCE;
        int access = PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION;
        // OpenProcess returns a HANDLE
        com.sun.jna.platform.win32.WinNT.HANDLE h = kernel32.OpenProcess(access, false, (int) pid);
        if (h == null) return false; // access denied (system/elevated process)
        try {
            PowerThrottlingState state = on ? PowerThrottlingState.throttleOn() : PowerThrottlingState.throttleOff();
            return kernel32.SetProcessInformation(
                    h, Kernel32Ext.ProcessPowerThrottling, state, state.size());
        } finally {
            kernel32.CloseHandle(h);
        }
    }

    private long getForegroundProcessId() {
        User32Ext user32 = User32Ext.INSTANCE;
        com.sun.jna.platform.win32.WinDef.HWND fg = user32.GetForegroundWindow();
        if (fg == null) return -1;
        com.sun.jna.ptr.IntByReference pidRef = new com.sun.jna.ptr.IntByReference();
        int threadId = user32.GetWindowThreadProcessId(fg, pidRef);
        if (threadId > 0) {
            return pidRef.getValue();
        }
        return -1;
    }

    public int getThrottledCount() { return currentlyThrottledPids.size(); }
    public boolean isEnabled() { return enabled; }
}