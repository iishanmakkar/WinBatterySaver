/* User32Ext.java — JNA interface for user32.dll
 * Extended with EcoQoS foreground window detection methods.
 * This is the project's established JNA interface pattern.
 */
package com.batterysaver.interop;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Extended user32 JNA interface with EcoQoS support methods.
 * Uses the project's standard JNA approach (extends StdCallLibrary,
 * loads "user32" native library).
 */
public interface User32Ext extends StdCallLibrary {
    User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

    // Hot-key modifier flags
    int MOD_CONTROL = 0x0002;
    int MOD_ALT       = 0x0001;
    int MOD_SHIFT     = 0x0004;

    boolean RegisterHotKey(long hWnd, int id, int fsModifiers, int vk);
    boolean UnregisterHotKey(long hWnd, int id);

    // Idle detection - correct signature: BOOL GetLastInputInfo(PLASTINPUTINFO)
    boolean GetLastInputInfo(LastInputInfo plii);

    /** Get foreground window handle */
    /** @return handle to foreground window, or null if none */
    com.sun.jna.platform.win32.WinDef.HWND GetForegroundWindow();

    /** Get window thread process ID
     *  @param hWnd window handle
     *  @param lpidProcess receives the process ID
     *  @return thread ID (DWORD)
     */
    int GetWindowThreadProcessId(com.sun.jna.platform.win32.WinDef.HWND hWnd, com.sun.jna.ptr.IntByReference lpidProcess);
}