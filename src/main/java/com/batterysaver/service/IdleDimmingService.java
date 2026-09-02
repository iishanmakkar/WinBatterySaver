package com.batterysaver.service;

import com.batterysaver.interop.Kernel32Ext;
import com.batterysaver.interop.LastInputInfo;
import com.batterysaver.interop.User32Ext;
import javafx.application.Platform;

import java.util.concurrent.*;

/**
 * Dims screen after idle threshold, restores on activity.
 * Fixed: threshold is minutes*60_000 (was *1000), uses GetTickCount for idle calc,
 * and tracks dimmed state to avoid flicker (only dim/restore on transition).
 */
public class IdleDimmingService {
    private final BrightnessService brightnessService;
    // volatile: updatable at runtime via updateSettings() (Settings save)
    private volatile long idleThresholdMs;
    private volatile int dimToPercent;
    private final int restoreToPercent;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idle-dim-poller");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean active = false;
    private volatile boolean isDimmed = false;
    private volatile int savedBrightness = -1;
    // Set once WMI brightness proves unsupported - stops the 2-spawn PowerShell
    // attempt on every idle dim/restore cycle
    private volatile boolean brightnessUnsupported = false;

    public IdleDimmingService(BrightnessService brightnessService,
                              int idleMinutes,
                              int dimToPercent,
                              int restoreToPercent) {
        this.brightnessService = brightnessService;
        this.idleThresholdMs = (long) idleMinutes * 60_000L;
        this.dimToPercent = dimToPercent;
        this.restoreToPercent = restoreToPercent;
    }

    /** Live-update the idle threshold (minutes) and dim level from Settings. */
    public void updateSettings(int idleMinutes, int dimToPercent) {
        this.idleThresholdMs = (long) idleMinutes * 60_000L;
        this.dimToPercent = dimToPercent;
    }

    public void start() {
        if (active) return;
        active = true;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                LastInputInfo lii = new LastInputInfo();
                lii.cbSize = lii.size();
                lii.write(); // sync to native
                boolean ok = User32Ext.INSTANCE.GetLastInputInfo(lii);
                if (!ok) return;
                lii.read();
                long idleMs = idleMillis(lii.dwTime, currentTick());
                if (idleMs < 0) idleMs = 0;

                if (idleMs >= idleThresholdMs) {
                    if (!isDimmed) {
                        isDimmed = true;
                        if (!brightnessUnsupported) {
                            // Save current brightness before dimming (2 PowerShell spawns
                            // per transition - skipped entirely once we know it's unsupported)
                            try {
                                savedBrightness = brightnessService.getBrightness();
                            } catch (UnsupportedOperationException e) {
                                brightnessUnsupported = true;
                                savedBrightness = restoreToPercent;
                            } catch (Exception ignored) {
                                savedBrightness = restoreToPercent;
                            }
                            try { brightnessService.setBrightness(dimToPercent); }
                            catch (UnsupportedOperationException e) { brightnessUnsupported = true; }
                            catch (Exception ignored) {}
                        }
                    }
                } else {
                    if (isDimmed) {
                        isDimmed = false;
                        if (!brightnessUnsupported) {
                            int toRestore = savedBrightness >= 0 ? savedBrightness : restoreToPercent;
                            try { brightnessService.setBrightness(toRestore); }
                            catch (UnsupportedOperationException e) { brightnessUnsupported = true; }
                            catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                // Don't spam stacktrace; log once
                System.err.println("IdleDimmingService poll error: " + e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    /** Current tick in the 32-bit wrap domain of LASTINPUTINFO.dwTime. */
    private static int currentTick() {
        long now64;
        try {
            now64 = Kernel32Ext.INSTANCE.GetTickCount64();
        } catch (UnsatisfiedLinkError | Exception e) {
            now64 = Integer.toUnsignedLong(Kernel32Ext.INSTANCE.GetTickCount());
        }
        return (int) now64; // low 32 bits - same wrap domain as dwTime
    }

    /**
     * Idle milliseconds between two 32-bit tick counters. LASTINPUTINFO.dwTime wraps
     * every ~49.7 days, so the delta MUST be computed mod 2^32 (mixing in the
     * unwrapped 64-bit counter breaks permanently after wrap and dims forever).
     * Package-private for testing.
     */
    static long idleMillis(int lastInputTick, int nowTick) {
        return Integer.toUnsignedLong(nowTick - lastInputTick);
    }

    public void stop() {
        active = false;
        scheduler.shutdownNow();
        // Restore if dimmed on stop
        if (isDimmed) {
            isDimmed = false;
            try {
                int toRestore = savedBrightness >= 0 ? savedBrightness : restoreToPercent;
                brightnessService.setBrightness(toRestore);
            } catch (UnsupportedOperationException ignored) {}
        }
    }

    public boolean isDimmed() { return isDimmed; }
    public boolean isActive() { return active; }
}
