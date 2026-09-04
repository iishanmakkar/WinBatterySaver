package com.batterysaver.service;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.util.Map;
import java.util.Set;

/**
 * WMC-like optimizer for battery: clears cached RAM to reduce background CPU/disk,
 * trims working sets, enables Power Saver, lowers brightness. Cross-OEM, no driver.
 * Must be low overhead itself.
 */
public class BatteryOptimizerService {

    public static class Result {
        public final int processesTrimmed;
        public final int errors;
        public final boolean powerSaverEnabled;
        public final boolean brightnessLowered;
        public Result(int trimmed, int errors, boolean ps, boolean bright) {
            this.processesTrimmed = trimmed;
            this.errors = errors;
            this.powerSaverEnabled = ps;
            this.brightnessLowered = bright;
        }
        public String summary() {
            return String.format("Trimmed %d processes, Power Saver %s, Brightness %s, %d skipped",
                    processesTrimmed, powerSaverEnabled ? "ON" : "no change", brightnessLowered ? "lowered" : "unchanged", errors);
        }
    }

    private final PowerSaverModeService saver;
    private final BrightnessService brightness = new BrightnessService();

    /**
     * Shares the app-wide PowerSaverModeService so enable/disable state
     * (previous plan GUID, previous brightness) stays consistent with the
     * tray/hotkey toggle. A private instance would record its own restore
     * state and leave the user stuck on Power Saver when disabled elsewhere.
     */
    public BatteryOptimizerService(PowerSaverModeService saver) {
        this.saver = saver;
    }

    /** Standalone constructor (tests / one-off use). */
    public BatteryOptimizerService() {
        this(new PowerSaverModeService());
    }

    /**
     * Optimize for battery: enable saver, dim, and empty working sets.
     * Runs on background thread, caller must not block FX.
     */
    public Result optimize(boolean doPowerSaver, boolean doBrightness, boolean doRam) {
        boolean psOn = false;
        boolean brightOk = false;
        int trimmed = 0;
        int errs = 0;

        if (doPowerSaver) {
            try {
                if (!saver.isActiveReal()) {
                    saver.enable(40);
                    psOn = true;
                } else {
                    psOn = true;
                }
            } catch (Exception e) {
                System.err.println("Optimize Power Saver failed: " + e.getMessage());
            }
        }

        if (doBrightness) {
            try {
                int cur = brightness.getBrightness();
                if (cur > 45) {
                    brightness.setBrightness(40);
                    brightOk = true;
                }
            } catch (UnsupportedOperationException ignored) {
            } catch (Exception e) {
                System.err.println("Optimize brightness failed: " + e.getMessage());
            }
        }

        if (doRam) {
            // Try to load EmptyWorkingSet via NativeLibrary (works even if JNA interface doesn't expose it)
            Function emptyWS = null;
            NativeLibrary lib = null;
            try {
                lib = NativeLibrary.getInstance("psapi");
                emptyWS = lib.getFunction("EmptyWorkingSet");
            } catch (Exception e1) {
                try {
                    lib = NativeLibrary.getInstance("kernel32");
                    emptyWS = lib.getFunction("EmptyWorkingSet");
                } catch (Exception e2) {
                    System.err.println("EmptyWorkingSet not found, fallback to SetProcessWorkingSetSize");
                    try {
                        lib = NativeLibrary.getInstance("kernel32");
                        emptyWS = lib.getFunction("K32EmptyWorkingSet");
                    } catch (Exception e3) {
                        emptyWS = null;
                    }
                }
            }
            // Fallback to SetProcessWorkingSetSize(-1,-1) if EmptyWorkingSet not found
            Function setWS = null;
            if (emptyWS == null) {
                try {
                    NativeLibrary k32 = NativeLibrary.getInstance("kernel32");
                    setWS = k32.getFunction("SetProcessWorkingSetSize");
                } catch (Exception ignored) {}
            }

            // SAFETY: never page out processes whose memory is actively needed -
            // trimming dwm/csrss/the foreground app or anything with a visible
            // window forces hard-fault storms (disk thrash -> user-visible hang).
            // The EcoQoS default whitelist already covers shell/system/console
            // criticals + apps that misbehave when squeezed.
            Set<String> safeList = EcoQosThrottleService.DEFAULT_WHITELIST;
            Set<Long> visible = com.batterysaver.util.VisibleWindows.visibleWindowPids();
            long foregroundPid = foregroundProcessId();
            int PROCESS_SET_QUOTA = 0x0100;
            int PROCESS_QUERY_INFORMATION = 0x0400;
            for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
                long pid = ph.pid();
                if (pid == 0 || pid == 4) continue;
                if (pid == ProcessHandle.current().pid()) continue;
                // Visible window owner (non-minimized) = in active use - skip
                if (visible.contains(pid)) continue;
                // Foreground process and its whole tree - skip
                if (foregroundPid > 0 && com.batterysaver.util.ProcessTree.isInTree(pid, foregroundPid)) continue;
                String exeName = ph.info().command().orElse("");
                int slash = Math.max(exeName.lastIndexOf('\\'), exeName.lastIndexOf('/'));
                if (slash >= 0) exeName = exeName.substring(slash + 1).toLowerCase();
                if (!exeName.isEmpty() && safeList.contains(exeName)) continue;
                HANDLE h = null;
                try {
                    h = Kernel32.INSTANCE.OpenProcess(PROCESS_SET_QUOTA | PROCESS_QUERY_INFORMATION, false, (int) pid);
                    if (h == null || h.equals(WinBase.INVALID_HANDLE_VALUE)) {
                        // Skipped (permission denied) - not an error worth alarming the user
                        continue;
                    }
                    boolean ok = false;
                    if (emptyWS != null) {
                        // EmptyWorkingSet(HANDLE) -> BOOL
                        int res = emptyWS.invokeInt(new Object[]{h});
                        ok = res != 0;
                    } else if (setWS != null) {
                        // SetProcessWorkingSetSize(HANDLE, -1, -1) -> BOOL
                        int res = setWS.invokeInt(new Object[]{h, -1, -1});
                        ok = res != 0;
                    } else {
                        // No API, count as error
                        errs++;
                        continue;
                    }
                    if (ok) trimmed++;
                    else errs++;
                } catch (Exception e) {
                    errs++;
                } finally {
                    if (h != null && !h.equals(WinBase.INVALID_HANDLE_VALUE)) {
                        Kernel32.INSTANCE.CloseHandle(h);
                    }
                }
                // Yield between batches so the trim itself never saturates a core
                if (trimmed % 40 == 0) {
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                }
            }
        }

        return new Result(trimmed, errs, psOn, brightOk);
    }

    /** PID of the foreground window's owner, 0 when unavailable. */
    private static long foregroundProcessId() {
        try {
            com.sun.jna.platform.win32.WinDef.HWND fg =
                    com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
            if (fg == null) return 0;
            com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(fg, pid);
            return pid.getValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    public String topDrainerSuggestion() {
        try {
            Map<String, Double> top = ProcessUsageService.getTopCpuProcesses(1);
            if (top.isEmpty()) return "No significant CPU usage - system idle.";
            Map.Entry<String, Double> e = top.entrySet().iterator().next();
            String name = e.getKey();
            double cpu = e.getValue();
            if (cpu < 5.0) return String.format("Top: %s (%.1f%% CPU) - low impact, no need to kill.", name, cpu);
            String lower = name.toLowerCase();
            boolean isSystem = lower.equals("system") || lower.equals("registry") || lower.equals("wininit.exe") || lower.equals("csrss.exe") || lower.equals("services.exe") || lower.equals("svchost.exe");
            if (isSystem) return String.format("Top: %s (%.1f%%) system - do not kill. Check next.", name, cpu);
            return String.format("Top drainer: %s (%.1f%% CPU) - closing it would cut this draw to ~zero.", name, cpu);
        } catch (Exception ex) {
            return "Unable to determine top drainer: " + ex.getMessage();
        }
    }
}
