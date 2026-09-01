/* Kernel32Throttle.java - JNA binding for SetProcessInformation (EcoQoS)
 * SetProcessInformation is not in JNA-platform default Kernel32 interface
 * Declared directly for PROCESS_POWER_THROTTLING execution speed control.
 */
package com.batterysaver.interop;

import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.platform.win32.WinNT.HANDLE;

public interface Kernel32Throttle extends com.sun.jna.win32.StdCallLibrary {
    Kernel32Throttle INSTANCE = Native.load("kernel32", Kernel32Throttle.class, W32APIOptions.DEFAULT_OPTIONS);

    int ProcessPowerThrottling = 4; // PROCESS_INFORMATION_CLASS enum value — stable, documented in processthreadsapi.h

    boolean SetProcessInformation(HANDLE hProcess, int processInformationClass,
                                   PowerThrottlingState processInformation, int processInformationSize);
}