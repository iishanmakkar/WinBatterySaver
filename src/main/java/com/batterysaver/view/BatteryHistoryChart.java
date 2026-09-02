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
        chart.getData().add(buildSeries(sorted, maxPoints));
        return chart;
    }

    /**
     * Builds the data series with unique category labels. Categories on a
     * CategoryAxis MUST be unique: duplicate values (e.g. blank labels or the
     * same HH:mm stamp) collapse into one x-position and mangle the line, so
     * collisions are disambiguated with trailing spaces (invisible when rendered).
     */
    public XYChart.Series<String, Number> buildSeries(List<BatteryHistoryService.Entry> entries, int maxPoints) {
        List<BatteryHistoryService.Entry> sampled = downsample(entries, maxPoints);
        boolean withDate = sampled.size() > 24 || isMultiDay(sampled);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Battery");
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (BatteryHistoryService.Entry e : sampled) {
            String label = formatLabel(e.timestamp(), withDate);
            while (!used.add(label)) label = label + " ";
            series.getData().add(new XYChart.Data<>(label, e.percent()));
        }
        return series;
    }

    private boolean isMultiDay(List<BatteryHistoryService.Entry> entries) {
        if (entries.size() < 2) return false;
        Instant first = entries.get(0).timestamp();
        Instant last = entries.get(entries.size()-1).timestamp();
        return java.time.Duration.between(first, last).toHours() >= 24;
    }

    public static List<BatteryHistoryService.Entry> downsample(List<BatteryHistoryService.Entry> entries, int maxPoints) {
        if (entries.size() <= maxPoints) return entries;
        // Uniform downsample that always includes BOTH endpoints - the most recent
        // sample matters most on a live battery chart
        double step = (double) (entries.size() - 1) / (maxPoints - 1);
        java.util.ArrayList<BatteryHistoryService.Entry> out = new java.util.ArrayList<>(maxPoints);
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
        ObservableList<XYChart.Series<String, Number>> list = FXCollections.observableArrayList();
        list.add(buildSeries(entries, maxPoints));
        return list;
    }
}
