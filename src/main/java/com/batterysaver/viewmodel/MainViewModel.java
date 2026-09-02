package com.batterysaver.viewmodel;

import com.batterysaver.constants.AppConstants;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    /** User intent / master switch for EcoQoS (Settings checkbox + button state source). */
    private final BooleanProperty ecoQosEnabled = new SimpleBooleanProperty(true);
    /** ACTUAL service state (is the throttling engine running right now). The UI
     *  binds to this, not to the intent, so it can never claim "ON" while stopped. */
    private final BooleanProperty ecoQosRunning = new SimpleBooleanProperty(false);
    private final IntegerProperty throttledCount = new SimpleIntegerProperty(0);
    /** Last action error (user-visible); blank when the last action succeeded. */
    private final StringProperty actionError = new SimpleStringProperty("");

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

    // Settings passthrough (backed by SettingsService.Config).
    // volatile: updateConfig() swaps the object from the FX thread while the
    // vm-poller thread reads it.
    private volatile SettingsService.Config config;

    // Services
    private final BatteryHistoryService historyService;
    private final BatteryHealthService healthService;
    private final PowerSaverModeService powerSaverService;
    private final ModernStandbyService modernStandbyService;
    private final PowerPlanService powerPlanService;
    private final EcoService ecoService;
    private final EcoQosThrottleService ecoQosThrottleService;

    private ScheduledExecutorService poller;
    // AC transition tracking - baseline is unknown until the first poll, so launching
    // on battery does NOT fire the "just unplugged" auto-saver
    private boolean wasAcOnlineKnown = false;
    private boolean wasAcOnline = true;
    private int pollTick = 0;
    private long lastHealthRefreshMs = 0;
    private int lastPctForDrop = -1;
    private long lastDropCheckMs = 0;
    // True when the poller auto-started EcoQoS (vs the user starting it manually).
    // The poller only auto-STOPS what it auto-started - a manual "ON" is authoritative.
    private volatile boolean ecoQosAutoStarted = false;
    // Set by Main; receives sudden-drop messages for user-visible notification.
    private volatile Consumer<String> suddenDropListener;
    // Serializes togglePowerSaver executions (button double-clicks, hotkey + poller).
    private final AtomicBoolean toggleInFlight = new AtomicBoolean(false);

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
        // Runtime EcoQoS master switch starts from the SAVED setting - a user who
        // disabled EcoQoS must not get throttling back after a restart
        this.ecoQosEnabled.set(cfg.ecoQosEnabled);
        // Sync to real OS state on start, not cached flag
        try {
            String cur = powerPlanService.getActivePlanGuid();
            boolean isSaver = powerPlanService.isPowerSaverGuid(cur);
            this.powerSaverOn.set(isSaver);
        } catch (Exception e) {
            this.powerSaverOn.set(powerSaverService.isActive());
        }

        // Standby note one-shot - powercfg spawn can take seconds, keep it off the FX
        // thread so the first window paints immediately
        Thread standbyInit = new Thread(() -> {
            try {
                String note = modernStandbyService.getNote();
                if (note != null) Platform.runLater(() -> standbyNote.set(note));
            } catch (Exception ignored) {}
        }, "standby-init");
        standbyInit.setDaemon(true);
        standbyInit.start();

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
                pollTick++;
                Battery b = BatteryStatusService.getLastStatus();
                int pct = b.getPercent();
                boolean ac = b.isOnAC();
                boolean ch = b.isChargingFlag() || (ac && pct >= 0 && pct < 100);

                // BatteryLifeTime is the DISCHARGE estimate; it is invalid while charging,
                // so never present it as "time to full"
                int remaining = b.getRemainingSeconds();
                String timeStr = "";
                if (!ac && remaining > 0 && remaining < Integer.MAX_VALUE) {
                    int h = remaining / 3600;
                    int m = (remaining % 3600) / 60;
                    if (h > 0) timeStr = h + "h " + m + "m";
                    else timeStr = m + "m";
                }

                // History trend - ONE read per tick (BatteryHistoryService caches parsed entries)
                int drained = historyService.drainedInLastHour();
                String trend = drained > 0
                        ? "Battery drained " + drained + "% in the last hour"
                        : "Battery stable in last hour";
                String drain;
                String fStatus;
                if (ac && ch && pct >= 0) {
                    if (pct >= 98) fStatus = "Fully charged";
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

                // Auto-saver AC transitions.
                // Note: justUnplugged = (was AC, now battery); justPluggedIn = (was battery, now AC).
                boolean justUnplugged = wasAcOnlineKnown && wasAcOnline && !ac;
                boolean justPluggedIn = wasAcOnlineKnown && !wasAcOnline && ac;
                wasAcOnlineKnown = true;
                wasAcOnline = ac;
                if (justUnplugged) {
                    try {
                        String cur2 = powerPlanService.getActivePlanGuid();
                        boolean isAlreadySaver = powerPlanService.isPowerSaverGuid(cur2);
                        if (!isAlreadySaver) {
                            powerSaverService.enable(config.dimPercent);
                            System.out.println("Auto-saver: enabled Power Saver on unplug");
                        }
                    } catch (Exception e) {
                        System.err.println("Auto-saver failed: " + e.getMessage());
                    }
                } else if (justPluggedIn) {
                    try {
                        if (powerSaverService.isActive()) {
                            powerSaverService.disable();
                            System.out.println("Auto-saver: disabled Power Saver on AC reconnect");
                        }
                    } catch (Exception ignored) {}
                }

                // Sudden drop detection: maintain a rolling baseline while on battery;
                // alert when the battery fell >= 3% within a 5-minute window. Baseline is
                // armed even at app start on battery (first poll seeds it).
                String suddenMsg = null;
                long nowMs = System.currentTimeMillis();
                if (ac || pct < 0) {
                    // While charging/unknown, keep the baseline fresh
                    lastPctForDrop = pct;
                    lastDropCheckMs = nowMs;
                } else if (lastPctForDrop < 0) {
                    lastPctForDrop = pct;
                    lastDropCheckMs = nowMs;
                } else {
                    int drop = lastPctForDrop - pct;
                    long windowMs = nowMs - lastDropCheckMs;
                    if (drop >= 3 && windowMs <= 5 * 60 * 1000L) {
                        Map<String, Double> top = ProcessUsageService.getTopCpuProcesses(1);
                        String topName = top.isEmpty() ? "unknown" : top.keySet().iterator().next();
                        suddenMsg = String.format("Sudden drop %d%% detected! Top drainer: %s (%.1f%% CPU)", drop, topName, top.isEmpty() ? 0 : top.values().iterator().next());
                        lastPctForDrop = pct;
                        lastDropCheckMs = nowMs;
                    } else if (drop < 0 || windowMs > 5 * 60 * 1000L) {
                        // Level rose again or window expired without a qualifying drop - re-arm
                        lastPctForDrop = pct;
                        lastDropCheckMs = nowMs;
                    }
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
                // Only trust "on battery" once the battery state is CONFIRMED (pct >= 0).
                // The pre-first-poll default (ac=false, pct=-1) must not count as
                // "on battery" - that briefly auto-throttled desktops/AC machines at startup.
                boolean onBatteryConfirmed = !fAc && fPct >= 0;
                // Eco throttling (EnergyStarX-like, any laptop) - update based on battery + saver
                try {
                    if (ecoService != null) {
                        ecoService.updateThrottleStatus(onBatteryConfirmed, fSaver);
                    }
                } catch (Exception e) {
                    System.err.println("Eco update failed: " + e.getMessage());
                }
                // EcoQoS Background Throttling (Efficiency Mode) - auto-managed:
                // throttle on battery or when Power Saver is on, but only auto-stop
                // what we auto-started (a manual "ON" from the user is authoritative
                // and survives AC transitions until they turn it off).
                try {
                    boolean wantAuto = this.ecoQosEnabled.get() && (onBatteryConfirmed || fSaver);
                    if (wantAuto) {
                        if (!ecoQosThrottleService.isEnabled()) {
                            ecoQosThrottleService.start();
                            ecoQosAutoStarted = true;
                        }
                    } else {
                        if (ecoQosAutoStarted && ecoQosThrottleService.isEnabled()) {
                            ecoQosThrottleService.stop();
                        }
                        ecoQosAutoStarted = false;
                    }
                } catch (Exception e) {
                    System.err.println("EcoQoS auto-manage failed: " + e.getMessage());
                }
                // Notify the user about a sudden drop (not just the console)
                if (suddenMsg != null && suddenDropListener != null) {
                    try { suddenDropListener.accept(suddenMsg); } catch (Exception ignored) {}
                }
                int tc = ecoQosThrottleService != null ? ecoQosThrottleService.getThrottledCount() : 0;
                final boolean fEcoQosRunning = ecoQosThrottleService.isEnabled();
                Platform.runLater(() -> {
                    batteryPercent.set(fPct);
                    acOnline.set(fAc);
                    charging.set(fCh);
                    statusLine.set(fStatusFinal);
                    drainLabel.set(fDrain);
                    timeRemaining.set(fTime);
                    powerSaverOn.set(fSaver);
                    throttledCount.set(tc);
                    ecoQosRunning.set(fEcoQosRunning);
                    if (fSudden != null) {
                        // Also surface as drainLabel until the next poll overwrites it
                        drainLabel.set(fSudden);
                    }
                });

                // Processes every other poll (~10s) - deterministic counter, not clock phase
                if (pollTick % 2 == 0) {
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

    /**
     * Toggle Power Saver. Runs on a background thread - enable() spawns multiple
     * PowerShell/powercfg processes (seconds) and must never block the FX thread.
     * Double-clicks while a toggle is in flight are ignored.
     */
    public void togglePowerSaver() {
        if (!toggleInFlight.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                doTogglePowerSaver();
            } finally {
                toggleInFlight.set(false);
            }
        }, "toggle-power-saver");
        t.setDaemon(true);
        t.start();
    }

    private void doTogglePowerSaver() {
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
            Platform.runLater(() -> {
                powerSaverOn.set(isNowSaver);
                actionError.set("");
            });
        } catch (SecurityException se) {
            System.err.println("PowerSaver toggle blocked by security: " + se.getMessage());
            Platform.runLater(() -> {
                powerSaverOn.set(false);
                actionError.set("Power plan change blocked (security policy): " + se.getMessage());
            });
        } catch (Exception e) {
            System.err.println("PowerSaver toggle failed: " + e.getMessage());
            try {
                String cur2 = powerPlanService.getActivePlanGuid();
                boolean isSaver2 = powerPlanService.isPowerSaverGuid(cur2);
                Platform.runLater(() -> {
                    powerSaverOn.set(isSaver2);
                    actionError.set("Power Saver toggle failed: " + e.getMessage());
                });
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
                    // healthShort FIRST: the Health tab reads it from a healthPercent
                    // listener, so it must be fresh before the listener fires
                    healthShort.set(shortTxt);
                    healthPercent.set(hpVal);
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
    public StringProperty actionErrorProperty() { return actionError; }
    public BooleanProperty ecoQosEnabledProperty() { return ecoQosEnabled; }
    public BooleanProperty ecoQosRunningProperty() { return ecoQosRunning; }
    public IntegerProperty throttledCountProperty() { return throttledCount; }

    public PowerSaverModeService getPowerSaverService() { return powerSaverService; }

    /** Push a new user exclusion list (comma-separated exes) into the EcoQoS service. */
    public void updateEcoQosWhitelist(String csv) {
        if (ecoQosThrottleService != null) ecoQosThrottleService.setUserWhitelist(csv);
    }

    /** Set by Main: receives sudden-drop messages for user-visible notification. */
    public void setSuddenDropListener(Consumer<String> listener) {
        this.suddenDropListener = listener;
    }

    /**
     * Manual EcoQoS toggle (Status button / Settings save). Authoritative: the
     * poller never auto-stops a manually-started service. ON starts the engine
     * immediately (even on AC - the user asked for it); OFF stops it and turns
     * the master switch off (no auto-restart until re-enabled).
     */
    public void toggleEcoQos(boolean enable) {
        ecoQosEnabled.set(enable);
        ecoQosAutoStarted = false; // manual action - no longer "auto" state
        if (ecoQosThrottleService != null) {
            if (enable) {
                if (!ecoQosThrottleService.isEnabled()) {
                    // Start off the FX thread: it opens process handles system-wide
                    Thread t = new Thread(() -> {
                        ecoQosThrottleService.start();
                        // Reflect reality as soon as the start completes (don't wait for the next poll tick)
                        Platform.runLater(() -> ecoQosRunning.set(ecoQosThrottleService.isEnabled()));
                    }, "ecoqos-toggle");
                    t.setDaemon(true);
                    t.start();
                }
            } else {
                Thread t = new Thread(() -> {
                    ecoQosThrottleService.stop();
                    Platform.runLater(() -> ecoQosRunning.set(ecoQosThrottleService.isEnabled()));
                }, "ecoqos-toggle");
                t.setDaemon(true);
                t.start();
            }
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
