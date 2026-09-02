package com.batterysaver.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Detects Modern Standby (S0 low-power idle) via powercfg /a.
 * 2020+ ultrabooks use S0 instead of S3; Power Saver scheme behaves slightly differently.
 */
public class ModernStandbyService {

    public boolean isModernStandby() {
        String out = runCapture("powercfg", "/a");
        if (out == null) return false;
        String lower = out.toLowerCase();
        // "Standby (S0 Low Power Idle)" is the English phrasing; the "(S0" marker plus
        // "low power idle" catches most localized variants without a false positive on S3.
        return lower.contains("standby (s0 low power idle)")
                || (lower.contains("(s0") && lower.contains("low power idle"));
    }

    public String getNote() {
        if (isModernStandby()) {
            return "Modern Standby (S0 Low Power Idle) detected - brightness/throttle behavior may differ slightly on this device.";
        }
        return null;
    }

    private String runCapture(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            // Drain on a daemon thread so the timeout can actually fire
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append("\n");
                } catch (Exception ignored) {}
            }, "standby-io");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            reader.join(300);
            return sb.toString();
        } catch (Exception e) {
            System.err.println("ModernStandby check failed: " + e.getMessage());
            return null;
        }
    }
}
