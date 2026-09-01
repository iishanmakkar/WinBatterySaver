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
        return out.toLowerCase().contains("standby (s0 low power idle)");
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("ModernStandby check failed: " + e.getMessage());
            return null;
        }
    }
}
