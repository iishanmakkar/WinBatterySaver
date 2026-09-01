package com.batterysaver.view.shell;

import com.batterysaver.constants.AppConstants;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Shared borderless titlebar: [icon] WBS - title -- drag region -- [ _ ] [ ] [ X ]
 * Hover states via CSS. Close minimizes to tray (not exit).
 */
public class CustomTitleBar extends HBox {

    private final Label titleLabel;
    private final Button minBtn;
    private final Button maxBtn;
    private final Button closeBtn;
    private double dragX, dragY;

    public CustomTitleBar(Stage stage, String title, Runnable onMinimizeToTray, Runnable onClose) {
        this(stage, title, onMinimizeToTray, onClose, true);
    }

    public CustomTitleBar(Stage stage, String title, Runnable onMinimizeToTray, Runnable onClose, boolean showMax) {
        getStyleClass().add("titlebar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 10, 4, 10));
        setSpacing(8);
        setPrefHeight(36);
        setMinHeight(36);

        // App glyph (simple battery unicode or label)
        Label icon = new Label("\u25A3"); // placeholder glyph, CSS colors it
        icon.getStyleClass().add("titlebar-icon");
        icon.setStyle("-fx-font-size: 14px; -fx-text-fill: -wbs-accent-good;");

        titleLabel = new Label(title != null ? title : AppConstants.WINDOW_TITLE_EXPANDED);
        titleLabel.getStyleClass().add("titlebar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        minBtn = createBtn("_", "titlebar-min");
        maxBtn = createBtn("\u25A1", "titlebar-max");
        closeBtn = createBtn("\u2715", "titlebar-close");

        minBtn.setOnAction(e -> stage.setIconified(true));
        if (showMax) {
            maxBtn.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        } else {
            maxBtn.setManaged(false);
            maxBtn.setVisible(false);
        }
        // Close -> minimize to tray by default; real exit via tray menu
        closeBtn.setOnAction(e -> {
            if (onMinimizeToTray != null) onMinimizeToTray.run();
            else if (onClose != null) onClose.run();
            else stage.hide();
        });

        getChildren().addAll(icon, titleLabel, spacer, minBtn);
        if (showMax) getChildren().add(maxBtn);
        getChildren().add(closeBtn);

        // Drag-to-move on bar (excluding buttons)
        enableDrag(this, stage);
        // Prevent drag when clicking buttons
        for (Button b : new Button[]{minBtn, maxBtn, closeBtn}) {
            b.setOnMousePressed(e -> e.consume());
            b.setOnMouseDragged(e -> e.consume());
        }
    }

    private Button createBtn(String text, String cls) {
        Button b = new Button(text);
        b.getStyleClass().addAll("titlebar-btn", cls);
        b.setFocusTraversable(false);
        // Big like real Windows 11 - 45x32 hit target, not tiny 28x22
        b.setMinSize(45, 32);
        b.setPrefSize(45, 32);
        b.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        return b;
    }

    public static void enableDrag(javafx.scene.Node dragHandle, Stage stage) {
        final double[] drag = new double[2];
        dragHandle.setOnMousePressed(e -> {
            drag[0] = e.getSceneX();
            drag[1] = e.getSceneY();
        });
        dragHandle.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - drag[0]);
            stage.setY(e.getScreenY() - drag[1]);
        });
    }

    public void setTitle(String t) { titleLabel.setText(t); }
    public Label getTitleLabel() { return titleLabel; }
}
