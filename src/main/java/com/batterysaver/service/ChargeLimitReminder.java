package com.batterysaver.service;

/**
 * Charge-limit reminder - notification-only, no OEM API to cap charging.
 * Default limit 80%, configurable 60-100 in Settings.
 */
public class ChargeLimitReminder {
    private volatile boolean alreadyNotifiedThisSession = false;

    public void check(int batteryPercent, boolean isCharging, int limitPercent, NotificationService notifier) {
        if (!isCharging) return;
        if (batteryPercent < 0 || batteryPercent > 100) return;
        if (limitPercent < 60) limitPercent = 60;
        if (limitPercent > 100) limitPercent = 100;

        if (isCharging && batteryPercent >= limitPercent && !alreadyNotifiedThisSession) {
            notifier.showInfo("Battery Saver",
                    "At " + batteryPercent + "% - consider unplugging to preserve battery health. (Limit: " + limitPercent + "%)");
            alreadyNotifiedThisSession = true;
        }
        if (batteryPercent < limitPercent - 5) {
            alreadyNotifiedThisSession = false; // re-arm once drops 5% below limit
        }
    }

    /** For tests */
    public boolean isAlreadyNotified() { return alreadyNotifiedThisSession; }
    public void reset() { alreadyNotifiedThisSession = false; }
}
