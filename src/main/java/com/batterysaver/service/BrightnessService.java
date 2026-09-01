package com.batterysaver.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Brightness control via WMI (WmiMonitorBrightnessMethods) through PowerShell.
 * Cross-OEM: uses standard WMI, not OEM drivers. Fails gracefully with
 * UnsupportedOperationException and 3s timeout to avoid EDR hangs.
 */
public class BrightnessService {
    private static final int TIMEOUT_SECONDS = 3;

    public int getBrightness() throws UnsupportedOperationException {
        // Query current brightness via WMI
        String script = "Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness | Select-Object -ExpandProperty CurrentBrightness";
        String out = runPowerShell(script);
        if (out == null || out.isBlank()) {
            throw new UnsupportedOperationException("Brightness control unsupported on this display (no WMI data)");
        }
        try {
            // Output may be multiline; take first numeric line
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (line.matches("\\d+")) {
                    int v = Integer.parseInt(line);
                    if (v >= 0 && v <= 100) return v;
                }
            }
            throw new NumberFormatException("no numeric line");
        } catch (NumberFormatException e) {
            throw new UnsupportedOperationException("Brightness parse failed: " + out);
        }
    }

    public void setBrightness(int percent) throws UnsupportedOperationException {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent 0-100");
        // Use WmiMonitorBrightnessMethods.WmiSetBrightness(1, percent) with 1s timeout
        String script = String.format(
                "$m = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods; " +
                "if ($m) { Invoke-CimMethod -InputObject $m -MethodName WmiSetBrightness -Arguments @{ Timeout=1; Brightness=%d } | Out-Null; Write-Output OK } else { Write-Output NO_WMI }",
                percent);
        String out = runPowerShell(script);
        if (out == null) {
            throw new UnsupportedOperationException("Brightness control unsupported (PowerShell timed out or blocked by EDR)");
        }
        if (out.contains("NO_WMI") || (!out.contains("OK") && out.isBlank())) {
            throw new UnsupportedOperationException("Brightness control unsupported on this display");
        }
    }

    private String runPowerShell(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, TimeUnit.SECONDS);
                return null; // treat as unsupported, not hang
            }
            // Non-zero exit -> unsupported (e.g. WMI disabled, corporate policy)
            if (p.exitValue() != 0 && sb.length() == 0) return null;
            return sb.toString().trim();
        } catch (Exception e) {
            // EDR may block spawn; fail to unsupported
            System.err.println("BrightnessService PowerShell failed: " + e.getMessage());
            return null;
        }
    }
}
