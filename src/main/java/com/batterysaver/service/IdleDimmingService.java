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
    private final long idleThresholdMs;
    private final int dimToPercent;
    private final int restoreToPercent;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idle-dim-poller");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean active = false;
    private volatile boolean isDimmed = false;
    private volatile int savedBrightness = -1;

    public IdleDimmingService(BrightnessService brightnessService,
                              int idleMinutes,
                              int dimToPercent,
                              int restoreToPercent) {
        this.brightnessService = brightnessService;
        this.idleThresholdMs = (long) idleMinutes * 60_000L;
        this.dimToPercent = dimToPercent;
        this.restoreToPercent = restoreToPercent;
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
                long lastInputTick = Integer.toUnsignedLong(lii.dwTime);
                long nowTick;
                try {
                    nowTick = Long.divideUnsigned(Kernel32Ext.INSTANCE.GetTickCount64(), 1);
                } catch (UnsatisfiedLinkError | Exception e) {
                    // Fallback to GetTickCount (32-bit, wraps every 49 days - still monotonic for idle calc)
                    nowTick = Integer.toUnsignedLong(Kernel32Ext.INSTANCE.GetTickCount());
                }
                // Handle 32-bit wrap: unsigned subtraction
                long idleMs = nowTick - lastInputTick;
                // If idleMs negative due to type issues, clamp
                if (idleMs < 0) idleMs = 0;
                // Also handle case where dwTime wraps - if now < last, add 2^32
                if (nowTick < lastInputTick) {
                    idleMs = (0xFFFFFFFFL - lastInputTick) + nowTick + 1;
                }

                if (idleMs >= idleThresholdMs) {
                    if (!isDimmed) {
                        isDimmed = true;
                        // Save current brightness before dimming, if possible (on background thread, not FX)
                        try {
                            savedBrightness = brightnessService.getBrightness();
                        } catch (UnsupportedOperationException ignored) {
                            savedBrightness = restoreToPercent;
                        } catch (Exception ignored) {
                            savedBrightness = restoreToPercent;
                        }
                        try { brightnessService.setBrightness(dimToPercent); }
                        catch (UnsupportedOperationException ignored) {}
                        catch (Exception ignored) {}
                    }
                } else {
                    if (isDimmed) {
                        isDimmed = false;
                        int toRestore = savedBrightness >= 0 ? savedBrightness : restoreToPercent;
                        try { brightnessService.setBrightness(toRestore); }
                        catch (UnsupportedOperationException ignored) {}
                        catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                // Don't spam stacktrace; log once
                System.err.println("IdleDimmingService poll error: " + e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);
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
