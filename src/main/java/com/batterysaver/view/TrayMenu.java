package com.batterysaver.view;

import javafx.event.ActionEvent;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

public class TrayMenu extends Menu {

    public TrayMenu() {
        super("Battery Saver");
    }

    public void setOnToggle(Runnable on, Runnable off) {
        MenuItem toggle = new MenuItem("Toggle Power-Saver");
        toggle.setOnAction(e -> {
            if (isActive()) off.run();
            else on.run();
        });
        getItems().add(toggle);
    }

    public void setOnExit(Runnable exit) {
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> exit.run());
        getItems().add(exitItem);
    }

    private boolean isActive() {
        // placeholder - UI will reference the service directly
        return false;
    }
}