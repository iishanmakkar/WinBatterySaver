package com.batterysaver.view;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.service.*;
import com.batterysaver.util.PortableMode;
import com.batterysaver.view.shell.CustomTitleBar;
import com.batterysaver.viewmodel.MainViewModel;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import com.batterysaver.service.BatteryOptimizerService;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.util.Duration;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * Expanded view: 640x520, borderless with CustomTitleBar, TabPane for Status/Health/Processes/Settings/About.
 */
public class ExpandedView {

    private Stage stage;
    private final MainViewModel vm;
    private final BatteryHistoryService historyService;
    private final BatteryHealthService healthService;
    private final Runnable onCloseToTray;
    private final Runnable onRequestCompact;

    private LineChart<String, Number> chart;
    private ComboBox<String> rangeBox;
    private Label chartInfo;

    public ExpandedView(MainViewModel vm,
                        BatteryHistoryService historyService,
                        BatteryHealthService healthService,
                        Runnable onCloseToTray,
                        Runnable onRequestCompact) {
        this.vm = vm;
        this.historyService = historyService;
        this.healthService = healthService;
        this.onCloseToTray = onCloseToTray;
        this.onRequestCompact = onRequestCompact;
    }

    public Stage createStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(AppConstants.WINDOW_TITLE_EXPANDED);
        stage.setWidth(AppConstants.EXPANDED_WIDTH);
        stage.setHeight(AppConstants.EXPANDED_HEIGHT);
        stage.setMinWidth(600);
        stage.setMinHeight(480);
        stage.setAlwaysOnTop(false);

        BorderPane root = new BorderPane();
        root.getStyleClass().addAll("expanded-root", "root-container");
        root.setStyle("-fx-background-radius: 12; -fx-border-color: -wbs-border; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 2);");
        // Ensure TabPane content area is tinted, not pure white, for card contrast
        

        CustomTitleBar titleBar = new CustomTitleBar(stage, AppConstants.WINDOW_TITLE_EXPANDED,
                () -> { if (onCloseToTray != null) onCloseToTray.run(); else stage.hide(); },
                () -> { if (onCloseToTray != null) onCloseToTray.run(); else stage.hide(); },
                true);
        root.setTop(titleBar);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("wbs-tabs");
        tabs.setTabMinWidth(90);

        tabs.getTabs().addAll(
                createStatusTab(),
                createHealthTab(),
                createProcessesTab(),
                createSettingsTab(),
                createAboutTab()
        );

        root.setCenter(tabs);

        // Bottom bar: version info
        HBox bottom = new HBox(10);
        bottom.setPadding(new Insets(6, 10, 8, 10));
        bottom.setAlignment(Pos.CENTER_RIGHT);
        Label ver = new Label(AppConstants.APP_FULL_NAME + " v" + AppConstants.VERSION);
        ver.getStyleClass().add("muted");
        ver.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");
        bottom.getChildren().add(ver);
        root.setBottom(bottom);

        Scene scene = new Scene(root, AppConstants.EXPANDED_WIDTH, AppConstants.EXPANDED_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        String themeCss = "Light".equalsIgnoreCase(vm.getConfig().theme) ? "/styles/Light.css" : "/styles/Dark.css";
        scene.getStylesheets().addAll(
                res("/styles/theme.css"),
                res(themeCss)
        );
        scene.getStylesheets().removeIf(String::isEmpty);

        try {
            var icon16 = com.batterysaver.util.IconUtil.createFxBatteryIcon(16);
            var icon32 = com.batterysaver.util.IconUtil.createFxBatteryIcon(32);
            var icon64 = com.batterysaver.util.IconUtil.createFxBatteryIcon(64);
            if (icon16 != null) stage.getIcons().add(icon16);
            if (icon32 != null) stage.getIcons().add(icon32);
            if (icon64 != null) stage.getIcons().add(icon64);
        } catch (Exception ignored) {}

        stage.setScene(scene);
        return stage;
    }

    private String res(String path) {
        var url = getClass().getResource(path);
        return url != null ? url.toExternalForm() : "";
    }

    private Tab createStatusTab() {
        Tab tab = new Tab("Status");
        tab.setClosable(false);

        VBox box = new VBox(12);
        box.setPadding(new Insets(14));

        // Top dashboard: battery ring + stats
        HBox top = new HBox(16);
        top.setAlignment(Pos.CENTER_LEFT);

        // Large battery ring 72x72
        StackPane ringPane = new StackPane();
        ringPane.setPrefSize(72, 72); ringPane.setMinSize(72, 72); ringPane.setMaxSize(72, 72);
        Circle track = new Circle(30); track.getStyleClass().add("battery-ring-track"); track.setFill(Color.TRANSPARENT); track.setStrokeWidth(6);
        Arc arc = new Arc(0,0,30,30,90,0); arc.setType(ArcType.OPEN); arc.setFill(Color.TRANSPARENT); arc.setStrokeWidth(6); arc.getStyleClass().add("battery-ring-high"); arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        Label pctLabel = new Label("--");
        pctLabel.getStyleClass().add("status-pct"); pctLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        StackPane arcStack = new StackPane(track, arc, pctLabel);
        ringPane.getChildren().add(arcStack);

        VBox stats = new VBox(4);
        stats.setAlignment(Pos.CENTER_LEFT);
        Label batLabel = new Label(); batLabel.getStyleClass().add("card-title");
        Label acLabel = new Label(); acLabel.setStyle("-fx-font-size: 12px;");
        Label drainLabel = new Label(); drainLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -wbs-text-muted;");
        Label timeLabel = new Label();
        batLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            int p = vm.batteryPercentProperty().get();
            return "Battery: " + (p < 0 ? "-" : p + "%");
        }, vm.batteryPercentProperty()));
        acLabel.textProperty().bind(Bindings.createStringBinding(() -> vm.acOnlineProperty().get() ? "AC: online" : "AC: offline", vm.acOnlineProperty()));
        drainLabel.textProperty().bind(vm.drainLabelProperty());
        // Ring binding
        vm.batteryPercentProperty().addListener((o, old, p) -> updateRing(arc, pctLabel, p.intValue()));
        vm.chargingProperty().addListener((o, old, ch) -> updateRing(arc, pctLabel, vm.batteryPercentProperty().get()));
        updateRing(arc, pctLabel, vm.batteryPercentProperty().get());

        // Power Saver toggle - real-time poll drives state
        Button saverBtn = new Button("Power Saver");
        saverBtn.setMinWidth(130);
        saverBtn.getStyleClass().add("primary-btn");
        // Update visually based on state
        Runnable updateSaverBtn = () -> {
            if (vm.powerSaverOnProperty().get()) {
                saverBtn.setText("Power Saver (ON)");
                saverBtn.setStyle("-fx-background-color: -wbs-accent-good; -fx-text-fill: black; -fx-font-weight: bold;");
            } else {
                saverBtn.setText("Power Saver (OFF)");
                saverBtn.setStyle(""); // reset to default primary-btn
            }
        };
        vm.powerSaverOnProperty().addListener((o, old, v) -> updateSaverBtn.run());
        updateSaverBtn.run();
        
        saverBtn.setOnAction(e -> {
            try {
                vm.togglePowerSaver();
            } catch (SecurityException se) {
                Alert a = new Alert(Alert.AlertType.WARNING, se.getMessage(), ButtonType.OK);
                a.setHeaderText("Power plan restricted");
                a.setContentText(se.getMessage());
                a.showAndWait();
            } catch (Exception ex) {
                Alert a = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK);
                a.setHeaderText("Power Saver toggle failed");
                a.showAndWait();
            }
        });

        stats.getChildren().addAll(batLabel, acLabel, drainLabel, saverBtn);
        top.getChildren().addAll(ringPane, stats);

        // Card wrapper for top
        VBox topCard = new VBox(top);
        topCard.getStyleClass().add("card");
        topCard.setPadding(new Insets(12));

        // EnergyStar EcoQoS Card
        VBox ecoCard = new VBox(8);
        ecoCard.getStyleClass().add("card");
        ecoCard.setPadding(new Insets(12));
        Label ecoTitle = new Label("⚡ EnergyStar EcoQoS Background Throttling");
        ecoTitle.getStyleClass().add("card-title");
        Label ecoDesc = new Label("Automatically throttles background applications to Windows Efficiency Mode (green leaf in Task Manager) when they lose focus.");
        ecoDesc.setWrapText(true);
        ecoDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");

        HBox ecoRow = new HBox(12);
        ecoRow.setAlignment(Pos.CENTER_LEFT);
        Button ecoToggle = new Button();
        ecoToggle.getStyleClass().add("primary-btn");
        Runnable updateEcoBtn = () -> {
            if (vm.ecoQosEnabledProperty().get()) {
                ecoToggle.setText("EcoQoS ON");
                ecoToggle.setStyle("-fx-background-color: -wbs-accent-good; -fx-text-fill: black; -fx-font-weight: bold;");
            } else {
                ecoToggle.setText("EcoQoS OFF");
                ecoToggle.setStyle(""); // reset
            }
        };
        vm.ecoQosEnabledProperty().addListener((o, old, v) -> updateEcoBtn.run());
        updateEcoBtn.run();
        
        ecoToggle.setOnAction(e -> vm.toggleEcoQos(!vm.ecoQosEnabledProperty().get()));

        Label ecoCountLabel = new Label();
        ecoCountLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -wbs-accent-good;");
        ecoCountLabel.textProperty().bind(Bindings.createStringBinding(
                () -> vm.ecoQosEnabledProperty().get() ? vm.throttledCountProperty().get() + " background processes throttled in real-time" : "EcoQoS Paused",
                vm.ecoQosEnabledProperty(), vm.throttledCountProperty()
        ));

        ecoRow.getChildren().addAll(ecoToggle, ecoCountLabel);
        ecoCard.getChildren().addAll(ecoTitle, ecoDesc, ecoRow);

        // Optimize card
        VBox optimizeCard = new VBox(8);
        optimizeCard.getStyleClass().add("card");
        optimizeCard.setPadding(new Insets(12));
        Label optTitle = new Label("One-Click RAM & Power Optimizer");
        optTitle.getStyleClass().add("card-title");
        Label optDesc = new Label("Enable Power Saver, lower brightness to 40%, and clear cached RAM. Frees RAM without closing apps.");
        optDesc.setWrapText(true);
        optDesc.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");
        BatteryOptimizerService optimizer = new BatteryOptimizerService();
        Label optResult = new Label("");
        optResult.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-accent-good;");
        optResult.setWrapText(true);
        Label drainerLabel = new Label(optimizer.topDrainerSuggestion());
        drainerLabel.getStyleClass().add("banner-warn");
        drainerLabel.setStyle("-fx-font-size: 10px;");
        drainerLabel.setWrapText(true);
        drainerLabel.setMaxWidth(Double.MAX_VALUE);
        // Refresh drainer every 10s
        Timeline drainerTl = new Timeline(new KeyFrame(Duration.seconds(10), ev -> drainerLabel.setText(optimizer.topDrainerSuggestion())));
        drainerTl.setCycleCount(Timeline.INDEFINITE);
        drainerTl.play();
        Button optBtn = new Button("Optimize Now - Save Battery");
        optBtn.getStyleClass().add("primary-btn");
        optBtn.setMaxWidth(Double.MAX_VALUE);
        optBtn.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8;");
        optBtn.setOnAction(e -> {
            optBtn.setDisable(true);
            optBtn.setText("Optimizing...");
            optResult.setText("Running - trimming working sets (may take 2-4s)...");
            new Thread(() -> {
                BatteryOptimizerService.Result r = optimizer.optimize(true, true, true);
                Platform.runLater(() -> {
                    optBtn.setDisable(false);
                    optBtn.setText("Optimize Now - Save Battery");
                    optResult.setText(r.summary() + " | " + optimizer.topDrainerSuggestion() + " | Battery drain dropping.");
                    try { vm.togglePowerSaver(); } catch (Exception ignored) {}
                    refreshChart();
                });
            }, "optimize-expanded").start();
        });
        HBox optBtnRow = new HBox(optBtn);
        optBtnRow.setAlignment(Pos.CENTER);
        optimizeCard.getChildren().addAll(optTitle, optDesc, drainerLabel, optBtnRow, optResult);

        // Chart + range selector
        HBox chartHeader = new HBox(10);
        chartHeader.setAlignment(Pos.CENTER_LEFT);
        Label chartTitle = new Label("Battery History");
        chartTitle.getStyleClass().add("card-title");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        rangeBox = new ComboBox<>(FXCollections.observableArrayList("2h", "1h", "6h", "24h", "7d"));
        rangeBox.setValue("2h");
        rangeBox.setPrefWidth(80);
        chartHeader.getChildren().addAll(chartTitle, sp, new Label("Range:"), rangeBox);

        BatteryHistoryChart chartBuilder = new BatteryHistoryChart();
        List<BatteryHistoryService.Entry> initial = vm.getHistoryEntriesForRange("2h");
        chart = chartBuilder.build(initial, 60);
        chart.setPrefHeight(200);
        VBox.setVgrow(chart, Priority.ALWAYS);

        chartInfo = new Label();
        chartInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");
        chartInfo.setWrapText(true);
        updateChartInfo(initial, "2h");

        rangeBox.setOnAction(e -> refreshChart());

        Button refreshChartBtn = new Button("Refresh");
        refreshChartBtn.getStyleClass().add("secondary-btn");
        refreshChartBtn.setOnAction(e -> refreshChart());

        Timeline autoRefresh = new Timeline(new KeyFrame(Duration.seconds(60), ev -> {
            if (chart != null && chart.getScene() != null && chart.getScene().getWindow() != null && chart.getScene().getWindow().isShowing()) {
                refreshChart();
            }
        }));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();

        VBox chartCard = new VBox(8, chartHeader, chart, chartInfo, refreshChartBtn);
        chartCard.getStyleClass().add("card");
        chartCard.setPadding(new Insets(12));
        VBox.setVgrow(chartCard, Priority.ALWAYS);

        box.getChildren().addAll(topCard, ecoCard, optimizeCard, chartCard);
        // Scroll if needed
        ScrollPane spn = new ScrollPane(box);
        spn.setFitToWidth(true);
        spn.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        tab.setContent(spn);
        return tab;
    }

    private void updateRing(Arc arc, Label pctLabel, int pct) {
        if (pct < 0 || pct > 100) {
            pctLabel.setText("--");
            arc.setLength(0);
            arc.getStyleClass().setAll("battery-ring-unknown");
            return;
        }
        pctLabel.setText(pct + "%");
        double len = pct * 3.6;
        arc.setLength(-len);
        String cls = pct < 20 ? "battery-ring-low" : pct < 50 ? "battery-ring-medium" : "battery-ring-high";
        arc.getStyleClass().setAll(cls);
    }

    private void refreshChart() {
        if (chart == null || rangeBox == null) return;
        String r = rangeBox.getValue() != null ? rangeBox.getValue() : "2h";
        // Off FX thread: history file I/O is blocking, do in background
        new Thread(() -> {
            List<BatteryHistoryService.Entry> entries = vm.getHistoryEntriesForRange(r);
            Platform.runLater(() -> {
                updateChartInfo(entries, r);
                BatteryHistoryChart builder = new BatteryHistoryChart();
                LineChart<String, Number> fresh = builder.build(entries, 60);
                chart.getData().clear();
                if (fresh.getData() != null && !fresh.getData().isEmpty()) {
                    for (var s : fresh.getData()) {
                        XYChart.Series<String, Number> ns = new XYChart.Series<>();
                        ns.setName(s.getName());
                        for (var d : s.getData()) {
                            ns.getData().add(new XYChart.Data<>(d.getXValue(), d.getYValue()));
                        }
                        chart.getData().add(ns);
                    }
                }
                if (entries.isEmpty()) {
                    chart.setTitle("No history yet - collecting...");
                } else {
                    chart.setTitle(null);
                }
            });
        }, "chart-refresh").start();
    }

    private void updateChartInfo(List<BatteryHistoryService.Entry> entries, String range) {
        if (chartInfo == null) return;
        if (entries == null || entries.isEmpty()) {
            chartInfo.setText("No data for " + range + " - history will appear after a few minutes. CSV: " + historyService.getHistoryFile());
            return;
        }
        java.time.Duration span = java.time.Duration.between(entries.get(0).timestamp(), entries.get(entries.size()-1).timestamp());
        long mins = span.toMinutes();
        String spanStr = mins < 60 ? mins + " min" : (mins/60) + "h " + (mins%60) + "m";
        chartInfo.setText(entries.size() + " points, span " + spanStr + " for range " + range + " - " + (entries.size() < 60 ? "all points shown" : "downsampled to 60") + ". File: " + historyService.getHistoryFile().getFileName());
    }

    private Tab createHealthTab() {
        Tab tab = new Tab("Health");
        tab.setClosable(false);
        VBox box = new VBox(12);
        box.setPadding(new Insets(14));

        Label title = new Label("Battery Health");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label healthLabel = new Label(); healthLabel.textProperty().bind(vm.healthTextProperty());
        healthLabel.setWrapText(true);
        healthLabel.getStyleClass().add("card");

        Label detail = new Label(); detail.textProperty().bind(vm.healthDetailProperty());
        detail.setWrapText(true);
        detail.setStyle("-fx-font-size: 11px; -fx-font-family: 'Consolas', monospace;");
        VBox detailCard = new VBox(detail);
        detailCard.getStyleClass().add("card");
        detailCard.setPadding(new Insets(10));
        // Show detail only when not empty
        detailCard.managedProperty().bind(detail.textProperty().isNotEmpty());
        detailCard.visibleProperty().bind(detail.textProperty().isNotEmpty());

        Label standby = new Label(); standby.textProperty().bind(vm.standbyNoteProperty());
        standby.setWrapText(true);
        standby.getStyleClass().add("banner-warn");
        standby.setStyle("-fx-font-size: 11px;");
        standby.managedProperty().bind(standby.textProperty().isNotEmpty());
        standby.visibleProperty().bind(standby.textProperty().isNotEmpty());

        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(24, 24);
        pi.visibleProperty().bind(vm.healthLoadingProperty());
        pi.managedProperty().bind(vm.healthLoadingProperty());

        Button refresh = new Button("Refresh health");
        refresh.getStyleClass().add("secondary-btn");
        refresh.setOnAction(e -> vm.refreshHealth());
        refresh.disableProperty().bind(vm.healthLoadingProperty());

        HBox btnRow = new HBox(10, refresh, pi);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // Battery health improvements - how to improve, cross-OEM tips
        VBox tipsCard = new VBox(6);
        tipsCard.getStyleClass().add("card");
        tipsCard.setPadding(new Insets(10));
        Label tipsTitle = new Label("How to Improve Battery Health");
        tipsTitle.getStyleClass().add("card-title");
        Label t1 = new Label("• Keep charge 20-80% (set limit 80% in Settings) - avoids stress, doubles cycle life vs 0-100%");
        Label t2 = new Label("• Avoid heat >35C - heat is #1 killer (don't game on cushion, keep vents clear)");
        Label t3 = new Label("• Calibrate monthly: charge to 100%, discharge to 5%, then full charge - fixes % estimation");
        Label t4 = new Label("• When unplugged, WBS auto-enables Power Saver + dims to 40% - saves ~15% drain");
        Label t5 = new Label("• EnergyStar EcoQoS automatically throttles background apps - saves 15-30% battery");
        for (Label l : new Label[]{t1,t2,t3,t4,t5}) { l.setWrapText(true); l.setStyle("-fx-font-size: 11px; -fx-text-fill: -wbs-text;"); }
        tipsCard.getChildren().addAll(tipsTitle, t1, t2, t3, t4, t5);

        // Live health bar in Health tab as well (second bar)
        VBox liveHealthCard = new VBox(6);
        liveHealthCard.getStyleClass().add("card");
        liveHealthCard.setPadding(new Insets(10));
        Label liveTitle = new Label("Live Health");
        liveTitle.setStyle("-fx-font-weight: bold;");
        StackPane hBar = new StackPane();
        hBar.setPrefHeight(12); hBar.setStyle("-fx-background-color: -wbs-card-bg; -fx-background-radius: 6;");
        Region hTrack = new Region(); hTrack.setStyle("-fx-background-color: -wbs-card-bg; -fx-background-radius: 6;"); hTrack.setPrefHeight(12);
        Region hFill = new Region(); hFill.setStyle("-fx-background-color: -wbs-accent-good; -fx-background-radius: 6;"); hFill.setPrefHeight(12);
        StackPane.setAlignment(hFill, Pos.CENTER_LEFT);
        hBar.getChildren().addAll(hTrack, hFill);
        Label hLeft = new Label("Health: --"); Label hRight = new Label("Cycles --");
        HBox hLabels = new HBox(8, hLeft, new Region(), hRight);
        HBox.setHgrow(hLabels.getChildren().get(1), Priority.ALWAYS);
        liveHealthCard.getChildren().addAll(liveTitle, hBar, hLabels);
        // Bind to VM health
        vm.healthPercentProperty().addListener((o, old, hp) -> {
            int v = hp.intValue();
            if (v < 0) { hLeft.setText("Health: --"); hFill.setPrefWidth(0); }
            else {
                hLeft.setText("Health: " + v + "%");
                String cycles = vm.healthShortProperty().get();
                hRight.setText(cycles != null && cycles.contains("Cycles") ? cycles.substring(cycles.indexOf("Cycles")) : "Cycles --");
                double total = 700; // approx card width
                try { total = liveHealthCard.getWidth() > 0 ? liveHealthCard.getWidth() - 24 : 700; } catch (Exception ignored) {}
                hFill.setPrefWidth(Math.max(0, total * v / 100.0));
                String col = v < 60 ? "-wbs-accent-crit" : v < 80 ? "-wbs-accent-warn" : "-wbs-accent-good";
                hFill.setStyle("-fx-background-color: " + col + "; -fx-background-radius: 6;");
            }
        });

        box.getChildren().addAll(title, healthLabel, detailCard, liveHealthCard, standby, tipsCard, btnRow);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        tab.setContent(sp);

        // Initial load
        Platform.runLater(() -> vm.refreshHealth());

        return tab;
    }

    private Tab createProcessesTab() {
        Tab tab = new Tab("Processes");
        tab.setClosable(false);
        VBox box = new VBox(10);
        box.setPadding(new Insets(14));

        Label disclaimer = new Label("Estimated impact - Windows does not expose per-app battery draw.");
        disclaimer.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");
        disclaimer.setWrapText(true);

        TableView<MainViewModel.ProcessRow> table = new TableView<>();
        table.setItems(vm.getProcessRows());
        table.setPlaceholder(new Label("Collecting CPU data (30s window)..."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(320);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<MainViewModel.ProcessRow, String> nameCol = new TableColumn<>("Process");
        nameCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().name()));
        nameCol.setSortable(true);
        nameCol.setPrefWidth(420);

        TableColumn<MainViewModel.ProcessRow, Double> cpuCol = new TableColumn<>("CPU %");
        cpuCol.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(cd.getValue().cpu()));
        cpuCol.setSortable(true);
        cpuCol.setPrefWidth(110);
        cpuCol.setMaxWidth(120);
        cpuCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        cpuCol.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(cpuCol);

        table.getColumns().addAll(nameCol, cpuCol);

        Button refresh = new Button("Refresh now");
        refresh.setOnAction(e -> {
            var top = ProcessUsageService.getTopCpuProcesses(5);
            vm.getProcessRows().clear();
            top.forEach((k,v) -> vm.getProcessRows().add(new MainViewModel.ProcessRow(k,v)));
            table.sort();
            if (top.isEmpty()) {
                table.setPlaceholder(new Label("No significant CPU usage (or collecting...)"));
            }
        });

        Label hint = new Label("Top 5 CPU over 30s window, 5s samples. Refresh to re-sort.");
        hint.setStyle("-fx-font-size: 9px; -fx-text-fill: -wbs-text-muted;");

        box.getChildren().addAll(disclaimer, table, hint, refresh);
        // Wrap in scroll to prevent overflow on small height
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setStyle("-fx-background: transparent;");
        tab.setContent(sp);
        return tab;
    }

    private Tab createSettingsTab() {
        Tab tab = new Tab("Settings");
        tab.setClosable(false);
        VBox outer = new VBox(16);
        outer.setPadding(new Insets(14));
        outer.setStyle("-fx-background-color: transparent;");

        SettingsService.Config cfg = vm.getConfig();
        boolean isPortable = PortableMode.isPortable();

        Label pageTitle = new Label("Settings");
        pageTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        outer.getChildren().add(pageTitle);

        // --- Charge limit card ---
        VBox chargeCard = new VBox(8);
        chargeCard.getStyleClass().add("card");
        chargeCard.setPadding(new Insets(12));
        Label chargeTitle = new Label("Charge Limit");
        chargeTitle.setStyle("-fx-font-weight: bold;");
        HBox chargeRow = new HBox(10);
        chargeRow.setAlignment(Pos.CENTER_LEFT);
        Label chargeLbl = new Label("Limit:");
        chargeLbl.setMinWidth(55);
        Slider chargeSlider = new Slider(60, 100, cfg.chargeLimitPercent);
        chargeSlider.setShowTickLabels(true); chargeSlider.setShowTickMarks(true);
        chargeSlider.setMajorTickUnit(10); chargeSlider.setMinorTickCount(1); chargeSlider.setSnapToTicks(true);
        chargeSlider.setPrefWidth(280); HBox.setHgrow(chargeSlider, Priority.ALWAYS);
        Label chargeVal = new Label(String.valueOf((int)chargeSlider.getValue()));
        chargeVal.setStyle("-fx-font-weight: bold; -fx-min-width: 35;");
        chargeVal.setMinWidth(35);
        chargeSlider.valueProperty().addListener((o,old,v)-> chargeVal.setText(String.valueOf(v.intValue())));
        chargeRow.getChildren().addAll(chargeLbl, chargeSlider, chargeVal);
        CheckBox chargeEnabled = new CheckBox("Notify when battery reaches limit (toast)");
        chargeEnabled.setSelected(cfg.chargeLimitEnabled);
        chargeSlider.disableProperty().bind(chargeEnabled.selectedProperty());
        Label chargeHint = new Label("Default 80% - helps preserve long-term health. No OEM API, just a reminder.");
        chargeHint.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;"); chargeHint.setWrapText(true);
        chargeCard.getChildren().addAll(chargeTitle, chargeRow, chargeEnabled, chargeHint);
        outer.getChildren().add(chargeCard);

        // --- Hotkey card ---
        VBox hotkeyCard = new VBox(8);
        hotkeyCard.getStyleClass().add("card");
        hotkeyCard.setPadding(new Insets(12));
        Label hkTitle = new Label("Hotkey");
        hkTitle.setStyle("-fx-font-weight: bold;");
        HBox hkRow = new HBox(10);
        hkRow.setAlignment(Pos.CENTER_LEFT);
        Label hkLbl = new Label("Toggle Power Saver:");
        hkLbl.setMinWidth(130);
        Label hkVal = new Label(SettingsService.hotkeyToString(cfg.hotkeyModifiers, cfg.hotkeyVk));
        hkVal.setStyle("-fx-font-family: 'Consolas', monospace; -fx-background-color: -wbs-input-bg; -fx-border-color: -wbs-border; -fx-padding: 5 10; -fx-background-radius: 6; -fx-border-radius: 6;");
        hkVal.setMinWidth(110);
        Button hkChange = new Button("Change...");
        hkChange.setMinWidth(90);
        hkRow.getChildren().addAll(hkLbl, hkVal, hkChange);
        Label hkHint = new Label("Default Ctrl+Alt+B. Requires restart to apply after saving.");
        hkHint.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;"); hkHint.setWrapText(true);
        final int[] mods = {cfg.hotkeyModifiers}; final int[] vk = {cfg.hotkeyVk};
        hkChange.setOnAction(e-> {
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle("Change hotkey"); dlg.setHeaderText("Press new hotkey (e.g. Ctrl+Alt+B)");
            Label instr = new Label("Click field and press keys. Current: "+SettingsService.hotkeyToString(mods[0],vk[0]));
            instr.setWrapText(true);
            TextField cap = new TextField(); cap.setPromptText("Press hotkey here"); cap.setEditable(false);
            cap.setOnKeyPressed(ke->{
                int m=0;
                if(ke.isControlDown()) m|=0x0002;
                if(ke.isAltDown()) m|=0x0001;
                if(ke.isShiftDown()) m|=0x0004;
                if(ke.isMetaDown()) m|=0x0008;
                if(m==0) instr.setText("Include at least Ctrl or Alt");
                else { mods[0]=m; vk[0]=ke.getCode().getCode(); cap.setText(SettingsService.hotkeyToString(m, vk[0])); instr.setText("Will save: "+cap.getText()); }
                ke.consume();
            });
            VBox dbox=new VBox(8,instr,cap); dbox.setPadding(new Insets(12));
            dlg.getDialogPane().setContent(dbox);
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
            dlg.showAndWait().ifPresent(bt->{ if(bt==ButtonType.OK) hkVal.setText(SettingsService.hotkeyToString(mods[0],vk[0])); });
        });
        hotkeyCard.getChildren().addAll(hkTitle, hkRow, hkHint);
        outer.getChildren().add(hotkeyCard);

        // --- Startup + Idle card ---
        VBox sysCard = new VBox(10);
        sysCard.getStyleClass().add("card");
        sysCard.setPadding(new Insets(12));
        Label sysTitle = new Label("System & Auto-Start Settings");
        sysTitle.getStyleClass().add("card-title");
        HBox startupRow = new HBox(10);
        startupRow.setAlignment(Pos.CENTER_LEFT);
        CheckBox startupBox = new CheckBox("Auto-start when laptop boots (run at startup)");
        startupBox.setStyle("-fx-font-weight: bold;");
        StartupService ss = new StartupService();
        try{ startupBox.setSelected(cfg.autoStart || ss.isEnabled()); }catch(Exception ignored){}
        Label startupNote = new Label();
        startupNote.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");
        if(isPortable){
            startupBox.setDisable(true); startupBox.setSelected(false);
            startupNote.setText("Disabled in portable mode (config next to exe)");
        } else {
            startupNote.setText("Starts minimized to system tray on Windows boot.");
        }
        startupNote.setWrapText(true);
        startupRow.getChildren().addAll(startupBox, startupNote);
        HBox.setHgrow(startupNote, Priority.ALWAYS);

        HBox idleRow = new HBox(10);
        idleRow.setAlignment(Pos.CENTER_LEFT);
        CheckBox idleBox = new CheckBox("Idle dimming");
        idleBox.setSelected(cfg.idleDimmingEnabled);
        idleBox.setMinWidth(110);
        Label idleLbl = new Label("after");
        Spinner<Integer> idleSp = new Spinner<>(1, 30, cfg.idleMinutes);
        idleSp.setPrefWidth(75); idleSp.setEditable(true);
        idleSp.disableProperty().bind(idleBox.selectedProperty());
        Label idleMinLbl = new Label("minutes");
        idleRow.getChildren().addAll(idleBox, idleLbl, idleSp, idleMinLbl);

        sysCard.getChildren().addAll(sysTitle, startupRow, idleRow);
        outer.getChildren().add(sysCard);

        // --- EnergyStar EcoQoS Card ---
        VBox ecoSettingsCard = new VBox(10);
        ecoSettingsCard.getStyleClass().add("card");
        ecoSettingsCard.setPadding(new Insets(12));
        Label ecoStTitle = new Label("EnergyStar EcoQoS Throttling");
        ecoStTitle.getStyleClass().add("card-title");
        CheckBox ecoStBox = new CheckBox("Enable real-time background EcoQoS throttling (Efficiency Mode)");
        ecoStBox.setSelected(cfg.ecoQosEnabled);

        HBox ecoWlRow = new HBox(10);
        ecoWlRow.setAlignment(Pos.CENTER_LEFT);
        Label ecoWlLbl = new Label("Excluded Apps:");
        ecoWlLbl.setMinWidth(110);
        TextField ecoWlField = new TextField(cfg.ecoQosWhitelistStr != null ? cfg.ecoQosWhitelistStr : "");
        ecoWlField.setPromptText("exe names separated by comma (e.g. game.exe, obs64.exe)");
        HBox.setHgrow(ecoWlField, Priority.ALWAYS);
        ecoWlRow.getChildren().addAll(ecoWlLbl, ecoWlField);

        Label ecoStHint = new Label("EnergyStar mechanism: background apps are throttled to low power CPU states. Add mouse/game exes to exclusion list if lag occurs.");
        ecoStHint.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;"); ecoStHint.setWrapText(true);
        ecoSettingsCard.getChildren().addAll(ecoStTitle, ecoStBox, ecoWlRow, ecoStHint);
        outer.getChildren().add(ecoSettingsCard);

        // --- Updates + Theme card ---
        VBox updCard = new VBox(10);
        updCard.getStyleClass().add("card");
        updCard.setPadding(new Insets(12));
        Label updTitle = new Label("Updates & Appearance");
        updTitle.getStyleClass().add("card-title");
        HBox updRow = new HBox(10);
        updRow.setAlignment(Pos.CENTER_LEFT);
        CheckBox updBox = new CheckBox("Check for updates on startup");
        updBox.setSelected(cfg.updateCheckEnabled);
        updBox.setMinWidth(210);
        TextField repoField = new TextField(cfg.updateRepoSlug);
        repoField.setPromptText("user/repo"); repoField.setPrefWidth(220); HBox.setHgrow(repoField, Priority.ALWAYS);
        repoField.disableProperty().bind(updBox.selectedProperty());
        updRow.getChildren().addAll(updBox, repoField);
        Label updHint = new Label("Disabled by default - no outbound request unless enabled (0 cost). Requires repo slug like user/BatterySaver.");
        updHint.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;"); updHint.setWrapText(true);
        HBox themeRow = new HBox(10);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        Label themeLbl = new Label("Theme:");
        themeLbl.setMinWidth(55);
        ComboBox<String> themeBox = new ComboBox<>(FXCollections.observableArrayList("Dark", "Light"));
        themeBox.setValue(cfg.theme != null ? cfg.theme : "Dark");
        themeBox.setPrefWidth(120);
        themeBox.setOnAction(e-> {
            Scene sc = outer.getScene();
            if(sc==null) return;
            sc.getStylesheets().removeIf(s->s.contains("Light.css")||s.contains("Dark.css"));
            String css = "Light".equalsIgnoreCase(themeBox.getValue()) ? "/styles/Light.css" : "/styles/Dark.css";
            var url=getClass().getResource(css);
            if(url!=null) sc.getStylesheets().add(url.toExternalForm());
        });
        themeRow.getChildren().addAll(themeLbl, themeBox);
        updCard.getChildren().addAll(updTitle, updRow, updHint, themeRow);
        outer.getChildren().add(updCard);

        if(isPortable){
            Label port = new Label("Portable mode - config next to exe, startup disabled");
            port.getStyleClass().add("banner-success");
            port.setWrapText(true); port.setMaxWidth(Double.MAX_VALUE);
            outer.getChildren().add(port);
        }

        // Save row
        HBox saveRow = new HBox(12);
        saveRow.setAlignment(Pos.CENTER_LEFT);
        saveRow.setPadding(new Insets(4,0,0,0));
        Button save = new Button("Save settings");
        save.getStyleClass().add("primary-btn");
        save.setStyle("-fx-font-weight: bold;");
        save.setMinWidth(120);
        Label status = new Label(); status.setStyle("-fx-text-fill: -wbs-accent-good; -fx-font-size: 11px;"); status.setWrapText(true); HBox.setHgrow(status, Priority.ALWAYS);
        saveRow.getChildren().addAll(save, status);

        save.setOnAction(ev->{
            cfg.chargeLimitPercent=(int)chargeSlider.getValue();
            cfg.chargeLimitEnabled=chargeEnabled.isSelected();
            cfg.hotkeyModifiers=mods[0]; cfg.hotkeyVk=vk[0];
            cfg.idleDimmingEnabled=idleBox.isSelected(); cfg.idleMinutes=idleSp.getValue();
            cfg.updateCheckEnabled=updBox.isSelected(); cfg.updateRepoSlug=repoField.getText().trim();
            cfg.theme = themeBox.getValue();
            cfg.ecoQosEnabled = ecoStBox.isSelected();
            cfg.ecoQosWhitelistStr = ecoWlField.getText().trim();
            vm.toggleEcoQos(cfg.ecoQosEnabled);
            if(!isPortable){
                try{
                    if(startupBox.isSelected()){
                        String exe=ProcessHandle.current().info().command().orElse("");
                        if(!exe.isBlank()) ss.enable(exe);
                    } else ss.disable();
                }catch(Exception ex){ new Alert(Alert.AlertType.WARNING, ex.getMessage()).showAndWait(); }
            }
            cfg.autoStart=startupBox.isSelected();
            SettingsService.save(cfg);
            vm.updateConfig(cfg);
            status.setText("Saved to "+PortableMode.getConfigPath()+" - restart to apply hotkey");
        });

        outer.getChildren().add(saveRow);

        ScrollPane sp = new ScrollPane(outer);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        sp.setPadding(new Insets(0));

        tab.setContent(sp);
        return tab;
    }

    private Tab createAboutTab() {
        Tab tab = new Tab("About");
        tab.setClosable(false);
        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setAlignment(Pos.TOP_LEFT);

        Label title = new Label(AppConstants.APP_FULL_NAME);
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label ver = new Label("Version " + AppConstants.VERSION);
        ver.setStyle("-fx-font-size: 12px; -fx-text-fill: -wbs-text-muted;");

        Label desc = new Label("WBS is a cross-OEM Windows battery saver.\nNo vendor drivers required - uses standard Windows APIs\n(GetSystemPowerStatus, powercfg, WMI) and works on Dell, HP, Lenovo, Asus, etc.");
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 11px;");

        Hyperlink gh = new Hyperlink("GitHub: " + AppConstants.GITHUB_REPO);
        gh.setOnAction(e->{
            try{ Desktop.getDesktop().browse(URI.create("https://github.com/"+AppConstants.GITHUB_REPO)); }catch(Exception ex){}
        });
        if(AppConstants.GITHUB_REPO.contains("REPLACE_ME")) gh.setText("GitHub: (configure repo slug in Settings)");

        Label lic = new Label("License: MIT - Free for personal and corporate use (0 cost).\nCredits: Built with JDK 25 + JavaFX 25 + JNA 5.14");
        lic.setWrapText(true);
        lic.setStyle("-fx-font-size: 10px; -fx-text-fill: -wbs-text-muted;");

        Button closeBtn = new Button("Close to tray");
        closeBtn.setOnAction(e-> { if(onCloseToTray!=null) onCloseToTray.run(); });

        box.getChildren().addAll(title, ver, new Separator(), desc, gh, lic, closeBtn);
        tab.setContent(new ScrollPane(box));
        return tab;
    }

    public void show() {
        if (stage == null) createStage();
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    public void hide() { if (stage!=null) stage.hide(); }
    public boolean isShowing() { return stage!=null && stage.isShowing(); }
    public Stage getStage() { return stage; }
}
