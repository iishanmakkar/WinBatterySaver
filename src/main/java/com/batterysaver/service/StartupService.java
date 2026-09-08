package com.batterysaver.service;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages Windows startup registration via HKCU\Software\Microsoft\Windows\CurrentVersion\Run.
 * HKCU is user-writable without admin, so this works on standard accounts.
 * Disabled/greyed out in portable mode.
 */
public class StartupService {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    // Task Manager's per-app startup toggle lives here: a value named like ours
    // with bit0 set in the first byte = user disabled autostart there. The Run
    // key alone is NOT the whole truth - this key silently kills the launch.
    private static final String APPROVED_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    // Must match the name used by build.gradle's generateAutoStartScript, otherwise
    // two different Run entries can coexist and double-start the app.
    private static final String VALUE = "WindowsBatterySaver";
    // Legacy entry written by older builds of this app
    private static final String LEGACY_VALUE = "BatterySaver";

    public void enable(String exePath) {
        if (exePath == null || exePath.isBlank()) {
            throw new IllegalArgumentException("exePath must not be blank");
        }
        // Quote path and add --minimized so Main starts to tray without flashing window
        String cmd = "\"" + exePath + "\" --minimized";
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, cmd);
        // Clean up any legacy entry from older versions
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, LEGACY_VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, LEGACY_VALUE);
            }
        } catch (Exception ignored) {}
    }

    public void disable() {
        for (String value : new String[]{VALUE, LEGACY_VALUE}) {
            try {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, value)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, value);
                }
            } catch (Exception e) {
                System.err.println("StartupService.disable failed: " + e.getMessage());
            }
        }
    }

    public boolean isEnabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)
                    || Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, LEGACY_VALUE);
        } catch (Exception e) {
            return false;
        }
    }

    public String getRegisteredCommand() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)) {
                return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * True when a Run entry exists but no longer points at a valid exe (app
     * moved, uninstalled copy deleted) - Windows would silently fail to launch
     * us at logon. The Settings checkbox shows ON in this state, so callers
     * repair it by re-registering with the current path.
     */
    public boolean isRegisteredStale() {
        String cmd = getRegisteredCommand();
        if (cmd == null || cmd.isBlank()) return false;
        String exe = extractExePath(cmd);
        return exe == null || !Files.isRegularFile(Path.of(exe));
    }

    /**
     * True when Task Manager's Startup tab has our entry toggled OFF. Windows
     * records that in Explorer\\StartupApproved\\Run without touching the Run
     * key itself - so isEnabled() can say "registered" while Windows silently
     * skips the launch at logon. Bit0 of the first byte set = disabled.
     */
    public boolean isDisabledByTaskManager() {
        return approvedFlagSet(VALUE);
    }

    /** True when the legacy value name has a leftover approval-flag entry. */
    public boolean hasLegacyApprovalEntry() {
        return approvedFlagSet(LEGACY_VALUE);
    }

    private boolean approvedFlagSet(String valueName) {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, valueName)) {
                return false;
            }
            byte[] flag = Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, valueName);
            return flag != null && flag.length >= 1 && (flag[0] & 0x01) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Deletes a StartupApproved flag entry, which re-enables the logon launch. */
    private void removeApprovalFlag(String valueName) {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, valueName)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, valueName);
            }
        } catch (Exception e) {
            System.err.println("StartupService.removeApprovalFlag failed: " + e.getMessage());
        }
    }

    /**
     * Extract the exe path from a registered command: the first quoted token,
     * or the text before " --minimized" for unquoted legacy entries.
     */
    static String extractExePath(String cmd) {
        String c = cmd.trim();
        if (c.startsWith("\"")) {
            int end = c.indexOf('"', 1);
            if (end > 1) return c.substring(1, end);
            return null;
        }
        int cut = c.indexOf(" --");
        return cut > 0 ? c.substring(0, cut).trim() : (c.isEmpty() ? null : c);
    }

    /**
     * Self-heal autostart at app launch. Runs automatically so the checkbox in
     * Settings stays truthful even if the user never opens Settings: a missing
     * entry (or one pointing at a moved/updated/old copy) is re-registered to
     * the CURRENT exe path, but only when the saved setting wants autostart.
     *
     * @return "enabled", "repaired", or "off" (diagnostic string, also logged)
     */
    public String ensureRegistered(String exePath, boolean wantsAutoStart) {
        String result;
        try {
            if (!wantsAutoStart) {
                // Setting is off - make sure no stale entry lingers from an old build
                if (isEnabled()) {
                    disable();
                    result = "off (removed stale entry)";
                } else {
                    result = "off";
                }
            } else if (exePath == null || exePath.isBlank()) {
                // Dev run (java.exe): never register a JVM; keep any real entry untouched
                result = isEnabled() ? "enabled (dev run - existing entry kept)" : "off (dev run)";
            } else {
                String cmd = getRegisteredCommand();
                String registeredExe = cmd != null ? extractExePath(cmd) : null;
                boolean pointsAtUs = registeredExe != null
                        && registeredExe.equalsIgnoreCase(exePath)
                        && Files.isRegularFile(Path.of(registeredExe));
                if (!pointsAtUs) {
                    enable(exePath);
                    result = cmd == null ? "enabled (first run)" : "repaired (path changed)";
                } else {
                    result = "enabled";
                }
                // The Run key is only half the story: if Task Manager's Startup tab
                // (or an OEM cleanup tool) flagged the entry off, Windows skips the
                // logon launch while every registry check still says "registered".
                // The user's saved setting says ON, so clear the silent kill-switch.
                if (isDisabledByTaskManager()) {
                    removeApprovalFlag(VALUE);
                    result += " + re-enabled (was switched off in Task Manager)";
                }
                // Legacy flag entries only confuse Task Manager's list - clean them
                if (hasLegacyApprovalEntry()) {
                    removeApprovalFlag(LEGACY_VALUE);
                }
            }
        } catch (Exception e) {
            result = "failed: " + e.getMessage();
            System.err.println("StartupService.ensureRegistered: " + result);
        }
        System.out.println("Auto-start: " + result);
        return result;
    }
}
