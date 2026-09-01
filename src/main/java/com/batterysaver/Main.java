package com.batterysaver;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.service.*;
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

        // EcoQoS Background Throttling (Efficiency Mode) - Windows EcoQoS, same mechanism as Task Manager green leaf
        ecoQosThrottleService = new EcoQosThrottleService();
        try { ecoQosThrottleService.start(); } catch (Exception e) { System.err.println("EcoQos init failed: " + e.getMessage()); }

// ViewModel - now with EcoService and EcoQosThrottleService for real-time throttling
        viewModel = new MainViewModel(cfg, historyService, healthService, powerSaver, msService, ecoService, ecoQosThrottleService);

        // Window coordinator - single owner for visibility + positioning
        windowCoordinator = new WindowCoordinator();

        // Expanded view (main window)
        expandedView = new ExpandedView(viewModel, historyService, healthService,
                this::hideToTray,
                this::showExpanded);

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
            } catch (Exception e) {
                System.err.println("history poll error: " + e.getMessage());
            }
        }, 5, 30, TimeUnit.SECONDS);
        try {
            var b = BatteryStatusService.getLastStatus();
            if (b.getPercent() >= 0) historyService.append(b.getPercent(), b.isOnAC());
        } catch (Exception ignored) {}

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
            new Thread(() -> {
                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                UpdateCheckService svc = new UpdateCheckService();
                UpdateCheckService.UpdateInfo info = svc.check(cfg.updateRepoSlug);
                if (info != null && info.newer()) {
                    Platform.runLater(() -> trayManager.showMessage("WBS update available", info.latestTag() + " available - click to open releases"));
                    // Also show alert when expanded is visible
                    Platform.runLater(() -> {
                        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
                        a.setTitle("Update available");
                        a.setHeaderText(AppConstants.APP_FULL_NAME + " " + info.latestTag() + " is available");
                        a.setContentText("Current: v" + VERSION + "\nLatest: " + info.latestTag() + "\n\nOpen releases page?");
                        a.getButtonTypes().setAll(javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
                        a.showAndWait().ifPresent(bt -> {
                            if (bt == javafx.scene.control.ButtonType.YES) {
                                try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(info.htmlUrl())); } catch (Exception ex) {}
                            }
                        });
                    });
                }
            }, "update-check").start();
        }

        // Coordinator already positions near tray / centered; no extra manual placement needed
        
    }

    private void showExpanded() {
        Platform.runLater(() -> windowCoordinator.showExpanded());
    }

    private void showSettingsTab() {
        showExpanded();
        // TabPane is internal to ExpandedView; we need to select Settings tab.
        // For simplicity, just show expanded and user clicks Settings. Could add method to ExpandedView to select tab.
        // We'll add a helper: try to find TabPane via stage scene lookup
        Platform.runLater(() -> {
            try {
                var scene = expandedStage.getScene();
                var pane = (javafx.scene.control.TabPane) scene.lookup(".wbs-tabs");
                if (pane != null) {
                    for (var tab : pane.getTabs()) {
                        if ("Settings".equals(tab.getText())) {
                            pane.getSelectionModel().select(tab);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void showAboutTab() {
        showExpanded();
        Platform.runLater(() -> {
            try {
                var scene = expandedStage.getScene();
                var pane = (javafx.scene.control.TabPane) scene.lookup(".wbs-tabs");
                if (pane != null) {
                    for (var tab : pane.getTabs()) {
                        if ("About".equals(tab.getText())) {
                            pane.getSelectionModel().select(tab);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
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
        Platform.runLater(() -> {
            try {
                viewModel.togglePowerSaver();
            } catch (SecurityException se) {
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING, se.getMessage(), javafx.scene.control.ButtonType.OK);
                a.setHeaderText("Power plan restricted");
                a.showAndWait();
            }
        });
    }

    private void doExit() {
        try { if (hotkeyService != null) hotkeyService.unregister(); } catch (Exception ignored) {}
        try { if (idleService != null) idleService.stop(); } catch (Exception ignored) {}
        try { if (batteryService != null) batteryService.stop(); } catch (Exception ignored) {}
        try { ProcessUsageService.stopSampling(); } catch (Exception ignored) {}
        try { if (ecoService != null) ecoService.shutdown(); } catch (Exception ignored) {}
        if (historyPoller != null) historyPoller.shutdownNow();
        if (focusPoller != null) focusPoller.shutdownNow();
        if (viewModel != null) viewModel.stop();
        if (trayManager != null) trayManager.remove();
        notifier.remove();
        SingleInstanceGuard.release();
        Platform.exit();
        System.exit(0);
    }

    private long lastHistoryMs = 0;
    private int lastHistoryPct = -999;
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
        try { if (hotkeyService != null) hotkeyService.unregister(); } catch (Exception ignored) {}
        try { if (idleService != null) idleService.stop(); } catch (Exception ignored) {}
        try { if (batteryService != null) batteryService.stop(); } catch (Exception ignored) {}
        try { ProcessUsageService.stopSampling(); } catch (Exception ignored) {}
        try { if (ecoService != null) ecoService.shutdown(); } catch (Exception ignored) {}
        if (historyPoller != null) historyPoller.shutdownNow();
        if (focusPoller != null) focusPoller.shutdownNow();
        if (viewModel != null) viewModel.stop();
        if (trayManager != null) trayManager.remove();
        notifier.remove();
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
