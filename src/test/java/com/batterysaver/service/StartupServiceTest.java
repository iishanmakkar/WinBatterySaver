package com.batterysaver.service;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autostart verification against the REAL HKCU Run key (the exact mechanism the
 * Settings "Auto-start" checkbox uses). Restores any pre-existing registration
 * afterwards so a user's real setting is never lost by running the tests.
 *
 * Skipped when the Run key is not writable: GitHub Actions windows runners block
 * writes to autostart locations (anti-persistence), which surfaces as
 * Win32Exception (access denied) - the mechanism is exercised on real machines.
 */
@EnabledOnOs(OS.WINDOWS)
public class StartupServiceTest {

    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "WindowsBatterySaver";
    private static final String LEGACY = "BatterySaver";

    private final StartupService ss = new StartupService();

    private static boolean runKeyWritable;

    static {
        try {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, "writability-probe");
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE);
            runKeyWritable = true;
        } catch (Throwable t) {
            runKeyWritable = false;
        }
    }

    @AfterEach
    void restorePriorState() {
        if (!runKeyWritable) return;
        String prior = ss.getRegisteredCommand();
        if (prior != null) {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, prior);
        } else {
            ss.disable();
        }
    }

    @Test
    void enableDisableRoundTrip() {
        Assumptions.assumeTrue(runKeyWritable, "HKCU Run key not writable (CI runner) - skipping");
        // clean slate
        ss.disable();
        assertFalse(ss.isEnabled(), "must start disabled after disable()");

        String exe = ProcessHandle.current().info().command().orElse("C:\\Program Files\\WindowsBatterySaver\\WindowsBatterySaver.exe");
        ss.enable(exe);
        assertTrue(ss.isEnabled(), "must be enabled after enable()");

        String cmd = ss.getRegisteredCommand();
        assertNotNull(cmd, "registered command must be readable");
        assertTrue(cmd.startsWith("\""), "exe path must be quoted (spaces-safe): " + cmd);
        assertTrue(cmd.endsWith("--minimized"), "must launch minimized to tray: " + cmd);
        assertTrue(cmd.contains(exe), "registered command must contain the exe path: " + cmd);
        assertEquals("\"" + exe + "\" --minimized", cmd, "exact expected format");
        // the value the OS will actually launch at logon:
        assertEquals(cmd, Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE),
                "registry value must match getRegisteredCommand()");

        ss.disable();
        assertFalse(ss.isEnabled(), "must be disabled after disable()");
        assertFalse(Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE),
                "registry value must be gone after disable()");
    }

    @Test
    void enableCleansUpLegacyEntry() {
        Assumptions.assumeTrue(runKeyWritable, "HKCU Run key not writable (CI runner) - skipping");
        // simulate an entry written by an older build of the app
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, LEGACY,
                "\"C:\\legacy\\BatterySaver.exe\" --minimized");
        try {
            String exe = ProcessHandle.current().info().command().orElse("C:\\x\\WindowsBatterySaver.exe");
            ss.enable(exe);
            assertFalse(Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, LEGACY),
                    "legacy 'BatterySaver' entry must be removed by enable() (no double autostart)");
            assertTrue(ss.isEnabled());
        } finally {
            ss.disable();
        }
    }

    @Test
    void isEnabledDetectsLegacyEntryOnly() {
        Assumptions.assumeTrue(runKeyWritable, "HKCU Run key not writable (CI runner) - skipping");
        ss.disable();
        try {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, LEGACY,
                    "\"C:\\legacy\\BatterySaver.exe\" --minimized");
            assertTrue(ss.isEnabled(), "isEnabled must also detect the legacy entry");
        } finally {
            ss.disable();
        }
    }

    @Test
    void ensureRegisteredHealsMissingAndStaleEntries() {
        Assumptions.assumeTrue(runKeyWritable, "HKCU Run key not writable (CI runner) - skipping");
        // Use a path that REALLY exists on the host (the test JVM): ensureRegistered
        // validates file existence when deciding "points at us" vs "stale"
        String exe = ProcessHandle.current().info().command().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(exe != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(exe)),
                "no resolvable running exe on this host");
        String movedPath = java.nio.file.Path.of(exe).getParent().resolve("wbs-moved-target.exe").toString();
        ss.disable();
        try {
            // 1) wants=false: entry must stay gone
            String result = ss.ensureRegistered(exe, false);
            assertFalse(ss.isEnabled(), "wantsAutoStart=false must not register");

            // 2) wants=true, nothing registered -> enable
            result = ss.ensureRegistered(exe, true);
            assertTrue(result.startsWith("enabled"), "first registration should report enabled: " + result);
            assertTrue(ss.getRegisteredCommand().contains(exe));

            // 3) same path again -> idempotent, no rewrite churn
            result = ss.ensureRegistered(exe, true);
            assertEquals("enabled", result);

            // 4) app moved -> repair to the NEW path
            result = ss.ensureRegistered(movedPath, true);
            assertTrue(result.startsWith("repaired"), "path change must repair: " + result);
            assertTrue(ss.getRegisteredCommand().contains(movedPath),
                    "registry must point at the new path after repair");

            // 5) wants=false afterwards must clear the entry again
            ss.ensureRegistered(null, false);
            assertFalse(ss.isEnabled(), "wants=false must clear a stale entry");
        } finally {
            ss.disable();
        }
    }

    @Test
    void extractExePathParsesQuotedAndLegacyFormats() {
        Assumptions.assumeTrue(runKeyWritable, "HKCU Run key not writable (CI runner) - skipping");
        assertEquals("C:\\Program Files\\WindowsBatterySaver\\WindowsBatterySaver.exe",
                StartupService.extractExePath("\"C:\\Program Files\\WindowsBatterySaver\\WindowsBatterySaver.exe\" --minimized"));
        assertEquals("C:\\tools\\wbs.exe",
                StartupService.extractExePath("C:\\tools\\wbs.exe --minimized"));
        assertEquals("C:\\quoted\\only.exe",
                StartupService.extractExePath("\"C:\\quoted\\only.exe\""));
    }
}
