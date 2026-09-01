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
    private static final String VALUE = "BatterySaver";

    public void enable(String exePath) {
        if (exePath == null || exePath.isBlank()) {
            throw new IllegalArgumentException("exePath must not be blank");
        }
        // Quote path and add --minimized so Main starts to tray without flashing window
        String cmd = "\"" + exePath + "\" --minimized";
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, cmd);
    }

    public void disable() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
            }
        } catch (Exception e) {
            System.err.println("StartupService.disable failed: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
        } catch (Exception e) {
            return false;
        }
    }

    public String getRegisteredCommand() {
        try {
            if (isEnabled()) {
                return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
