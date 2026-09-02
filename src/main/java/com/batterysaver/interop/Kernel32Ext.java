package com.batterysaver.interop;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * JNA interface for kernel32.dll extended with EcoQoS process throttling support.
 * This interface extends the project's established pattern (extends StdCallLibrary,
 * loads "kernel32" native library) and adds Windows EcoQoS (Efficiency Mode) support
 * via SetProcessInformation with PROCESS_POWER_THROTTLING_EXECUTION_SPEED.
 *
 * Hardware reality: full benefit needs Intel 10th-gen+ or AMD Ryzen 5000+ or
 * Qualcomm mobile chips, and Windows 11 22H2+. Works on older hardware with
 * reduced effect. Do not oversell in UI copy.
 *
 * Scan interval matching EnergyStarX approach: 2s scan cycle, frequent enough to
 * un-throttle the instant a background app gets focus, infrequent enough to not
 * itself waste CPU/battery.
 *
 * Known limitations documented (don't hide them):
 * - Mouse-related software may lag if not whitelisted
 * - Some taskbar enhancement tools may become unstable when their process is throttled
 *   while a tray icon is hovered — whitelist proactively
 * - Child processes don't auto-un-throttle just because their parent gets focus
 * - System/Session-0 processes silently skipped (OpenProcess fails)
 */
public interface Kernel32Ext extends StdCallLibrary {
    Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

    boolean GetSystemPowerStatus(com.batterysaver.interop.SystemPowerStatus result);

    // Execution state flags
    int ES_CONTINUOUS = 0x80000000;
    int ES_SYSTEM_REQUIRED = 0x00000001;
    int ES_DISPLAY_REQUIRED = 0x00000002;

    int SetThreadExecutionState(int esFlags);

    // Tick count for idle detection (milliseconds since boot)
    int GetTickCount();
    long GetTickCount64();

    // --- EcoQoS / Process throttling support ---

    /** OpenProcess access rights */
    int PROCESS_SET_INFORMATION = 0x0200;
    int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** Open a process with specified access rights.
     *  @param dwDesiredAccess access rights (e.g. PROCESS_SET_INFORMATION)
     *  @param bInheritHandle if the handle is inherited after process creation
     *  @param dwProcessId process ID to open
     *  @return process handle, or null for failure/access denied
     */
    com.sun.jna.platform.win32.WinNT.HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

    /** Set process information for EcoQoS/Efficiency Mode.
     *  @param hProcess process handle (from OpenProcess)
     *  @param processInformationClass must be ProcessPowerThrottling (value 4)
     *  @param processInformation PowerThrottlingState structure with throttle-on or throttle-off
     *  @param processInformationSize size of the PowerThrottlingState structure
     *  @return true if successful
     */
    boolean SetProcessInformation(com.sun.jna.platform.win32.WinNT.HANDLE hProcess, int processInformationClass,
                                   com.batterysaver.interop.PowerThrottlingState processInformation,
                                   int processInformationSize);

    /** Close a process handle opened by OpenProcess. */
    boolean CloseHandle(com.sun.jna.platform.win32.WinNT.HANDLE hProcess);

    /** Free a buffer returned by PowerGetActiveScheme (maps HLOCAL as Pointer). */
    com.sun.jna.Pointer LocalFree(com.sun.jna.Pointer hMem);

    /** EcoQoS execution speed throttling class index — stable enum value per
     *  processthreadsapi.h, matching EnergyStarX implementation. */
    int ProcessPowerThrottling = 4;
}