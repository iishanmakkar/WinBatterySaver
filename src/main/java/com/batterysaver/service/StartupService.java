package com.batterysaver.service;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * Manages Windows startup registration via HKCU\Software\Microsoft\Windows\CurrentVersion\Run.
 * HKCU is user-writable without admin, so this works on standard accounts.
 * Disabled/greyed out in portable mode.
 */
public class StartupService {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
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
}
