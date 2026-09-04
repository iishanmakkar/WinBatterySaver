package com.batterysaver.service;

import com.batterysaver.view.BatteryHistoryChart;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for service-level logic (no FX toolkit needed except chart builder,
 * which only constructs data objects here).
 */
public class ServiceUnitTest {

    // ── IdleDimmingService wrap math ────────────────────────────────────────

    @Test
    void idleMillisBasicDelta() {
        assertEquals(60_000L, IdleDimmingService.idleMillis(40_000, 100_000));
    }

    @Test
    void idleMillisAcross32BitWrap() {
        // last input just before the 49.7-day wrap, now just after
        int last = -6;          // 0xFFFFFFFA
        int now = 5;            // 0x00000005
        assertEquals(11L, IdleDimmingService.idleMillis(last, now));
    }

    @Test
    void idleMillisNoInputJustNow() {
        int t = 123_456;
        assertEquals(0L, IdleDimmingService.idleMillis(t, t));
    }

    // ── EcoService exe-name extraction ──────────────────────────────────────

    @Test
    void exeNameFromFullPath() {
        assertEquals("chrome.exe", EcoService.extractExeName("C:\\Program Files\\Chrome\\chrome.exe"));
    }

    @Test
    void exeNameStripsArguments() {
        // commandLine() fallback includes arguments
        assertEquals("chrome.exe", EcoService.extractExeName("C:\\path\\chrome.exe --type=renderer --foo=1"));
    }

    @Test
    void exeNameHandlesSpacesInPath() {
        // regression: naive first-space split made this "program"
        assertEquals("chrome.exe", EcoService.extractExeName("C:\\Program Files\\Google\\Chrome\\chrome.exe"));
        assertEquals("app.exe", EcoService.extractExeName("\"C:\\Program Files\\My App\\app.exe\" --flag"));
    }

    @Test
    void exeNameLowercasedAndHandlesEmpty() {
        assertEquals("notepad.exe", EcoService.extractExeName("notepad.EXE"));
        assertEquals("", EcoService.extractExeName(""));
        assertEquals("", EcoService.extractExeName(null));
    }

    // ── SettingsService JSON round-trip ─────────────────────────────────────

    @Test
    void settingsJsonRoundTrip() {
        SettingsService.Config c = new SettingsService.Config();
        c.chargeLimitPercent = 70;
        c.chargeLimitEnabled = false;
        c.hotkeyModifiers = 0x0006;
        c.hotkeyVk = 0x21;
        c.idleDimmingEnabled = false;
        c.idleMinutes = 7;
        c.updateCheckEnabled = true;
        c.updateRepoSlug = "someone/some-repo";
        c.theme = "Light";
        c.ecoQosEnabled = false;
        c.ecoQosWhitelistStr = "a.exe, b.exe";
        c.dimPercent = 55;
        c.lowBatteryThreshold = 25;
        c.criticalBatteryThreshold = 12;
        c.autoSaverAtPercent = 30;

        String json = SettingsService.toJson(c);
        SettingsService.Config back = SettingsService.fromJson(json);
        assertNotNull(back);
        assertEquals(70, back.chargeLimitPercent);
        assertFalse(back.chargeLimitEnabled);
        assertEquals(0x0006, back.hotkeyModifiers);
        assertEquals(0x21, back.hotkeyVk);
        assertFalse(back.idleDimmingEnabled);
        assertEquals(7, back.idleMinutes);
        assertTrue(back.updateCheckEnabled);
        assertEquals("someone/some-repo", back.updateRepoSlug);
        assertEquals("Light", back.theme);
        assertFalse(back.ecoQosEnabled);
        assertEquals("a.exe, b.exe", back.ecoQosWhitelistStr);
        assertEquals(55, back.dimPercent);
        assertEquals(25, back.lowBatteryThreshold);
        assertEquals(12, back.criticalBatteryThreshold);
        assertEquals(30, back.autoSaverAtPercent);
    }

    @Test
    void settingsFromJsonFallsBackToDefaultsOnMissingKeys() {
        SettingsService.Config back = SettingsService.fromJson("{}");
        assertNotNull(back);
        assertEquals(80, back.chargeLimitPercent);
        assertEquals("Dark", back.theme);
        assertTrue(back.ecoQosEnabled);
    }

    // ── BatteryHistoryChart downsample ──────────────────────────────────────

    @Test
    void downsampleKeepsBoundsAndOrder() {
        List<BatteryHistoryService.Entry> entries = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 300; i++) {
            entries.add(new BatteryHistoryService.Entry(base.plusSeconds(60L * i), i % 100, false));
        }
        List<BatteryHistoryService.Entry> out = BatteryHistoryChart.downsample(entries, 60);
        assertEquals(60, out.size());
        // first/last preserved
        assertEquals(entries.get(0).timestamp(), out.get(0).timestamp());
        assertEquals(entries.get(entries.size() - 1).timestamp(), out.get(out.size() - 1).timestamp());
        // monotonically increasing (order preserved)
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i).timestamp().isAfter(out.get(i - 1).timestamp()));
        }
    }

    @Test
    void downsampleNoopWhenUnderMax() {
        List<BatteryHistoryService.Entry> entries = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            entries.add(new BatteryHistoryService.Entry(base.plusSeconds(i), 50, false));
        }
        assertSame(entries, BatteryHistoryChart.downsample(entries, 60));
    }

    // ── UpdateCheckService semver edges ─────────────────────────────────────

    @Test
    void semverEdgeCases() {
        assertFalse(UpdateCheckService.isNewer("1.0.0", "1.0"));
        assertTrue(UpdateCheckService.isNewer("1.0", "1.0.1"));
        assertTrue(UpdateCheckService.isNewer("1.9.0", "1.10.0"), "numeric compare, not lexicographic");
        assertFalse(UpdateCheckService.isNewer("2.0.0", "2.0.0-beta"));
        assertFalse(UpdateCheckService.isNewer(null, "1.0.0"));
        assertFalse(UpdateCheckService.isNewer("1.0.0", null));
    }

    // ── ChargeLimitReminder clamping ────────────────────────────────────────

    @Test
    void chargeReminderClampsLimitBounds() {
        ChargeLimitReminder r = new ChargeLimitReminder();
        // limit below minimum (60) clamps to 60
        r.check(58, true, 10, NOOP_NOTIFIER);
        assertFalse(r.isAlreadyNotified(), "58% is below clamped limit 60 - no notify");
        r.check(61, true, 10, NOOP_NOTIFIER);
        assertTrue(r.isAlreadyNotified(), "61% is at/above clamped limit 60 - notify");
    }

    // ── BatteryHealthService parsing (regression: capacity-history fixture) ─

    @Test
    void healthParsingIgnoresCapacityHistoryTable() {
        // Real-world fixture shape: "Installed batteries" detail cells use
        // <span class="label">...</span></td><td>value</td>, while the
        // "Battery capacity history" table header repeats the same labels as
        // plain/bold text. The old lazy regex harvested history-row values too.
        String html = """
                <html><body>
                <h2>Installed batteries</h2>
                <table>
                <tr><td><span class="label">MANUFACTURER</span></td><td>ACME</td></tr>
                <tr><td><span class="label">DESIGN CAPACITY</span></td><td>59,160 mWh</td></tr>
                <tr><td><span class="label">FULL CHARGE CAPACITY</span></td><td>51,220 mWh</td></tr>
                <tr><td><span class="label">CYCLE COUNT</span></td><td>-</td></tr>
                </table>
                <h2>Battery capacity history</h2>
                <table>
                <tr><td class="a"><b>START DATE</b></td><td class="a"><b>FULL CHARGE CAPACITY </b></td><td class="a"><b>DESIGN CAPACITY </b></td></tr>
                <tr><td>2026-01-01</td><td>50,511 mWh</td><td>59,160 mWh</td></tr>
                <tr><td>2025-06-01</td><td>50,900 mWh</td><td>59,160 mWh</td></tr>
                </table>
                </body></html>
                """;
        BatteryHealthService.Health h = BatteryHealthService.parseHtml(html);
        assertEquals(59160, h.designCapacityMwh(), "design must not sum history rows");
        assertEquals(51220, h.fullChargeCapacityMwh(), "full must not sum history rows");
        assertEquals(-1, h.cycleCount(), "'-' cell must read as not-reported");
        assertEquals(86.6, h.healthPercent(), 0.1);
    }

    @Test
    void healthParsingSumsMultiBatteryAndReadsCycles() {
        String html = """
                <html><body>
                <table>
                <tr><td><span class="label">DESIGN CAPACITY</span></td><td>40,000 mWh</td></tr>
                <tr><td><span class="label">FULL CHARGE CAPACITY</span></td><td>38,000 mWh</td></tr>
                <tr><td><span class="label">CYCLE COUNT</span></td><td>123</td></tr>
                </table>
                <table>
                <tr><td><span class="label">DESIGN CAPACITY</span></td><td>20,000 mWh</td></tr>
                <tr><td><span class="label">FULL CHARGE CAPACITY</span></td><td>18,000 mWh</td></tr>
                <tr><td><span class="label">CYCLE COUNT</span></td><td>45</td></tr>
                </table>
                </body></html>
                """;
        BatteryHealthService.Health h = BatteryHealthService.parseHtml(html);
        assertEquals(60000, h.designCapacityMwh());
        assertEquals(56000, h.fullChargeCapacityMwh());
        assertEquals(93.3, h.healthPercent(), 0.1);
        assertTrue(h.cycleCount() == 123 || h.cycleCount() == 45, "first battery's cycle count");
    }

    /** Notification stub - tests must not pop real tray balloons. */
    static final NotificationService NOOP_NOTIFIER = new NotificationService() {
        @Override public void showInfo(String title, String message) {}
        @Override public void showWarning(String title, String message) {}
    };
}
