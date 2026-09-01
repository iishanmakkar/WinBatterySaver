package com.batterysaver.viewmodel;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.interop.PowerThrottlingState;
import com.batterysaver.model.Battery;
import com.batterysaver.service.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared ViewModel for Compact + Expanded views.
 * Holds observable state, delegates to existing services (no logic rewrite).
 */
public class MainViewModel {

    // Battery
    private final IntegerProperty batteryPercent = new SimpleIntegerProperty(-1);
    private final BooleanProperty acOnline = new SimpleBooleanProperty(false);
    private final BooleanProperty charging = new SimpleBooleanProperty(false);
    private final StringProperty statusLine = new SimpleStringProperty("Checking...");
    private final StringProperty drainLabel = new SimpleStringProperty("");
    private final StringProperty timeRemaining = new SimpleStringProperty("");

    // Power saver
    private final BooleanProperty powerSaverOn = new SimpleBooleanProperty(false);
    private final BooleanProperty ecoQosEnabled = new SimpleBooleanProperty(true);
    private final IntegerProperty throttledCount = new SimpleIntegerProperty(0);

    // Health
    private final StringProperty healthText = new SimpleStringProperty("Health: checking...");
    private final StringProperty healthDetail = new SimpleStringProperty("");
    private final BooleanProperty healthLoading = new SimpleBooleanProperty(false);
    private final StringProperty standbyNote = new SimpleStringProperty("");
    private final IntegerProperty healthPercent = new SimpleIntegerProperty(-1);
    private final StringProperty healthShort = new SimpleStringProperty("Checking...");

    // Processes
    private final ObservableList<ProcessRow> processRows = FXCollections.observableArrayList();
    public record ProcessRow(String name, double cpu) {}

    // Settings passthrough (backed by SettingsService.Config)
    private SettingsService.Config config;

    // Services
    private final BatteryHistoryService historyService;
    private final BatteryHealthService healthService;
    private final PowerSaverModeService powerSaverService;
    private final ModernStandbyService modernStandbyService;
    private final PowerPlanService powerPlanService;
    private final EcoService ecoService;
    private final EcoQosThrottleService ecoQosThrottleService;

    private ScheduledExecutorService poller;
    private boolean wasAcOnline = true;
    private long lastHealthRefreshMs = 0;
    private int lastPctForDrop = -1;
    private long lastDropCheckMs = 0;
    private final StringProperty ecoStatus = new SimpleStringProperty("Eco: idle");

    public MainViewModel(SettingsService.Config cfg,
                         BatteryHistoryService historyService,
                         BatteryHealthService healthService,
                         PowerSaverModeService powerSaverService,
                         ModernStandbyService standbyService,
                         EcoService ecoService,
                         EcoQosThrottleService ecoQosThrottleService) {
        this.config = cfg;
        this.historyService = historyService;
        this.healthService = healthService;
        this.powerSaverService = powerSaverService;
        this.modernStandbyService = standbyService;
        this.powerPlanService = powerSaverService.getPowerPlanService();
        this.ecoService = ecoService != null ? ecoService : new EcoService();
        this.ecoQosThrottleService = ecoQosThrottleService != null ? ecoQosThrottleService : new EcoQosThrottleService();
        // Sync to real OS state on start, not cached flag
        try {
            String cur = powerPlanService.getActivePlanGuid();
            boolean isSaver = powerPlanService.isPowerSaverGuid(cur);
            this.powerSaverOn.set(isSaver);
        } catch (Exception e) {
            this.powerSaverOn.set(powerSaverService.isActive());
        }

        // Standby note one-shot
        try {
            String note = modernStandbyService.getNote();
            if (note != null) standbyNote.set(note);
        } catch (Exception ignored) {}

        startPolling();
    }

    private void startPolling() {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vm-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(() -> {
            try {
                Battery b = BatteryStatusService.getLastStatus();
                int pct = b.getPercent();
                boolean ac = b.isOnAC();
                boolean ch = b.isChargingFlag() || (ac && pct >= 0 && pct < 100);

                int remaining = b.getRemainingSeconds();
                String timeStr = "";
                if (remaining > 0 && remaining < Integer.MAX_VALUE) {
                    int h = remaining / 3600;
                    int m = (remaining % 3600) / 60;
                    if (h > 0) timeStr = h + "h " + m + "m";
                    else timeStr = m + "m";
                }

                // History trend for status line - must capture status string and set on FX thread
                String trend = historyService.getTrendLabel();
                String drain = "";
                String fStatus;
                int drained = historyService.drainedInLastHour();
                if (ac && ch && pct >= 0) {
                    if (pct >= 98) fStatus = "Fully charged";
                    else if (!timeStr.isEmpty()) fStatus = "Charging - full in " + timeStr;
                    else fStatus = "Charging";
                    drain = pct >= 0 ? "Charging" : "";
                } else {
                    if (drained > 0) {
                        fStatus = "Draining " + drained + "%/hr";
                        drain = fStatus;
                    } else if (trend.contains("stable")) {
                        fStatus = "Battery stable";
                        drain = "Stable";
                    } else {
                        fStatus = ac ? "AC online" : "On battery";
                        drain = fStatus;
                    }
                    if (!timeStr.isEmpty() && !ac) {
                        drain += " - " + timeStr + " left";
                    }
                }

                // Auto-saver when unplugged (not plugged in) - real battery saver
                boolean shouldAutoEnable = false;
                if (wasAcOnline && !ac) {
                    // Just unplugged - enable saver if not already
                    shouldAutoEnable = true;
                }
                if (!ac && wasAcOnline) {
                    // Just plugged in - disable saver if enabled
                    try {
                        powerSaverService.disable();
                        System.out.println("Auto-saver: disabled Power Saver on AC reconnect");
                    } catch (Exception ignored) {}
                }
                wasAcOnline = ac;
                if (shouldAutoEnable) {
                    try {
                        String cur2 = powerPlanService.getActivePlanGuid();
                        boolean isAlreadySaver = cur2 != null && cur2.equalsIgnoreCase(PowerPlanService.POWER_SAVER);
                        if (!isAlreadySaver) {
                            powerSaverService.enable(config.dimPercent);
                            System.out.println("Auto-saver: enabled Power Saver on unplug");
                        }
                    } catch (Exception e) {
                        System.err.println("Auto-saver failed: " + e.getMessage());
                    }
                }

                // Sudden drop detection (live, every poll)
                String suddenMsg = null;
                if (!ac && pct >= 0 && lastPctForDrop >= 0) {
                    int drop = lastPctForDrop - pct;
                    long nowMs = System.currentTimeMillis();
                    if (drop >= 3 && (nowMs - lastDropCheckMs) < 5*60*1000) {
                        // Drop >=3% within 5 min - sudden
                        Map<String, Double> top = ProcessUsageService.getTopCpuProcesses(1);
                        String topName = top.isEmpty() ? "unknown" : top.keySet().iterator().next();
                        suddenMsg = String.format("Sudden drop %d%% detected! Top drainer: %s (%.1f%% CPU)", drop, topName, top.isEmpty()?0:top.values().iterator().next());
                    }
                    if (nowMs - lastDropCheckMs > 5*60*1000) {
                        lastPctForDrop = pct;
                        lastDropCheckMs = nowMs;
                    }
                } else if (ac) {
                    lastPctForDrop = pct;
                    lastDropCheckMs = System.currentTimeMillis();
                }
                final String fSudden = suddenMsg;

                // Periodic health refresh when on battery (every 5 min) for live health
                long nowMs2 = System.currentTimeMillis();
                if (!ac && (nowMs2 - lastHealthRefreshMs) > 5*60*1000) {
                    lastHealthRefreshMs = nowMs2;
                    // Trigger refresh on background (not FX)
                    new Thread(() -> {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        refreshHealth();
                    }, "health-auto").start();
                }

                final String fDrain = drain;
                final String fTime = timeStr;
                final String fStatusFinal = fStatus;
                final int fPct = pct;
                final boolean fAc = ac;
                final boolean fCh = ch;
                // Real-time power plan sync (non-blocking, timeout handled in service)
                boolean isSaver = false;
                try {
                    String cur = powerPlanService.getActivePlanGuid();
                    isSaver = powerPlanService.isPowerSaverGuid(cur);
                } catch (Exception ignored) {
                    isSaver = powerSaverService.isActiveReal();
                }
                final boolean fSaver = isSaver;
                // Eco throttling (EnergyStarX-like, any laptop) - update based on battery + saver
                try {
                    if (ecoService != null) {
                        boolean isOnBattery = !fAc;
                        ecoService.updateThrottleStatus(isOnBattery, fSaver);
                    }
                } catch (Exception e) {
                    System.err.println("Eco update failed: " + e.getMessage());
                }
                final String fEco = ecoService != null ? "Eco: " + ecoService.getStatus() + (ecoService.isPaused() ? " (paused)" : "") + (ecoService.isThrottleWhenPluggedIn() ? " [plugged throttles]" : "") : "Eco: idle";
// EcoQoS Background Throttling (Efficiency Mode) - update based on battery + power saver mode
                boolean ecoQosRunning = ecoQosThrottleService.isEnabled();
                int ecoQosCount = ecoQosThrottleService.getThrottledCount();
                String ecoQosStatus = (ecoQosRunning ? "EcoQos: " + ecoQosCount + " throttled" : "EcoQos: off");
                // Start/stop EcoQos based on battery + power saver preference: throttle on battery OR when Power Saver is on
                try {
                    // Only automatically start if the user hasn't explicitly disabled it
                    if (this.ecoQosEnabled.get() && (!fAc || fSaver)) {
                        if (!ecoQosThrottleService.isEnabled()) {
                            ecoQosThrottleService.start();
                        }
                    } else {
                        if (ecoQosThrottleService.isEnabled()) {
                            ecoQosThrottleService.stop();
                        }
                    }
                } catch (Exception ignored) {}
                final String fEcoQos = ecoQosStatus;
                int tc = ecoQosThrottleService != null ? ecoQosThrottleService.getThrottledCount() : 0;
                Platform.runLater(() -> {
                    batteryPercent.set(fPct);
                    acOnline.set(fAc);
                    charging.set(fCh);
                    statusLine.set(fStatusFinal);
                    drainLabel.set(fDrain);
                    timeRemaining.set(fTime);
                    powerSaverOn.set(fSaver);
                    throttledCount.set(tc);
                    ecoStatus.set(fEco + " | " + fEcoQos);
                    if (fSudden != null) {
                        System.out.println("Sudden drop: " + fSudden);
                        // Also surface as drainLabel for 30s
                        drainLabel.set(fSudden);
                    }
                });

                // Processes every 10s
                if (System.currentTimeMillis() % 10000 < 5500) {
                    Map<String, Double> top = ProcessUsageService.getTopCpuProcesses(5);
                    Platform.runLater(() -> {
                        processRows.clear();
                        if (top.isEmpty()) {
                            // keep empty, view shows placeholder
                        } else {
                            top.forEach((k, v) -> processRows.add(new ProcessRow(k, v)));
                        }
                    });
                }

            } catch (Exception e) {
                System.err.println("VM poll error: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void togglePowerSaver() {
        try {
            String cur = powerPlanService.getActivePlanGuid();
            boolean isSaver = powerPlanService.isPowerSaverGuid(cur);
            if (!isSaver) {
                powerSaverService.enable(config.dimPercent);
            } else {
                powerSaverService.disable();
            }
            // Re-query after toggle to confirm
            String after = powerPlanService.getActivePlanGuid();
            boolean isNowSaver = powerPlanService.isPowerSaverGuid(after);
            Platform.runLater(() -> powerSaverOn.set(isNowSaver));
        } catch (SecurityException se) {
            System.err.println("PowerSaver toggle blocked by security: " + se.getMessage());
            Platform.runLater(() -> powerSaverOn.set(false));
        } catch (Exception e) {
            System.err.println("PowerSaver toggle failed: " + e.getMessage());
            try {
                String cur2 = powerPlanService.getActivePlanGuid();
                boolean isSaver2 = powerPlanService.isPowerSaverGuid(cur2);
                Platform.runLater(() -> powerSaverOn.set(isSaver2));
            } catch (Exception ignored) {}
        }
    }

    public void refreshHealth() {
        Platform.runLater(() -> {
            if (healthLoading.get()) return;
            healthLoading.set(true);
            healthText.set("Health: checking...");
            new Thread(() -> {
                try {
                    BatteryHealthService.Health h = healthService.getHealth();
                    String cycles = h.cycleCount() < 0 ? "not reported" : String.valueOf(h.cycleCount());
                String hp = h.healthPercent() < 0 ? "-" : String.format("%.0f%%", h.healthPercent());
                String txt = String.format("Health: %s | Design %d mWh / Full %d mWh | Cycles %s",
                        hp, h.designCapacityMwh(), h.fullChargeCapacityMwh(), cycles);
                String detail = String.format("Design capacity: %d mWh\nFull charge capacity: %d mWh\nHealth: %s\nCycle count: %s",
                        h.designCapacityMwh(), h.fullChargeCapacityMwh(), hp, cycles);
                final int hpVal = h.healthPercent() < 0 ? -1 : (int)Math.round(h.healthPercent());
                final String shortTxt = h.healthPercent() < 0 ? "Health: --" : "Health: " + hpVal + "% | Cycles " + cycles;
                Platform.runLater(() -> {
                    healthText.set(txt);
                    healthDetail.set(detail);
                    healthPercent.set(hpVal);
                    healthShort.set(shortTxt);
                    healthLoading.set(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    if (msg.toLowerCase().contains("elevated") || msg.toLowerCase().contains("access")) {
                        healthText.set("Health: requires elevated permissions on this device");
                        healthDetail.set("Run as administrator to read battery report, or check powercfg /batteryreport manually.");
                        healthPercent.set(-1);
                        healthShort.set("Health: --");
                    } else {
                        healthText.set("Health: unavailable (" + msg + ")");
                        healthDetail.set(msg);
                        healthPercent.set(-1);
                        healthShort.set("Health: --");
                    }
                    healthLoading.set(false);
                });
            }
        }, "health-refresh-vm").start();
        });
    }

    public void updateConfig(SettingsService.Config newCfg) {
        this.config = newCfg;
    }

    public void stop() {
        if (poller != null) poller.shutdownNow();
    }

    // Properties
    public IntegerProperty batteryPercentProperty() { return batteryPercent; }
    public BooleanProperty acOnlineProperty() { return acOnline; }
    public BooleanProperty chargingProperty() { return charging; }
    public StringProperty statusLineProperty() { return statusLine; }
    public StringProperty drainLabelProperty() { return drainLabel; }
    public BooleanProperty powerSaverOnProperty() { return powerSaverOn; }
    public StringProperty healthTextProperty() { return healthText; }
    public StringProperty healthDetailProperty() { return healthDetail; }
    public BooleanProperty healthLoadingProperty() { return healthLoading; }
    public StringProperty standbyNoteProperty() { return standbyNote; }
    public IntegerProperty healthPercentProperty() { return healthPercent; }
    public StringProperty healthShortProperty() { return healthShort; }
    public StringProperty timeRemainingProperty() { return timeRemaining; }
    public BooleanProperty ecoQosEnabledProperty() { return ecoQosEnabled; }
    public IntegerProperty throttledCountProperty() { return throttledCount; }
    public void toggleEcoQos(boolean enable) {
        ecoQosEnabled.set(enable);
        if (ecoQosThrottleService != null) {
            if (enable) ecoQosThrottleService.start();
            else ecoQosThrottleService.stop();
        }
    }
    public ObservableList<ProcessRow> getProcessRows() { return processRows; }
    public SettingsService.Config getConfig() { return config; }

    public String batteryColorClass(int pct) {
        if (pct < 0) return "battery-ring-unknown";
        if (pct < 20) return "battery-ring-low";
        if (pct < 50) return "battery-ring-medium";
        return "battery-ring-high";
    }

    public List<BatteryHistoryService.Entry> getHistoryEntriesForRange(String range) {
        List<BatteryHistoryService.Entry> all = historyService.readAll();
        java.time.Instant cutoff;
        java.time.Instant now = java.time.Instant.now();
        switch (range) {
            case "1h": cutoff = now.minus(1, java.time.temporal.ChronoUnit.HOURS); break;
            case "6h": cutoff = now.minus(6, java.time.temporal.ChronoUnit.HOURS); break;
            case "24h": cutoff = now.minus(24, java.time.temporal.ChronoUnit.HOURS); break;
            case "7d": cutoff = now.minus(7, java.time.temporal.ChronoUnit.DAYS); break;
            default: cutoff = now.minus(2, java.time.temporal.ChronoUnit.HOURS); break;
        }
        return all.stream().filter(e -> e.timestamp().isAfter(cutoff)).toList();
    }
}
