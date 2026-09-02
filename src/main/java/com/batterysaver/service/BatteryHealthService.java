package com.batterysaver.service;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class BatteryHealthService {
    public record Health(int designCapacityMwh, int fullChargeCapacityMwh, int cycleCount, double healthPercent) {}

    public Health getHealth() throws Exception {
        Path tmp = Files.createTempFile("battery-report", ".html");
        try {
            ProcessBuilder pb = new ProcessBuilder("powercfg", "/batteryreport", "/output", tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain on a daemon thread so the waitFor timeout can actually fire
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    while (r.readLine() != null) {}
                } catch (Exception ignored) {}
            }, "battery-report-io");
            drain.setDaemon(true);
            drain.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, TimeUnit.SECONDS);
                Files.deleteIfExists(tmp);
                throw new IOException("Battery health report timed out (possible EDR block)");
            }
            if (p.exitValue() != 0) {
                Files.deleteIfExists(tmp);
                throw new IOException("Battery health report requires elevated permissions on this device (powercfg exit=" + p.exitValue() + ")");
            }
            if (!Files.exists(tmp) || Files.size(tmp) == 0) {
                throw new IOException("Battery health report not generated (empty output)");
            }
            return parseHtml(Files.readString(tmp));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Battery health failed: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    /**
     * Parse capacities/cycles from a battery-report HTML document.
     * Package-private for regression testing against real report fixtures.
     */
    static Health parseHtml(String html) {
        int design = extractInstalledCapacity(html, "DESIGN CAPACITY");
        int full   = extractInstalledCapacity(html, "FULL CHARGE CAPACITY");
        int cycles = extractCycleCount(html);
        double healthPct = design > 0 ? (full * 100.0 / design) : -1.0;
        return new Health(design, full, cycles, healthPct);
    }

    /**
     * Extracts the capacity from the "Installed batteries" detail cells ONLY, summing
     * one value per battery on multi-battery systems.
     *
     * IMPORTANT: a lazy DOTALL match like "DESIGN CAPACITY .*? ([\d,]+) mWh" also
     * hits the "Battery capacity history" TABLE HEADER ("FULL CHARGE CAPACITY</td>
     * <td>DESIGN CAPACITY</td>") and then harvests the first history-row value -
     * double-counting capacities and reporting several points of optimistic health.
     * The installed-battery cells use {@code <span class="label">LABEL</span></td>
     * <td>value mWh</td>} while the history header uses plain/bold text, so anchor
     * on the closing {@code </span>}.
     */
    private static int extractInstalledCapacity(String html, String label) {
        Matcher m = Pattern.compile(
                        label + "\\s*</span>\\s*</td>\\s*<td[^>]*>\\s*([\\d,]+)\\s*mWh",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        int sum = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            sum += Integer.parseInt(m.group(1).replace(",", ""));
        }
        if (found) return sum;
        // Fallback for older report formats without the span markup
        m = Pattern.compile(label + "[^<]*</td>\\s*<td[^>]*>\\s*([\\d,]+)\\s*mWh",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        return -1;
    }

    /**
     * Cycle count from the table cell right after the label. A DOTALL "label .*? (\d+)"
     * would skip past a "-" cell and harvest digits from later report sections
     * (dates, mWh values), so anchor to the next {@code <td>} element.
     */
    private static int extractCycleCount(String html) {
        Matcher m = Pattern.compile("CYCLE\\s+COUNT[^<]*</span>\\s*</td>\\s*<td[^>]*>\\s*([\\d,]+)?\\s*</td>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (m.find() && m.group(1) != null) {
            try { return Integer.parseInt(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
        }
        // Return -1 => UI shows "not reported"
        return -1;
    }
}
