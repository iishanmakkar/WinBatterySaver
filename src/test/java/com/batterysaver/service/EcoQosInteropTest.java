package com.batterysaver.service;

import com.batterysaver.interop.Kernel32Ext;
import com.batterysaver.interop.PowerThrottlingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EcoQoS interop regression test - verifies the raw JNA SetProcessInformation
 * round-trip (throttle ON then OFF) against a sacrificial child process.
 * This is the exact call path the throttling engines use; if it breaks
 * (JNA upgrade, struct layout, OS change) EcoQoS silently stops working.
 */
@EnabledOnOs(OS.WINDOWS)
public class EcoQosInteropTest {

    @Test
    void setProcessInformationRoundTrip() throws Exception {
        Process child = new ProcessBuilder("cmd.exe", "/c", "ping -n 30 127.0.0.1").start();
        try {
            Thread.sleep(500); // let it spawn
            long pid = child.pid();

            Kernel32Ext k32 = Kernel32Ext.INSTANCE;
            com.sun.jna.platform.win32.WinNT.HANDLE h = k32.OpenProcess(0x0200 | 0x1000, false, (int) pid);
            assertNotNull(h, "OpenProcess failed for own child process");

            PowerThrottlingState on = PowerThrottlingState.throttleOn();
            assertTrue(k32.SetProcessInformation(h, Kernel32Ext.ProcessPowerThrottling, on, on.size()),
                    "SetProcessInformation throttle-ON failed");

            PowerThrottlingState off = PowerThrottlingState.throttleOff();
            assertTrue(k32.SetProcessInformation(h, Kernel32Ext.ProcessPowerThrottling, off, off.size()),
                    "SetProcessInformation throttle-OFF failed");
        } finally {
            child.destroyForcibly();
        }
    }
}
