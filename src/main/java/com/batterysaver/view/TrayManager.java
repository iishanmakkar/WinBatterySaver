package com.batterysaver.view;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.service.SettingsService;
import com.batterysaver.viewmodel.MainViewModel;
import javafx.application.Platform;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * System tray manager - AWT SystemTray with WBS menu.
 * Handles: battery icon states, tooltip, power saver toggle, charge limit quick-pick, open/settings/about/exit.
 */
public class TrayManager {

    private final MainViewModel vm;
    private final Runnable onOpenExpanded;
    private final Runnable onOpenSettings;
    private final Runnable onAbout;
    private final Runnable onExit;
    private final Runnable onTogglePowerSaver;

    private SystemTray tray;
    private TrayIcon icon;
    private CheckboxMenuItem powerItem;
    private Menu chargeMenu;
    private Label tooltipUpdater; // dummy

    public TrayManager(MainViewModel vm,
                       Runnable onOpenExpanded,
                       Runnable onOpenSettings,
                       Runnable onAbout,
                       Runnable onExit,
                       Runnable onTogglePowerSaver) {
        this.vm = vm;
        this.onOpenExpanded = onOpenExpanded;
        this.onOpenSettings = onOpenSettings;
        this.onAbout = onAbout;
        this.onExit = onExit;
        this.onTogglePowerSaver = onTogglePowerSaver;
    }

    public void install() {
        System.out.println("TrayManager.install: SystemTray.isSupported=" + SystemTray.isSupported());
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray not supported - tray will not appear");
            return;
        }
        tray = SystemTray.getSystemTray();
        System.out.println("Tray icons before: " + tray.getTrayIcons().length);
        // Remove old WBS icons (ghost after crash)
        for (TrayIcon ti : tray.getTrayIcons()) {
            try {
                if (ti.getToolTip() != null && ti.getToolTip().contains("WBS")) {
                    System.out.println("Removing stale WBS tray icon");
                    tray.remove(ti);
                }
            } catch (Exception ignored) {}
        }

        Image img = createBatteryIcon(vm.batteryPercentProperty().get(), vm.chargingProperty().get());
        if (img == null) {
            System.err.println("Tray icon image creation failed - using fallback");
            img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        }
        System.out.println("Tray icon image created: " + img.getWidth(null) + "x" + img.getHeight(null));
        icon = new TrayIcon(img, tooltipText());
        icon.setImageAutoSize(true);
        icon.setToolTip(tooltipText());

        PopupMenu menu = new PopupMenu();

        // Header: 28% - AC: offline (disabled)
        MenuItem header = new MenuItem(headerText());
        header.setEnabled(false);
        menu.add(header);

        menu.addSeparator();

        powerItem = new CheckboxMenuItem("Power Saver: " + (vm.powerSaverOnProperty().get() ? "ON" : "OFF"));
        powerItem.setState(vm.powerSaverOnProperty().get());
        powerItem.addItemListener(e -> {
            if (onTogglePowerSaver != null) onTogglePowerSaver.run();
            // Update state after toggle
            Platform.runLater(() -> update());
        });
        menu.add(powerItem);

        MenuItem open = new MenuItem("Open WBS");
        open.addActionListener(e -> Platform.runLater(onOpenExpanded));
        menu.add(open);

        menu.addSeparator();

        chargeMenu = new Menu("Charge limit: " + vm.getConfig().chargeLimitPercent + "%");
        for (int v : new int[]{60,70,80,90,100}) {
            MenuItem mi = new MenuItem(v + "%");
            final int val = v;
            mi.addActionListener(e -> {
                SettingsService.Config cfg = vm.getConfig();
                cfg.chargeLimitPercent = val;
                SettingsService.save(cfg);
                vm.updateConfig(cfg);
                Platform.runLater(() -> update());
            });
            chargeMenu.add(mi);
        }
        menu.add(chargeMenu);

        menu.addSeparator();

        MenuItem settings = new MenuItem("Settings...");
        settings.addActionListener(e -> Platform.runLater(onOpenSettings));
        menu.add(settings);

        MenuItem about = new MenuItem("About");
        about.addActionListener(e -> Platform.runLater(onAbout));
        menu.add(about);

        menu.addSeparator();

        MenuItem exit = new MenuItem("Exit WBS");
        exit.addActionListener(e -> {
            if (onExit != null) {
                // Run cleanup on FX thread; doExit handles Platform.exit + System.exit
                Platform.runLater(onExit);
            }
        });
        menu.add(exit);

        icon.setPopupMenu(menu);

        // Single click also opens (WMC behavior: single-click restores), double still works
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    // Single left click -> open
                    Platform.runLater(() -> {
                        if (onOpenExpanded != null) onOpenExpanded.run();
                    });
                }
            }
        });
        // Also handle action (some L&Fs)
        icon.addActionListener(e -> Platform.runLater(() -> {
            if (onOpenExpanded != null) onOpenExpanded.run();
        }));

        try {
            // Must add on EDT
            if (java.awt.EventQueue.isDispatchThread()) {
                tray.add(icon);
            } else {
                java.awt.EventQueue.invokeAndWait(() -> {
                    try { tray.add(icon); } catch (Exception ex) { throw new RuntimeException(ex); }
                });
            }
            System.out.println("Tray icon added OK - count now: " + tray.getTrayIcons().length + " tooltip=" + icon.getToolTip());
        } catch (Exception e) {
            System.err.println("Tray add failed: " + e);
            e.printStackTrace();
        }

        // Bind VM listeners for live update
        vm.batteryPercentProperty().addListener((o, old, p) -> update());
        vm.acOnlineProperty().addListener((o, old, v) -> update());
        vm.chargingProperty().addListener((o, old, v) -> update());
        vm.powerSaverOnProperty().addListener((o, old, v) -> update());
        vm.drainLabelProperty().addListener((o, old, v) -> update());
        vm.statusLineProperty().addListener((o, old, v) -> update());

        // Show initial
        update();
    }

    private void update() {
        if (icon == null) return;
        // Must be on EDT? TrayIcon is AWT, but we are on FX thread - use EventQueue
        java.awt.EventQueue.invokeLater(() -> {
            Image img = createBatteryIcon(vm.batteryPercentProperty().get(), vm.chargingProperty().get());
            icon.setImage(img);
            icon.setToolTip(tooltipText());
            if (icon.getPopupMenu() != null && icon.getPopupMenu().getItemCount() > 0) {
                // Update header
                MenuItem header = (MenuItem) icon.getPopupMenu().getItem(0);
                header.setLabel(headerText());
                if (powerItem != null) {
                    powerItem.setLabel("Power Saver: " + (vm.powerSaverOnProperty().get() ? "ON" : "OFF"));
                    powerItem.setState(vm.powerSaverOnProperty().get());
                }
                if (chargeMenu != null) {
                    chargeMenu.setLabel("Charge limit: " + vm.getConfig().chargeLimitPercent + "%");
                }
            }
        });
    }

    private String headerText() {
        int pct = vm.batteryPercentProperty().get();
        String pctStr = pct < 0 ? "--" : pct + "%";
        String ac = vm.acOnlineProperty().get() ? "online" : "offline";
        return pctStr + " - AC: " + ac;
    }

    private String tooltipText() {
        int pct = vm.batteryPercentProperty().get();
        String pctStr = pct < 0 ? "--" : pct + "%";
        String drain = vm.drainLabelProperty().get();
        if (drain == null || drain.isBlank()) drain = vm.statusLineProperty().get();
        if (drain == null) drain = "";
        // Truncate
        if (drain.length() > 40) drain = drain.substring(0, 37) + "...";
        return AppConstants.TRAY_TOOLTIP_PREFIX + " - " + pctStr + (drain.isBlank() ? "" : " (" + drain + ")");
    }

    private Image createBatteryIcon(int pct, boolean charging) {
        return com.batterysaver.util.IconUtil.createAwtBatteryIcon(32, pct, charging);
    }

    public void remove() {
        if (tray != null && icon != null) {
            tray.remove(icon);
        }
    }

    public void showMessage(String title, String msg) {
        if (icon != null) {
            icon.displayMessage(title, msg, TrayIcon.MessageType.INFO);
        }
    }
}
