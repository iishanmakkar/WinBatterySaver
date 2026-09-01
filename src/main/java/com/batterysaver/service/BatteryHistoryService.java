package com.batterysaver.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Session battery log: appends (timestamp, percent, ACLineStatus) to rolling CSV
 * %APPDATA%\BatterySaver\history.csv, capped at 30 days.
 * Provides "battery drained X% in last hour" without admin rights.
 */
public class BatteryHistoryService {
    public record Entry(Instant timestamp, int percent, boolean onAC) {}

    private final Path historyFile;

    public BatteryHistoryService() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) appData = System.getProperty("java.io.tmpdir");
        this.historyFile = Path.of(appData, "BatterySaver", "history.csv");
    }

    // For testing / portable
    public BatteryHistoryService(Path file) { this.historyFile = file; }

    public Path getHistoryFile() { return historyFile; }

    public synchronized void append(int percent, boolean onAC) {
        try {
            Files.createDirectories(historyFile.getParent());
            boolean isNew = !Files.exists(historyFile);
            String line = Instant.now().toString() + "," + percent + "," + (onAC ? 1 : 0) + "\n";
            if (isNew) {
                Files.writeString(historyFile, "timestamp,percent,ac\n" + line, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } else {
                // Throttle: only append if percent changed or >60s since last entry to avoid 5s spam?
                // For simplicity append every call but caller throttles (BatteryStatusService every 60s or on change)
                Files.writeString(historyFile, line, StandardOpenOption.APPEND);
            }
            // Periodic trim: if file >200KB or >5000 lines, trim to 30 days
            try {
                if (Files.size(historyFile) > 300_000) trimTo30Days();
            } catch (IOException ignored) {}
        } catch (IOException e) {
            System.err.println("BatteryHistory append failed: " + e.getMessage());
        }
    }

    public synchronized void trimTo30Days() {
        try {
            if (!Files.exists(historyFile)) return;
            List<String> lines = Files.readAllLines(historyFile);
            if (lines.isEmpty()) return;
            String header = lines.get(0);
            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
            List<String> kept = new ArrayList<>();
            kept.add(header.startsWith("timestamp") ? header : "timestamp,percent,ac");
            int startIdx = header.startsWith("timestamp") ? 1 : 0;
            for (int i = startIdx; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.isEmpty()) continue;
                try {
                    String ts = l.split(",")[0];
                    Instant t = Instant.parse(ts);
                    if (t.isAfter(cutoff)) kept.add(l);
                } catch (Exception ignored) {
                    // malformed line: keep if we can't parse
                    kept.add(l);
                }
            }
            Files.write(historyFile, kept);
        } catch (IOException e) {
            System.err.println("BatteryHistory trim failed: " + e.getMessage());
        }
    }

    public synchronized List<Entry> readAll() {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.exists(historyFile)) return out;
            List<String> lines = Files.readAllLines(historyFile);
            int start = (!lines.isEmpty() && lines.get(0).startsWith("timestamp")) ? 1 : 0;
            for (int i = start; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.isEmpty()) continue;
                String[] parts = l.split(",");
                if (parts.length < 3) continue;
                try {
                    Instant ts = Instant.parse(parts[0].trim());
                    int pct = Integer.parseInt(parts[1].trim());
                    boolean ac = parts[2].trim().equals("1") || parts[2].trim().equalsIgnoreCase("true");
                    out.add(new Entry(ts, pct, ac));
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return out;
    }

    /**
     * Returns drained percent in last hour (max - min, or start - end if monotonic).
     * If <2 samples in last hour, returns 0.
     */
    public synchronized int drainedInLastHour() {
        List<Entry> all = readAll();
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        List<Entry> hour = all.stream().filter(e -> e.timestamp().isAfter(oneHourAgo)).toList();
        if (hour.size() < 2) return 0;
        int min = hour.stream().mapToInt(Entry::percent).min().orElse(0);
        int max = hour.stream().mapToInt(Entry::percent).max().orElse(0);
        // Drained = max - current (more accurate for discharge)
        int current = hour.get(hour.size() - 1).percent();
        int drained = max - current;
        if (drained < 0) drained = 0;
        // Also cap by max-min to avoid charge spikes
        int range = max - min;
        return Math.min(drained, range);
    }

    public synchronized String getTrendLabel() {
        int drained = drainedInLastHour();
        if (drained == 0) {
            List<Entry> all = readAll();
            if (all.size() < 2) return "Not enough history yet";
            return "Battery stable in last hour";
        }
        return "Battery drained " + drained + "% in the last hour";
    }
}
