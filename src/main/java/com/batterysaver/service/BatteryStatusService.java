package com.batterysaver.service;

import com.batterysaver.interop.Kernel32Ext;
import com.batterysaver.interop.SystemPowerStatus;
import com.batterysaver.model.Battery;
import javafx.application.Platform;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BatteryStatusService {
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static volatile Battery lastBattery = new Battery();
    private final ScheduledExecutorService scheduler;

    public BatteryStatusService() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "battery-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                SystemPowerStatus status = new SystemPowerStatus();
                if (Kernel32Ext.INSTANCE.GetSystemPowerStatus(status)) {
                    Battery b = new Battery();

                    // BatteryLifePercent is a BYTE: 0-100 = actual %, 255 = unknown
                    // Java byte is signed so 255 becomes -1; use toUnsignedInt()
                    int pctRaw = Byte.toUnsignedInt(status.BatteryLifePercent);
                    b.setPercent(pctRaw == 255 ? -1 : pctRaw);

                    // ACLineStatus: 0=offline, 1=online, 255=unknown
                    int acRaw = Byte.toUnsignedInt(status.ACLineStatus);
                    b.setOnAC(acRaw == 1);

                    if (status.BatteryLifeTime >= 0) {
                        b.setRemainingSeconds(status.BatteryLifeTime);
                    }

                    // BatteryFlag is a BYTE bitmask - must use unsigned int for bitwise ops
                    int flag = Byte.toUnsignedInt(status.BatteryFlag);
                    b.setHighFlag((flag & 1) != 0);
                    b.setLowFlag((flag & 2) != 0);
                    b.setCriticalFlag((flag & 4) != 0);
                    b.setChargingFlag((flag & 8) != 0);
                    b.setNoBatteryFlag((flag & 128) != 0);

                    lastBattery = b;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public static Battery getLastStatus() {
        return lastBattery;
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}