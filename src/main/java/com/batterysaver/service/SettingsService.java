package com.batterysaver.service;

import com.batterysaver.constants.AppConstants;
import com.batterysaver.util.PortableMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Settings persistence.
 * - Portable mode: JSON file next to exe (portable.flag or --portable), via PortableMode.
 * - Installed mode: JSON in %APPDATA%\BatterySaver\config.json, fallback to Preferences for migration.
 * New fields: chargeLimitPercent (60-100, default 80), hotkeyModifiers/Vk, updateCheckEnabled + repoSlug.
 */
public class SettingsService {
    private static final String NODE = "BatterySaver";

    public static class Config {
        public int lowBatteryThreshold = 20;   // used by the low-battery toast
        public int criticalBatteryThreshold = 10;
        public int dimPercent = 40;
        public boolean autoStart = true;
        // Phase 7-9 new fields
        public int chargeLimitPercent = 80; // 60-100
        public boolean chargeLimitEnabled = true;
        public int hotkeyModifiers = 0x0003; // MOD_CONTROL|MOD_ALT
        public int hotkeyVk = 0x42; // 'B'
        public boolean idleDimmingEnabled = true;
        public int idleMinutes = 2;
        public boolean updateCheckEnabled = false; // 0-cost: disabled by default
        public String updateRepoSlug = AppConstants.GITHUB_REPO; // default from About/GitHub constant
        public String theme = "Dark"; // "Dark" or "Light"
        public boolean ecoQosEnabled = true; // EnergyStar EcoQoS throttling
        public String ecoQosWhitelistStr = "logioptionsplus.exe, steam.exe, discord.exe, obs64.exe";

        /** Field-by-field copy - UI edits a copy so the live config is only ever
         *  replaced atomically via SettingsService.save() + vm.updateConfig(). */
        public Config copy() {
            Config c = new Config();
            c.lowBatteryThreshold = lowBatteryThreshold;
            c.criticalBatteryThreshold = criticalBatteryThreshold;
            c.dimPercent = dimPercent;
            c.autoStart = autoStart;
            c.chargeLimitPercent = chargeLimitPercent;
            c.chargeLimitEnabled = chargeLimitEnabled;
            c.hotkeyModifiers = hotkeyModifiers;
            c.hotkeyVk = hotkeyVk;
            c.idleDimmingEnabled = idleDimmingEnabled;
            c.idleMinutes = idleMinutes;
            c.updateCheckEnabled = updateCheckEnabled;
            c.updateRepoSlug = updateRepoSlug;
            c.theme = theme;
            c.ecoQosEnabled = ecoQosEnabled;
            c.ecoQosWhitelistStr = ecoQosWhitelistStr;
            return c;
        }
    }

    private static Preferences prefs = Preferences.userNodeForPackage(SettingsService.class).node(NODE);

    public static Config load() {
        // Try portable JSON first, then AppData JSON, then Preferences fallback
        Config cfg = loadFromJson();
        if (cfg != null) return cfg;

        Config c = new Config();
        c.lowBatteryThreshold = prefs.getInt("lowBatteryThreshold", 20);
        c.criticalBatteryThreshold = prefs.getInt("criticalBatteryThreshold", 10);
        c.dimPercent = prefs.getInt("dimPercent", 40);
        c.autoStart = prefs.getBoolean("autoStart", true);
        c.chargeLimitPercent = prefs.getInt("chargeLimitPercent", 80);
        c.chargeLimitEnabled = prefs.getBoolean("chargeLimitEnabled", true);
        c.hotkeyModifiers = prefs.getInt("hotkeyModifiers", 0x0003);
        c.hotkeyVk = prefs.getInt("hotkeyVk", 0x42);
        c.idleDimmingEnabled = prefs.getBoolean("idleDimmingEnabled", true);
        c.idleMinutes = prefs.getInt("idleMinutes", 2);
        c.updateCheckEnabled = prefs.getBoolean("updateCheckEnabled", false);
        c.updateRepoSlug = prefs.get("updateRepoSlug", AppConstants.GITHUB_REPO);
        c.theme = prefs.get("theme", "Dark");
        c.ecoQosEnabled = prefs.getBoolean("ecoQosEnabled", true);
        c.ecoQosWhitelistStr = prefs.get("ecoQosWhitelistStr", "logioptionsplus.exe, steam.exe, discord.exe, obs64.exe");
        // Clamp charge limit
        if (c.chargeLimitPercent < 60) c.chargeLimitPercent = 60;
        if (c.chargeLimitPercent > 100) c.chargeLimitPercent = 100;
        return c;
    }

    public static void save(Config cfg) {
        // Clamp
        if (cfg.chargeLimitPercent < 60) cfg.chargeLimitPercent = 60;
        if (cfg.chargeLimitPercent > 100) cfg.chargeLimitPercent = 100;

        // If portable mode, write JSON next to exe; still mirror to prefs for compat
        Path jsonPath = PortableMode.getConfigPath();
        try {
            Files.createDirectories(jsonPath.getParent());
            String json = toJson(cfg);
            Files.writeString(jsonPath, json);
        } catch (Exception e) {
            System.err.println("SettingsService save JSON failed: " + e.getMessage());
        }

        // Mirror to Preferences (migration / fallback)
        prefs.putInt("lowBatteryThreshold", cfg.lowBatteryThreshold);
        prefs.putInt("criticalBatteryThreshold", cfg.criticalBatteryThreshold);
        prefs.putInt("dimPercent", cfg.dimPercent);
        prefs.putBoolean("autoStart", cfg.autoStart);
        prefs.putInt("chargeLimitPercent", cfg.chargeLimitPercent);
        prefs.putBoolean("chargeLimitEnabled", cfg.chargeLimitEnabled);
        prefs.putInt("hotkeyModifiers", cfg.hotkeyModifiers);
        prefs.putInt("hotkeyVk", cfg.hotkeyVk);
        prefs.putBoolean("idleDimmingEnabled", cfg.idleDimmingEnabled);
        prefs.putInt("idleMinutes", cfg.idleMinutes);
        prefs.putBoolean("updateCheckEnabled", cfg.updateCheckEnabled);
        prefs.put("updateRepoSlug", cfg.updateRepoSlug);
        prefs.put("theme", cfg.theme != null ? cfg.theme : "Dark");
        prefs.putBoolean("ecoQosEnabled", cfg.ecoQosEnabled);
        prefs.put("ecoQosWhitelistStr", cfg.ecoQosWhitelistStr != null ? cfg.ecoQosWhitelistStr : "");
        try { prefs.flush(); } catch (Exception ignored) {}
    }

    private static Config loadFromJson() {
        // Check portable path first, then AppData path (if portable not active, second is same)
        Path portablePath = PortableMode.getConfigPath(java.util.List.of("--portable"));
        Path installedPath = PortableMode.getConfigPath(java.util.List.of());
        Path[] candidates = { portablePath, installedPath };
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    String json = Files.readString(p);
                    Config c = fromJson(json);
                    if (c != null) return c;
                }
            } catch (Exception ignored) {}
        }
        // Also try whichever PortableMode currently resolves to (covers live mode)
        try {
            Path live = PortableMode.getConfigPath();
            if (Files.exists(live)) {
                boolean alreadyTried = live.equals(portablePath) || live.equals(installedPath);
                if (!alreadyTried) {
                    String json = Files.readString(live);
                    Config c = fromJson(json);
                    if (c != null) return c;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // Minimal JSON without external dep - only flat fields, no nesting.
    // Package-private for round-trip testing.
    static String toJson(Config c) {
        return "{\n" +
                "  \"lowBatteryThreshold\": " + c.lowBatteryThreshold + ",\n" +
                "  \"criticalBatteryThreshold\": " + c.criticalBatteryThreshold + ",\n" +
                "  \"dimPercent\": " + c.dimPercent + ",\n" +
                "  \"autoStart\": " + c.autoStart + ",\n" +
                "  \"chargeLimitPercent\": " + c.chargeLimitPercent + ",\n" +
                "  \"chargeLimitEnabled\": " + c.chargeLimitEnabled + ",\n" +
                "  \"hotkeyModifiers\": " + c.hotkeyModifiers + ",\n" +
                "  \"hotkeyVk\": " + c.hotkeyVk + ",\n" +
                "  \"idleDimmingEnabled\": " + c.idleDimmingEnabled + ",\n" +
                "  \"idleMinutes\": " + c.idleMinutes + ",\n" +
                "  \"updateCheckEnabled\": " + c.updateCheckEnabled + ",\n" +
                "  \"updateRepoSlug\": \"" + esc(c.updateRepoSlug) + "\",\n" +
                "  \"theme\": \"" + esc(c.theme) + "\",\n" +
                "  \"ecoQosEnabled\": " + c.ecoQosEnabled + ",\n" +
                "  \"ecoQosWhitelistStr\": \"" + esc(c.ecoQosWhitelistStr) + "\"\n" +
                "}";
    }

    static Config fromJson(String json) {
        try {
            Config c = new Config();
            c.lowBatteryThreshold = extractInt(json, "lowBatteryThreshold", c.lowBatteryThreshold);
            c.criticalBatteryThreshold = extractInt(json, "criticalBatteryThreshold", c.criticalBatteryThreshold);
            c.dimPercent = extractInt(json, "dimPercent", c.dimPercent);
            c.autoStart = extractBool(json, "autoStart", c.autoStart);
            c.chargeLimitPercent = extractInt(json, "chargeLimitPercent", c.chargeLimitPercent);
            c.chargeLimitEnabled = extractBool(json, "chargeLimitEnabled", c.chargeLimitEnabled);
            c.hotkeyModifiers = extractInt(json, "hotkeyModifiers", c.hotkeyModifiers);
            c.hotkeyVk = extractInt(json, "hotkeyVk", c.hotkeyVk);
            c.idleDimmingEnabled = extractBool(json, "idleDimmingEnabled", c.idleDimmingEnabled);
            c.idleMinutes = extractInt(json, "idleMinutes", c.idleMinutes);
            c.updateCheckEnabled = extractBool(json, "updateCheckEnabled", c.updateCheckEnabled);
            c.updateRepoSlug = extractString(json, "updateRepoSlug", c.updateRepoSlug);
            c.theme = extractString(json, "theme", c.theme);
            c.ecoQosEnabled = extractBool(json, "ecoQosEnabled", c.ecoQosEnabled);
            c.ecoQosWhitelistStr = extractString(json, "ecoQosWhitelistStr", c.ecoQosWhitelistStr);
            if (c.chargeLimitPercent < 60) c.chargeLimitPercent = 60;
            if (c.chargeLimitPercent > 100) c.chargeLimitPercent = 100;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int extractInt(String json, String key, int def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }
    private static boolean extractBool(String json, String key, boolean def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : def;
    }
    private static String extractString(String json, String key, String def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : def;
    }

    /** For hotkey display: e.g. 0x0003 + 0x42 -> "Ctrl+Alt+B" */
    public static String hotkeyToString(int modifiers, int vk) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & 0x0002) != 0) sb.append("Ctrl+");
        if ((modifiers & 0x0001) != 0) sb.append("Alt+");
        if ((modifiers & 0x0004) != 0) sb.append("Shift+");
        if ((modifiers & 0x0008) != 0) sb.append("Win+");
        // vk to char
        if (vk >= 0x41 && vk <= 0x5A) sb.append((char) vk);
        else if (vk >= 0x70 && vk <= 0x87) sb.append("F").append(vk - 0x6F);
        else sb.append("VK_").append(vk);
        return sb.toString();
    }
}
