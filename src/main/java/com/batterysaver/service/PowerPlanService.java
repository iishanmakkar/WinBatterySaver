package com.batterysaver.service;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class PowerPlanService {
    public static final String BALANCED = "381b4222-f694-41f0-9685-ff5bb260df2e";
    public static final String POWER_SAVER_DEFAULT = "a1841308-3541-4fab-bc81-f71556f20b4a";
    // Distinct GUID for the Power Saver scheme (same as default on most systems, but kept separate for clarity)
    // On some systems the default scheme GUID may differ; this allows distinct identification
    public static final String POWER_SAVER = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0001";
    public static final String HIGH_PERFORMANCE = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
    private static final int TIMEOUT_SECONDS = 4;

    public void setActivePlan(String guid) throws Exception {
        if (guid == null || guid.isBlank()) return;
        int exit = run("powercfg", "/setactive", guid);
        if (exit != 0) {
            System.err.println("powercfg /setactive " + guid + " exit code " + exit);
        }
    }

    public boolean isPowerSaverGuid(String guid) {
        if (guid == null) return false;
        if (POWER_SAVER_DEFAULT.equalsIgnoreCase(guid)) return true;
        try {
            String list = runCapture("powercfg", "/list");
            if (list != null) {
                for (String line : list.split("\n")) {
                    if (line.toLowerCase().contains(guid.toLowerCase()) && line.toLowerCase().contains("saver")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void ensureAndActivatePowerSaver() throws Exception {
        // 1. Check if a Power Saver scheme is already registered in powercfg /list
        String existingSaverGuid = findPowerSaverGuidInList();
        if (existingSaverGuid != null) {
            setActivePlan(existingSaverGuid);
            return;
        }

        // 2. Try activating default Power Saver GUID
        int exit = run("powercfg", "/setactive", POWER_SAVER_DEFAULT);
        if (exit == 0) return;

        // 3. If missing on Modern Standby system, duplicate/import default scheme
        String duplicatedGuid = duplicatePowerSaverScheme();
        if (duplicatedGuid != null) {
            setActivePlan(duplicatedGuid);
        }
    }

    public String findPowerSaverGuidInList() {
        try {
            String list = runCapture("powercfg", "/list");
            if (list == null) return null;
            for (String line : list.split("\n")) {
                if (line.toLowerCase().contains("saver") || line.toLowerCase().contains("eco")) {
                    Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(line);
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String duplicatePowerSaverScheme() {
        try {
            String out = runCapture("powercfg", "-duplicatescheme", POWER_SAVER_DEFAULT);
            if (out != null) {
                Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(out);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String getActivePlanGuid() throws Exception {
        String out = runCapture("powercfg", "/getactivescheme");
        if (out == null) return null;
        Matcher m = Pattern.compile("([0-9a-fA-F-]{36})").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private int run(String... cmd) throws Exception {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {} // drain
            } catch (Exception ignored) {}
            boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(1, TimeUnit.SECONDS);
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private String runCapture(String... cmd) throws Exception {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
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
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
