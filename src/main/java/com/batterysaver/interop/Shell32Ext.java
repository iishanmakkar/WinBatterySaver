package com.batterysaver.interop;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * shell32.dll binding for launching processes with elevation (runas verb).
 * Used by the "Restart as Administrator" feature so EcoQoS can throttle
 * elevated and background-service processes too.
 */
public interface Shell32Ext extends StdCallLibrary {
    Shell32Ext INSTANCE = Native.load("shell32", Shell32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

    int SW_SHOWNORMAL = 1;

    /**
     * ShellExecuteW. With lpOperation="runas" this triggers the UAC consent flow
     * and launches the elevated copy; it returns only after the dialog is resolved.
     *
     * @return HINSTANCE as long - a value {@code > 32} means success;
     *         small values are error codes (5 = UAC declined, 2 = file not found)
     */
    long ShellExecute(HWND hwnd, String lpOperation, String lpFile, String lpParameters, String lpDirectory, int nShowCmd);
}
