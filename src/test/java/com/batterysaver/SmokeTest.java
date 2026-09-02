package com.batterysaver;

import com.batterysaver.model.Battery;
import com.batterysaver.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class SmokeTest {

    @Test
    void settingsConfigDefaults() {
        SettingsService.Config cfg = new SettingsService.Config();
        assertEquals(80, cfg.chargeLimitPercent);
        assertTrue(cfg.chargeLimitEnabled);
        assertTrue(cfg.autoStart);
        assertEquals("Dark", cfg.theme);
        assertTrue(cfg.ecoQosEnabled);
        assertNotNull(cfg.ecoQosWhitelistStr);
        assertEquals("Ctrl+Alt+B", SettingsService.hotkeyToString(cfg.hotkeyModifiers, cfg.hotkeyVk));
    }

    @Test
    void ecoQosDefaultWhitelist() {
        EcoQosThrottleService eco = new EcoQosThrottleService();
        assertNotNull(eco);
        assertTrue(EcoQosThrottleService.DEFAULT_WHITELIST.contains("discord.exe"));
        assertTrue(EcoQosThrottleService.DEFAULT_WHITELIST.contains("obs64.exe"));
        assertFalse(eco.isEnabled());
    }

    @Test
    void powerPlanServiceGuids() {
        PowerPlanService pps = new PowerPlanService();
        assertNotNull(pps);
        assertTrue(pps.isPowerSaverGuid(PowerPlanService.POWER_SAVER_DEFAULT));
        assertFalse(pps.isPowerSaverGuid("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void batteryOptimizerSuggestion() {
        BatteryOptimizerService optimizer = new BatteryOptimizerService();
        String suggestion = optimizer.topDrainerSuggestion();
        assertNotNull(suggestion);
        assertFalse(suggestion.isBlank());
    }

    @Test
    void chargeLimitReminder() {
        ChargeLimitReminder r = new ChargeLimitReminder();
        assertFalse(r.isAlreadyNotified());
        r.check(80, true, 80, NOOP_NOTIFIER);
        assertTrue(r.isAlreadyNotified());
        // second check should not re-notify
        r.check(81, true, 80, NOOP_NOTIFIER);
        assertTrue(r.isAlreadyNotified());
        // drop 5% below re-arms
        r.check(74, true, 80, NOOP_NOTIFIER);
        assertFalse(r.isAlreadyNotified());
        r.check(80, true, 80, NOOP_NOTIFIER);
        assertTrue(r.isAlreadyNotified());
        // not charging -> no notify
        r.reset();
        r.check(80, false, 80, NOOP_NOTIFIER);
        assertFalse(r.isAlreadyNotified());
    }

    /** Notification stub - tests must not pop real tray balloons. */
    private static final NotificationService NOOP_NOTIFIER = new NotificationService() {
        @Override public void showInfo(String title, String message) {}
        @Override public void showWarning(String title, String message) {}
    };

    @Test
    void batteryHistoryDrain(@TempDir Path dir) {
        Path file = dir.resolve("history.csv");
        BatteryHistoryService svc = new BatteryHistoryService(file);
        svc.append(100, false);
        svc.append(90, false);
        svc.append(80, false);
        int drained = svc.drainedInLastHour();
        assertEquals(20, drained, "100->80 on battery should drain exactly 20%");
        String label = svc.getTrendLabel();
        assertNotNull(label);
    }

    @Test
    void batteryHistoryIgnoresChargingSamples(@TempDir Path dir) {
        Path file = dir.resolve("history.csv");
        BatteryHistoryService svc = new BatteryHistoryService(file);
        // charge spike in the middle must not count as "drain"
        svc.append(100, false);
        svc.append(100, true);
        svc.append(95, true);
        svc.append(90, false);
        assertEquals(10, svc.drainedInLastHour());
    }

    @Test
    void versionSingleSource() {
        assertEquals(com.batterysaver.constants.AppConstants.VERSION, UpdateCheckService.VERSION,
                "Update check must compare against the app's version constant");
    }

    @Test
    void batteryDefaultsUnknown() {
        Battery b = new Battery();
        assertEquals(-1, b.getPercent(), "unpolled battery must read unknown (-1), not 0%");
    }

    @Test
    void ecoQosUserWhitelistMergesWithDefaults() {
        EcoQosThrottleService eco = new EcoQosThrottleService();
        eco.setUserWhitelist("MyGame.exe, obs64.exe,,  weird name ");
        // user entry added (lowercased)
        assertTrue(ecoIsWhitelisted(eco, "mygame.exe"));
        // defaults retained
        assertTrue(ecoIsWhitelisted(eco, "dwm.exe"));
        assertTrue(ecoIsWhitelisted(eco, "explorer.exe"));
        assertTrue(ecoIsWhitelisted(eco, "obs64.exe"));
        // non-listed still throttled
        assertFalse(ecoIsWhitelisted(eco, "somebackgroundapp.exe"));
    }

    private boolean ecoIsWhitelisted(EcoQosThrottleService eco, String exe) {
        try {
            var f = EcoQosThrottleService.class.getDeclaredField("whitelist");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var set = (java.util.Set<String>) f.get(eco);
            return set.contains(exe);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void updateCheckIsNewer() {
        assertTrue(UpdateCheckService.isNewer("1.0.0", "1.0.1"));
        assertTrue(UpdateCheckService.isNewer("1.0.0", "2.0.0"));
        assertFalse(UpdateCheckService.isNewer("1.0.1", "1.0.0"));
        assertFalse(UpdateCheckService.isNewer("1.0.0", "1.0.0"));
        assertTrue(UpdateCheckService.isNewer("1.0.0", "v1.0.1"));
    }

    @Test
    void processUsageDoesNotCrash() {
        var top = ProcessUsageService.getTopCpuProcesses(5);
        assertNotNull(top);
    }

    @Test
    void batteryHealthServiceLoads() {
        BatteryHealthService svc = new BatteryHealthService();
        assertNotNull(svc);
    }
}
