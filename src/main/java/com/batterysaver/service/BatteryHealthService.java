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
            // Drain
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            } catch (Exception ignored) {}
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
            String html = Files.readString(tmp);
            int design = extractMwh(html, "DESIGN CAPACITY");
            int full   = extractMwh(html, "FULL CHARGE CAPACITY");
            int cycles = extractInt(html, "CYCLE COUNT");
            double healthPct = design > 0 ? (full * 100.0 / design) : -1.0;
            return new Health(design, full, cycles, healthPct);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Battery health failed: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private int extractMwh(String html, String label) {
        Matcher m = Pattern.compile(label + ".*?([\\d,]+)\\s*mWh", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return m.find() ? Integer.parseInt(m.group(1).replace(",", "")) : -1;
    }

    private int extractInt(String html, String label) {
        // Cycle count line is like: <span class="label">CYCLE COUNT</span> ... <td>-</td> or <td>123</td>
        // More robust: search for label then numeric capture, but allow '-' (no data)
        Matcher m = Pattern.compile(label + ".*?(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        // Return -1 => UI shows "not reported"
        return -1;
    }
}
