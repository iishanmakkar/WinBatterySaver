package com.batterysaver.view;

import com.batterysaver.service.BatteryHistoryService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Battery history LineChart with downsample and range handling.
 */
public class BatteryHistoryChart {

    public record HistoryPoint(String timeLabel, int percent, boolean charging) {}

    private static final DateTimeFormatter FMT_HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FMT_MD = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    public LineChart<String, Number> build(List<BatteryHistoryService.Entry> entries) {
        return build(entries, 60);
    }

    public LineChart<String, Number> build(List<BatteryHistoryService.Entry> entries, int maxPoints) {
        // Handle empty
        if (entries == null || entries.isEmpty()) {
            CategoryAxis xAxis = new CategoryAxis();
            xAxis.setLabel("Time");
            NumberAxis yAxis = new NumberAxis(0, 100, 20);
            yAxis.setLabel("Battery %");
            LineChart<String, Number> empty = new LineChart<>(xAxis, yAxis);
            empty.setLegendVisible(false);
            empty.setCreateSymbols(false);
            empty.setAnimated(false);
            empty.setMinHeight(160);
            empty.setPrefHeight(180);
            empty.getStyleClass().add("history-chart");
            empty.setTitle("No history yet - collecting...");
            return empty;
        }
        // Ensure sorted by time
        List<BatteryHistoryService.Entry> sorted = new java.util.ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparing(BatteryHistoryService.Entry::timestamp));

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Time");
        xAxis.setTickLabelRotation(-30);
        xAxis.setTickLabelGap(4);
        NumberAxis yAxis = new NumberAxis(0, 100, 20);
        yAxis.setLabel("Battery %");
        yAxis.setTickUnit(20);
        yAxis.setMinorTickCount(1);

        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setMinHeight(180);
        chart.setPrefHeight(200);
        chart.getStyleClass().add("history-chart");
        // Reduce clutter: only show every Nth label if many points
        List<BatteryHistoryService.Entry> sampled = downsample(sorted, maxPoints);
        boolean withDate = sampled.size() > 24 || isMultiDay(sampled);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Battery");
        for (int i = 0; i < sampled.size(); i++) {
            BatteryHistoryService.Entry e = sampled.get(i);
            String label = formatLabel(e.timestamp(), withDate);
            // Thin labels: only show label for every 3rd point if many points to avoid crowding
            if (sampled.size() > 30 && i % 3 != 0) label = "";
            series.getData().add(new XYChart.Data<>(label, e.percent()));
        }
        chart.getData().add(series);
        // Tooltip on hover is handled by chart, not needed
        return chart;
    }

    private boolean isMultiDay(List<BatteryHistoryService.Entry> entries) {
        if (entries.size() < 2) return false;
        Instant first = entries.get(0).timestamp();
        Instant last = entries.get(entries.size()-1).timestamp();
        return java.time.Duration.between(first, last).toHours() >= 24;
    }

    public static List<BatteryHistoryService.Entry> downsample(List<BatteryHistoryService.Entry> entries, int maxPoints) {
        if (entries.size() <= maxPoints) return entries;
        // Simple uniform downsample
        double step = (double) entries.size() / maxPoints;
        java.util.ArrayList<BatteryHistoryService.Entry> out = new java.util.ArrayList<>();
        for (int i = 0; i < maxPoints; i++) {
            int idx = (int) Math.round(i * step);
            if (idx >= entries.size()) idx = entries.size() - 1;
            out.add(entries.get(idx));
        }
        return out;
    }

    private String formatLabel(Instant ts, boolean withDate) {
        return withDate ? FMT_MD.format(ts) : FMT_HM.format(ts);
    }

    public ObservableList<XYChart.Series<String, Number>> toSeries(List<BatteryHistoryService.Entry> entries, int maxPoints) {
        List<BatteryHistoryService.Entry> sampled = downsample(entries, maxPoints);
        ObservableList<XYChart.Series<String, Number>> list = FXCollections.observableArrayList();
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        for (BatteryHistoryService.Entry e : sampled) {
            s.getData().add(new XYChart.Data<>(formatLabel(e.timestamp(), sampled.size() > 24), e.percent()));
        }
        list.add(s);
        return list;
    }
}
