package com.batterysaver;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.service.*;
import com.batterysaver.util.ElevationUtil;
import com.batterysaver.util.PortableMode;
import com.batterysaver.util.SingleInstanceGuard;
import com.batterysaver.view.ExpandedView;
import com.batterysaver.view.TrayManager;
import com.batterysaver.view.WindowCoordinator;
import com.batterysaver.viewmodel.MainViewModel;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

public class Main extends Application {

    public static final String VERSION = AppConstants.VERSION;

    private BatteryStatusService batteryService;
    private PowerSaverModeService powerSaver;
    private HotkeyService hotkeyService;
    private IdleDimmingService idleService;
    private final NotificationService notifier = new NotificationService();
    private final ChargeLimitReminder chargeReminder = new ChargeLimitReminder();
    private final BatteryHistoryService historyService = new BatteryHistoryService();
    private final BatteryHealthService healthService = new BatteryHealthService();
    private EcoService ecoService;
    private EcoQosThrottleService ecoQosThrottleService;

    private MainViewModel viewModel;
    private ExpandedView expandedView;
    private TrayManager trayManager;
    private WindowCoordinator windowCoordinator;

    private ScheduledExecutorService focusPoller;
    private ScheduledExecutorService historyPoller;

    private boolean minimizedArg = false;
    private boolean firstCloseToTray = true;
    private Stage expandedStage;

    @Override
    public void start(Stage primaryStage) {
        Platform.setImplicitExit(false);
        // primaryStage is unused (hidden) - we use our own borderless stages
        primaryStage.hide();

        List<String> raw = getParameters().getRaw();
        minimizedArg = raw.stream().anyMatch(a -> a.equalsIgnoreCase("--minimized") || a.equalsIgnoreCase("-minimized"));

        SettingsService.Config cfg = SettingsService.load();
        boolean isPortable = PortableMode.isPortable(raw);

        // Services
        batteryService = new BatteryStatusService();
        powerSaver = new PowerSaverModeService();

        if (cfg.idleDimmingEnabled) {
            try {
                idleService = new IdleDimmingService(new BrightnessService(), cfg.idleMinutes, cfg.dimPercent, 100);
                idleService.start();
            } catch (Exception e) {
                System.err.println("IdleDimming failed: " + e.getMessage());
            }
        }
        try { ProcessUsageService.startSampling(); } catch (Exception ignored) {}

        ModernStandbyService msService = new ModernStandbyService();

        // EcoService - EnergyStarX-like but improved for any laptop, low overhead
        ecoService = new EcoService();
        try { ecoService.initialize(); } catch (Exception e) { System.err.println("Eco init failed: " + e.getMessage()); }

        // EcoQoS Background Throttling (Efficiency Mode) - Windows EcoQoS, same mechanism as Task Manager green leaf.
        // NOT started here: the vm-poller auto-starts it on its first tick (immediate) when
        // the SAVED setting allows it and we're on battery / Power Saver - so a user who
        // disabled EcoQoS in Settings never gets throttling after a restart.
        ecoQosThrottleService = new EcoQosThrottleService();
        ecoQosThrottleService.setUserWhitelist(cfg.ecoQosWhitelistStr);

// ViewModel - now with EcoService and EcoQosThrottleService for real-time throttling
        viewModel = new MainViewModel(cfg, historyService, healthService, powerSaver, msService, ecoService, ecoQosThrottleService);

        // Window coordinator - single owner for visibility + positioning
        windowCoordinator = new WindowCoordinator();

        // Expanded view (main window)
        expandedView = new ExpandedView(viewModel, historyService, healthService,
                this::hideToTray,
                this::showExpanded,
                this::applySettingsLive,
                this::restartAsAdministrator);

        expandedStage = expandedView.createStage();
        windowCoordinator.setStage(expandedStage);

        // Tray - install immediately on FX thread
        trayManager = new TrayManager(viewModel,
                this::showExpanded,          // Open WBS
                this::showSettingsTab,       // Settings...
                this::showAboutTab,          // About
                this::doExit,                // Exit WBS
                this::togglePowerSaver);
        try {
            trayManager.install();
        } catch (Exception e) {
            System.err.println("Tray install failed: " + e.getMessage());
        }

        // Sudden-drop detection -> user-visible toast (was console-only)
        viewModel.setSuddenDropListener(msg -> notifier.showWarning("Sudden battery drop", msg));

        // When the main window is hidden, action errors (e.g. Power Saver blocked by
        // policy) surface as a tray toast; ExpandedView shows the alert when visible.
        viewModel.actionErrorProperty().addListener((o, old, err) -> {
            if (err != null && !err.isBlank() && expandedStage != null && !expandedStage.isShowing()) {
                trayManager.showMessage("WBS action failed", err);
            }
        });

        // Hotkey
        hotkeyService = new HotkeyService(this::togglePowerSaver);
        try {
            hotkeyService.register(cfg.hotkeyModifiers, cfg.hotkeyVk, this::togglePowerSaver);
        } catch (Exception e) {
            System.err.println("Hotkey register failed: " + e.getMessage());
        }

        // History append poller - live, all timings, adaptive
        historyPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-poller");
            t.setDaemon(true);
            return t;
        });
        historyPoller.scheduleAtFixedRate(() -> {
            try {
                var b = BatteryStatusService.getLastStatus();
                int pct = b.getPercent();
                boolean onAc = b.isOnAC();
                boolean isCharging = b.isChargingFlag() || (onAc && pct >= 0 && pct < 100);
                appendHistoryThrottled(pct, onAc);
                if (cfg.chargeLimitEnabled) {
                    chargeReminder.check(pct, isCharging, cfg.chargeLimitPercent, notifier);
                }
                checkLowBattery(pct, onAc, cfg);
            } catch (Exception e) {
                System.err.println("history poll error: " + e.getMessage());
            }
        }, 5, 30, TimeUnit.SECONDS);
        // Seed the history on a background thread through the SAME throttle path so
        // the poller's first tick doesn't append a duplicate sample and no file I/O
        // happens on the FX thread
        Thread historySeed = new Thread(() -> {
            try {
                var b = BatteryStatusService.getLastStatus();
                appendHistoryThrottled(b.getPercent(), b.isOnAC());
            } catch (Exception ignored) {}
        }, "history-seed");
        historySeed.setDaemon(true);
        historySeed.start();

        // Focus poll for single instance
        focusPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "focus-poller"); t.setDaemon(true); return t;
        });
        focusPoller.scheduleAtFixedRate(() -> {
            try {
                var flag = SingleInstanceGuard.getFocusFlagPath();
                if (Files.exists(flag)) {
                    Files.deleteIfExists(flag);
                    Platform.runLater(() -> {
                        showExpanded();
                        trayManager.showMessage(AppConstants.APP_SHORT_NAME, "Already running - focused existing window.");
                    });
                }
            } catch (Exception ignored) {}
        }, 2, 2, TimeUnit.SECONDS);

        try { historyService.trimTo30Days(); } catch (Exception ignored) {}

        expandedStage.setOnCloseRequest(ev -> {
            ev.consume();
            hideToTray();
            if (firstCloseToTray) {
                firstCloseToTray = false;
                try {
                    Preferences prefs = Preferences.userNodeForPackage(Main.class);
                    boolean shown = prefs.getBoolean("trayToastShown", false);
                    if (!shown) {
                        prefs.putBoolean("trayToastShown", true);
                        Platform.runLater(() -> trayManager.showMessage(AppConstants.APP_SHORT_NAME, "WBS is running in system tray. Click tray icon to open."));
                    }
                } catch (Exception ignored) {}
            }
        });

        // Show logic: if --minimized, start silently in system tray; otherwise show main window
        if (minimizedArg) {
            System.out.println("Starting minimized to system tray (--minimized)");
        } else {
            Platform.runLater(() -> windowCoordinator.showExpanded());
        }

        // Update check if enabled
        if (cfg.updateCheckEnabled) {
            Thread updateThread = new Thread(() -> {
                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                UpdateCheckService svc = new UpdateCheckService();
                UpdateCheckService.UpdateInfo info = svc.check(cfg.updateRepoSlug);
                if (info != null && info.newer()) {
                    Platform.runLater(() -> {
                        // Tray toast always (silent, non-intrusive)
                        if (trayManager != null) {
                            trayManager.showMessage("WBS update available", info.latestTag() + " available - click to open releases");
                        }
                        // Modal alert ONLY when the window is visible - a hidden tray
                        // app must never steal focus with a dialog
                        if (expandedStage != null && expandedStage.isShowing()) {
                            javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                            a.setTitle("Update available");
                            a.setHeaderText(AppConstants.APP_FULL_NAME + " " + info.latestTag() + " is available");
                            a.setContentText("Current: v" + VERSION + "\nLatest: " + info.latestTag() + "\n\nOpen releases page?");
                            a.getButtonTypes().setAll(javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
                            a.initOwner(expandedStage);
                            a.showAndWait().ifPresent(bt -> {
                                if (bt == javafx.scene.control.ButtonType.YES) {
                                    try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(info.htmlUrl())); } catch (Exception ex) {}
                                }
                            });
                        }
                    });
                }
            }, "update-check");
            updateThread.setDaemon(true);
            updateThread.start();
        }

        // Coordinator already positions near tray / centered; no extra manual placement needed
        
    }

    private void showExpanded() {
        Platform.runLater(() -> windowCoordinator.showExpanded());
    }

    /** Applies newly saved settings without a restart: hotkey re-registration
     *  and idle-dimming enable/disable/threshold updates. */
    /**
     * Restart the app with an Administrator token so EcoQoS can also throttle
     * elevated and background-service processes (Windows blocks OpenProcess on
     * those for normal-user apps). Frees the single-instance lock for the
     * elevated copy; if the user declines the UAC prompt, re-acquires it.
     */
    private void restartAsAdministrator() {
        if (ElevationUtil.isRunningElevated()) return; // nothing to do
        Thread t = new Thread(() -> {
            SingleInstanceGuard.release(); // let the elevated copy acquire the lock
            boolean launched;
            try {
                launched = ElevationUtil.launchElevated();
            } catch (Exception e) {
                launched = false;
            }
            if (launched) {
                System.out.println("Restart as Administrator: elevated instance launched - exiting this one");
                doExit();
            } else {
                // UAC declined or unavailable - keep running as the only instance
                try { SingleInstanceGuard.acquire(); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    if (trayManager != null) {
                        trayManager.showMessage(AppConstants.APP_SHORT_NAME,
                                "Restart as Administrator cancelled or unavailable - continuing as normal user.");
                    }
                });
            }
        }, "elevate-restart");
        t.setDaemon(true);
        t.start();
    }

    private void applySettingsLive() {        SettingsService.Config cfg = viewModel != null ? viewModel.getConfig() : null;
        if (cfg == null) return;
        // Hotkey: register() un-registers the old one first
        try {
            if (hotkeyService != null) hotkeyService.register(cfg.hotkeyModifiers, cfg.hotkeyVk, this::togglePowerSaver);
        } catch (Exception e) {
            System.err.println("Hotkey re-register failed: " + e.getMessage());
        }
        // Idle dimming: start/stop/update
        try {
            if (cfg.idleDimmingEnabled) {
                if (idleService == null) {
                    idleService = new IdleDimmingService(new BrightnessService(), cfg.idleMinutes, cfg.dimPercent, 100);
                    idleService.start();
                } else {
                    idleService.updateSettings(cfg.idleMinutes, cfg.dimPercent);
                }
            } else if (idleService != null) {
                idleService.stop();
                idleService = null;
            }
        } catch (Exception e) {
            System.err.println("Idle settings apply failed: " + e.getMessage());
        }
    }

    private void showSettingsTab() {
        showExpanded();
        expandedView.selectTab("Settings");
    }

    private void showAboutTab() {
        showExpanded();
        expandedView.selectTab("About");
    }

    private void hideToTray() {
        Platform.runLater(() -> {
            windowCoordinator.hideAll();
            if (firstCloseToTray) {
                firstCloseToTray = false;
                try {
                    Preferences prefs = Preferences.userNodeForPackage(Main.class);
                    boolean shown = prefs.getBoolean("trayToastShown", false);
                    if (!shown) {
                        prefs.putBoolean("trayToastShown", true);
                        if (trayManager != null) {
                            trayManager.showMessage(AppConstants.APP_SHORT_NAME, "WBS is running in system tray. Click tray icon to open.");
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void togglePowerSaver() {
        // VM runs the heavy work on its own background thread; failures are
        // surfaced via actionErrorProperty (alert when visible, tray toast when not)
        if (viewModel != null) viewModel.togglePowerSaver();
    }

    private void doExit() {
        // Save window position if the window is showing (hideAll persists it)
        try { if (windowCoordinator != null) windowCoordinator.hideAll(); } catch (Exception ignored) {}
        shutdownServices();
        Platform.exit();
        System.exit(0);
    }

    private long lastHistoryMs = 0;
    private int lastHistoryPct = -999;
    private boolean lowNotified = false;
    private boolean criticalNotified = false;

    /** Low/critical battery toasts with separate re-arm flags so a critical alert
     *  can still fire after the low alert while the battery keeps sinking.
     *  Each re-arms once the level recovers 5% above its threshold or AC connects. */
    private void checkLowBattery(int pct, boolean onAc, SettingsService.Config cfg) {
        if (pct < 0) return;
        if (onAc) {
            lowNotified = false;
            criticalNotified = false;
            return;
        }
        if (pct <= cfg.criticalBatteryThreshold) {
            if (!criticalNotified) {
                notifier.showWarning("Battery critical", pct + "% remaining - plug in your charger now.");
                criticalNotified = true;
            }
            lowNotified = true; // low was already implied on the way down
        } else if (pct <= cfg.lowBatteryThreshold) {
            if (!lowNotified) {
                notifier.showInfo("Battery low", pct + "% remaining - consider plugging in.");
                lowNotified = true;
            }
            if (pct > cfg.criticalBatteryThreshold + 5) criticalNotified = false;
        } else {
            if (pct > cfg.lowBatteryThreshold + 5) lowNotified = false;
            if (pct > cfg.criticalBatteryThreshold + 5) criticalNotified = false;
        }
    }

    private synchronized void appendHistoryThrottled(int pct, boolean onAC) {
        if (pct < 0) return;
        long now = System.currentTimeMillis();
        boolean changed = pct != lastHistoryPct;
        // Adaptive: record more frequently when on battery (30s) to catch sudden drops, 60s when on AC (low overhead)
        long interval = onAC ? 60_000 : 30_000;
        boolean timeDue = (now - lastHistoryMs) >= interval;
        if (changed || timeDue) {
            historyService.append(pct, onAC);
            lastHistoryMs = now;
            lastHistoryPct = pct;
        }
    }

    @Override
    public void stop() {
        shutdownServices();
    }

    /** Single central shutdown path - must un-throttle everything we touched
     *  (EcoQoS persists after our exit, so stop() is mandatory, not optional). */
    private void shutdownServices() {
        try { if (viewModel != null) viewModel.stop(); } catch (Exception ignored) {}
        try { if (ecoQosThrottleService != null) ecoQosThrottleService.stop(); } catch (Exception ignored) {}
        try { if (ecoService != null) ecoService.shutdown(); } catch (Exception ignored) {}
        try { if (hotkeyService != null) hotkeyService.unregister(); } catch (Exception ignored) {}
        try { if (idleService != null) idleService.stop(); } catch (Exception ignored) {}
        try { if (batteryService != null) batteryService.stop(); } catch (Exception ignored) {}
        try { ProcessUsageService.stopSampling(); } catch (Exception ignored) {}
        if (historyPoller != null) historyPoller.shutdownNow();
        if (focusPoller != null) focusPoller.shutdownNow();
        // Every call guarded: an AWT hiccup here must never skip
        // SingleInstanceGuard.release() below
        try { if (trayManager != null) trayManager.remove(); } catch (Exception ignored) {}
        try { notifier.remove(); } catch (Exception ignored) {}
        SingleInstanceGuard.release();
    }

    public static void main(String[] args) {
        boolean ok = SingleInstanceGuard.acquire();
        if (!ok) {
            SingleInstanceGuard.signalFocus();
            System.out.println("Another WBS instance is already running - signaling focus and exiting.");
            System.exit(0);
        }
        launch(args);
    }
}
