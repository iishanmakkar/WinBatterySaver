package com.batterysaver.view;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Single owner for window visibility. Remembers the user's window position
 * across restarts (Preferences) and clamps it to a visible screen so the
 * window can never get lost off-screen (e.g. after unplugging a monitor).
 */
public class WindowCoordinator {

    private Stage expandedStage;
    private boolean positioned = false;
    private final Preferences prefs = Preferences.userNodeForPackage(WindowCoordinator.class);

    public WindowCoordinator() {}

    public void setStage(Stage expandedStage) {
        this.expandedStage = expandedStage;
    }

    public synchronized void showExpanded() {
        if (expandedStage == null) return;
        if (!positioned) {
            restoreOrCenter(expandedStage);
            positioned = true;
        } else {
            clampToVisibleScreen(expandedStage);
        }
        expandedStage.show();
        expandedStage.toFront();
        expandedStage.requestFocus();
    }

    public synchronized void hideAll() {
        if (expandedStage != null && expandedStage.isShowing()) {
            savePosition(expandedStage);
            expandedStage.hide();
        }
    }

    public boolean isExpandedShowing() { return expandedStage != null && expandedStage.isShowing(); }

    public Stage getExpandedStage() { return expandedStage; }

    private void restoreOrCenter(Stage stage) {
        double x = prefs.getDouble("win.x", Double.NaN);
        double y = prefs.getDouble("win.y", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            stage.setX(x);
            stage.setY(y);
            clampToVisibleScreen(stage);
        } else {
            centerOnPrimaryScreen(stage);
        }
    }

    private void savePosition(Stage stage) {
        try {
            prefs.putDouble("win.x", stage.getX());
            prefs.putDouble("win.y", stage.getY());
        } catch (Exception ignored) {}
    }

    /** Keep the title bar reachable: at least 100x40px of the window on some screen. */
    private void clampToVisibleScreen(Stage stage) {
        try {
            double x = stage.getX();
            double y = stage.getY();
            double w = stage.getWidth();
            double h = stage.getHeight();
            boolean visible = false;
            for (Screen s : Screen.getScreensForRectangle(x, y, Math.min(w, 100), Math.min(h, 40))) {
                Rectangle2D b = s.getVisualBounds();
                if (x + 100 > b.getMinX() && x < b.getMaxX() && y + 40 > b.getMinY() && y < b.getMaxY()) {
                    visible = true;
                    break;
                }
            }
            if (!visible) {
                // Fell off-screen (monitor unplugged etc.) - recenter on primary
                centerOnPrimaryScreen(stage);
            }
        } catch (Exception ignored) {}
    }

    private void centerOnPrimaryScreen(Stage stage) {
        try {
            Rectangle2D b = Screen.getPrimary().getVisualBounds();
            stage.setX(b.getMinX() + (b.getWidth() - stage.getWidth()) / 2);
            stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
        } catch (Exception ignored) {}
    }
}
