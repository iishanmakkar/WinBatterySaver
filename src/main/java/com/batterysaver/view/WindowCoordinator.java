package com.batterysaver.view;

import javafx.stage.Stage;

/**
 * Single owner for window visibility. Manages main application window
 * positioning and visibility.
 */
public class WindowCoordinator {

    private Stage expandedStage;
    private java.util.function.Supplier<Stage> expandedBuilder;

    public WindowCoordinator() {}

    public void setBuilders(java.util.function.Supplier<Stage> compactBuilder,
                            java.util.function.Supplier<Stage> expandedBuilder) {
        this.expandedBuilder = expandedBuilder;
    }

    public void setStages(Stage compactStage, Stage expandedStage) {
        this.compactStage = compactStage;
        this.expandedStage = expandedStage;
    }

    public void setStage(Stage expandedStage) {
        this.expandedStage = expandedStage;
    }

    public synchronized void showExpanded() {
        if (expandedStage == null) {
            if (expandedBuilder != null) expandedStage = expandedBuilder.get();
            else return;
        }
        centerOnPrimaryScreen(expandedStage);
        expandedStage.show();
        expandedStage.toFront();
        expandedStage.requestFocus();
    }

    public synchronized void showCompact() {
        if (compactStage == null) return;
        centerOnPrimaryScreen(compactStage);
        compactStage.show();
        compactStage.toFront();
        compactStage.requestFocus();
    }

    public synchronized void hideAll() {
        if (expandedStage != null && expandedStage.isShowing()) expandedStage.hide();
    }

    public boolean isExpandedShowing() { return expandedStage != null && expandedStage.isShowing(); }
    public boolean isCompactShowing() { return isExpandedShowing(); }

    public Stage getExpandedStage() { return expandedStage; }
    private Stage compactStage;
    public Stage getCompactStage() { return compactStage; }
    public void setCompactStage(Stage compactStage) { this.compactStage = compactStage; }

    private void centerOnPrimaryScreen(Stage stage) {
        try {
            javafx.geometry.Rectangle2D b = javafx.stage.Screen.getPrimary().getVisualBounds();
            stage.setX(b.getMinX() + (b.getWidth() - stage.getWidth()) / 2);
            stage.setY(b.getMinY() + (b.getHeight() - stage.getHeight()) / 2);
        } catch (Exception ignored) {}
    }
}
