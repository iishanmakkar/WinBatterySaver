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

    // Hot-key modifier flags (used by HotkeyService config display)
    int MOD_CONTROL = 0x0002;
    int MOD_ALT       = 0x0001;
    int MOD_SHIFT     = 0x0004;

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

    // --- Window-state queries (missing from JNA's User32 in this version) ---

    /** Is the window minimized (iconic)? */
    boolean IsIconic(com.sun.jna.platform.win32.WinDef.HWND hWnd);

    /** WS_EX_TOOLWINDOW extended style bit (tool windows/tooltips). */
    int WS_EX_TOOLWINDOW = 0x00000080;
}