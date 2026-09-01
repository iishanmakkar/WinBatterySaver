package com.batterysaver.service;

import javafx.application.Platform;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class NotificationService {
    private TrayIcon icon;
    private SystemTray tray;

    public NotificationService() {
        try {
            if (SystemTray.isSupported()) {
                tray = SystemTray.getSystemTray();
                icon = new TrayIcon(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "WBS");
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
        // Prefer existing WBS icon from TrayManager if present (avoid duplicate invisible icons)
        try {
            if (SystemTray.isSupported()) {
                SystemTray t = SystemTray.getSystemTray();
                for (TrayIcon ti : t.getTrayIcons()) {
                    if (ti.getToolTip() != null && ti.getToolTip().contains("WBS")) {
                        return ti;
                    }
                }
            }
        } catch (Exception ignored) {}
        return icon;
    }

    public void showInfo(String title, String message) {
        TrayIcon target = resolveIcon();
        if (target != null && SystemTray.isSupported()) {
            try {
                SystemTray t = SystemTray.getSystemTray();
                // If target is our private icon and not yet added, try to add it on EDT
                if (target == icon) {
                    try {
                        if (!java.util.Arrays.asList(t.getTrayIcons()).contains(icon)) {
                            // Must add on EDT
                            if (java.awt.EventQueue.isDispatchThread()) t.add(icon);
                            else java.awt.EventQueue.invokeAndWait(() -> {
                                try { t.add(icon); } catch (Exception ignored) {}
                            });
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
                target.displayMessage(title, message, TrayIcon.MessageType.INFO);
                System.out.println("Notification: " + title + " - " + message);
                return;
            } catch (Exception e) {
                System.err.println("Tray display failed: " + e.getMessage());
            }
        }
        // Fallback to JavaFX Alert on FX thread, Swing dialog on EDT
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

    public void showWarning(String title, String message) {
        TrayIcon target = resolveIcon();
        if (target != null && SystemTray.isSupported()) {
            try {
                target.displayMessage(title, message, TrayIcon.MessageType.WARNING);
                return;
            } catch (Exception ignored) {}
        }
        try {
            if (Platform.isFxApplicationThread()) {
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING, message, javafx.scene.control.ButtonType.OK);
                a.setTitle(title);
                a.setHeaderText(title);
                a.showAndWait();
            } else {
                Platform.runLater(() -> {
                    javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING, message, javafx.scene.control.ButtonType.OK);
                    a.setTitle(title);
                    a.setHeaderText(title);
                    a.show();
                });
            }
        } catch (Exception ignored) {
            try { javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)); }
            catch (Exception e2) { System.out.println(title + ": " + message); }
        }
    }

    public void remove() {
        // Only remove our private icon if it was added and is not the shared WBS icon
        try {
            if (tray != null && icon != null) {
                boolean isShared = false;
                for (TrayIcon ti : tray.getTrayIcons()) {
                    if (ti == icon && ti.getToolTip() != null && ti.getToolTip().contains("WBS")) {
                        // This is shared TrayManager icon - don't remove here; TrayManager owns it
                        // But our icon tooltip is "WBS" too, so ambiguous - check if we added duplicate?
                        // If multiple WBS icons, keep the first one
                    }
                }
                // Try to remove only if we own it and it's still there
                if (java.util.Arrays.asList(tray.getTrayIcons()).contains(icon)) {
                    // If there are 2 WBS icons, remove the private one (the last added)
                    // Heuristic: if count >1, remove ours
                    if (tray.getTrayIcons().length > 1) {
                        tray.remove(icon);
                    } else {
                        // Single icon - likely TrayManager's, don't remove (let TrayManager handle)
                        // But if TrayManager not yet installed, this is our only icon, safe to remove on exit
                        // We will keep it until TrayManager removes
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}