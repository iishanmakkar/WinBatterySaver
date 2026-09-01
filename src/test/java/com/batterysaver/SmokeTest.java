package com.batterysaver;

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
        NotificationService n = new NotificationService();
        ChargeLimitReminder r = new ChargeLimitReminder();
        assertFalse(r.isAlreadyNotified());
        r.check(80, true, 80, n);
        assertTrue(r.isAlreadyNotified());
        // second check should not re-notify
        r.check(81, true, 80, n);
        assertTrue(r.isAlreadyNotified());
        // drop 5% below re-arms
        r.check(74, true, 80, n);
        assertFalse(r.isAlreadyNotified());
        r.check(80, true, 80, n);
        assertTrue(r.isAlreadyNotified());
        // not charging -> no notify
        r.reset();
        r.check(80, false, 80, n);
        assertFalse(r.isAlreadyNotified());
    }

    @Test
    void batteryHistoryDrain(@TempDir Path dir) {
        Path file = dir.resolve("history.csv");
        BatteryHistoryService svc = new BatteryHistoryService(file);
        svc.append(100, false);
        svc.append(90, false);
        svc.append(80, false);
        int drained = svc.drainedInLastHour();
        assertTrue(drained >= 0);
        String label = svc.getTrendLabel();
        assertNotNull(label);
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
