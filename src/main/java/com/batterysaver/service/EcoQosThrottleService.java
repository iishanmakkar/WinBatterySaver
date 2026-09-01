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
 * Scan interval of 2s matches EnergyStarX approach — frequent enough to un-throttle
 * the instant a background app gets focus (no perceptible lag), infrequent enough
 * to not itself waste CPU/battery.
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

import java.util.*;
import java.util.concurrent.*;

public class EcoQosThrottleService {
    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private final Set<String> whitelist;
    private final Set<Long> currentlyThrottledPids = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean enabled = false;

    // Default whitelist based on EnergyStarX documented known-conflict list
    public static final Set<String> DEFAULT_WHITELIST = Set.of(
        "logioptionsplus.exe", "logioptionsplus_agent.exe",   // mouse/peripheral software — throttling causes cursor lag
        "steam.exe", "steamwebhelper.exe",                      // gaming overlay/download management
        "discord.exe",                                          // voice chat — throttling causes audio stutter
        "startallback.exe", "explorerpatcher.exe",              // taskbar enhancers — known to crash on tray hover when throttled
        "obs64.exe", "obs32.exe",                                // screen recording — must not be throttled even in background
        "wbs.exe",                                                // WBS itself, redundant safety alongside the PID check
        "taskmgr.exe"                                             // Task Manager (prevent observer-effect lag)
    );

    public EcoQosThrottleService() {
        this.whitelist = new HashSet<>(DEFAULT_WHITELIST);
    }

    public EcoQosThrottleService(Set<String> whitelist) {
        this.whitelist = new HashSet<>(whitelist);
    }

    public void start() {
        enabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ecoqos-scan");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scanAndApply, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        enabled = false;
        if (scheduler != null) scheduler.shutdownNow();
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

        for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
            long pid = p.pid();
            Optional<String> name = p.info().command();
            String exeName = name.map(n -> n.substring(n.lastIndexOf('\\') + 1).toLowerCase()).orElse("");

            boolean isForegroundApp = (pid == foregroundPid) || (!exeName.isEmpty() && exeName.equals(foregroundExe));

            boolean shouldThrottle = !isForegroundApp
                    && pid != ProcessHandle.current().pid()   // never throttle WBS itself
                    && !whitelist.contains(exeName)
                    && !exeName.isEmpty();

            boolean currentlyThrottled = currentlyThrottledPids.contains(pid);
            if (shouldThrottle && !currentlyThrottled) {
                if (setThrottle(pid, true)) currentlyThrottledPids.add(pid);
            } else if (!shouldThrottle && currentlyThrottled) {
                setThrottle(pid, false);
                currentlyThrottledPids.remove(pid);
            }
        }
        // prune dead pids
        currentlyThrottledPids.removeIf(pid -> !ProcessHandle.of(pid).isPresent());
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