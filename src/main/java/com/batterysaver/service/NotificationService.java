package com.batterysaver.service;

import javafx.application.Platform;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class NotificationService {
    private TrayIcon icon;
    private SystemTray tray;
    // Whether WE added the private blank icon (vs reusing TrayManager's icon).
    // remove() must only remove what we added, otherwise heuristics can leave
    // the blank icon in the tray or remove the shared one.
    private volatile boolean addedPrivateIcon = false;

    public NotificationService() {
        try {
            if (SystemTray.isSupported()) {
                tray = SystemTray.getSystemTray();
                icon = new TrayIcon(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "WBS-notification");
                icon.setImageAutoSize(true);
            } else {
                tray = null;
                icon = null;
            }
        } catch (Exception e) {
            tray = null;
            icon = null;
        }
    }

    private TrayIcon resolveIcon() {
        // Prefer the shared TrayManager icon (tooltip starts with "WBS - ") over our
        // private blank one, avoiding duplicate/invisible tray icons.
        try {
            if (SystemTray.isSupported()) {
                SystemTray t = SystemTray.getSystemTray();
                for (TrayIcon ti : t.getTrayIcons()) {
                    if (ti != icon && ti.getToolTip() != null && ti.getToolTip().startsWith("WBS - ")) {
                        return ti;
                    }
                }
            }
        } catch (Exception ignored) {}
        return icon;
    }

    public void showInfo(String title, String message) {
        showTrayOrFallback(title, message, TrayIcon.MessageType.INFO);
    }

    public void showWarning(String title, String message) {
        showTrayOrFallback(title, message, TrayIcon.MessageType.WARNING);
    }

    /**
     * All AWT tray access (scanning icons, adding the private icon, displaying the
     * balloon) happens on the EDT - callers are background poller threads and AWT
     * tray APIs are not guaranteed thread-safe off-EDT.
     */
    private void showTrayOrFallback(String title, String message, TrayIcon.MessageType type) {
        if (SystemTray.isSupported()) {
            try {
                java.awt.EventQueue.invokeAndWait(() -> {
                    try {
                        TrayIcon target = resolveIcon();
                        if (target != null) {
                            SystemTray t = SystemTray.getSystemTray();
                            if (target == icon) {
                                try {
                                    if (!java.util.Arrays.asList(t.getTrayIcons()).contains(icon)) {
                                        t.add(icon);
                                        addedPrivateIcon = true;
                                    }
                                } catch (IllegalArgumentException ignored) {}
                            }
                            target.displayMessage(title, message, type);
                        } else {
                            fallbackDialog(title, message);
                        }
                    } catch (Exception e) {
                        System.err.println("Tray display failed: " + e.getMessage());
                        fallbackDialog(title, message);
                    }
                });
                return;
            } catch (Exception e) {
                System.err.println("Notification dispatch failed: " + e.getMessage());
            }
        }
        fallbackDialog(title, message);
    }

    /** Fallback to JavaFX Alert on FX thread, Swing dialog on EDT. */
    private void fallbackDialog(String title, String message) {
        try {
            if (Platform.isFxApplicationThread()) {
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, message, javafx.scene.control.ButtonType.OK);
                a.setTitle(title);
                a.setHeaderText(title);
                a.showAndWait();
            } else {
                Platform.runLater(() -> {
                    javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION, message, javafx.scene.control.ButtonType.OK);
                    a.setTitle(title);
                    a.setHeaderText(title);
                    a.show();
                });
            }
        } catch (Exception ignored) {
            // Last resort: Swing on EDT
            try {
                javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e2) { System.out.println(title + ": " + message); }
        }
    }

    public void remove() {
        // Remove ONLY the private icon if we actually added it - the shared
        // TrayManager icon is owned and removed by TrayManager.
        try {
            if (addedPrivateIcon && tray != null && icon != null
                    && java.util.Arrays.asList(tray.getTrayIcons()).contains(icon)) {
                tray.remove(icon);
            }
        } catch (Exception ignored) {} finally {
            addedPrivateIcon = false;
        }
    }
}