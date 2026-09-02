package com.batterysaver.util;

import com.batterysaver.interop.User32Ext;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.HashSet;
import java.util.Set;

/**
 * Enumerates visible top-level windows and maps them to owning PIDs. Used by the
 * throttling engines to keep "background throttling" honest: a process that owns a
 * VISIBLE window is in active use and must never be throttled; one with only a
 * minimized window can be throttled but must be un-throttled when restored
 * (the 5s scan picks up the change).
 */
public final class VisibleWindows {

    private VisibleWindows() {}

    /**
     * @return set of PIDs that currently own at least one visible, non-minimized
     *         top-level window (i.e. apps the user can see right now)
     */
    public static Set<Long> visibleWindowPids() {
        Set<Long> pids = new HashSet<>();
        try {
            User32.INSTANCE.EnumWindows(new WinUser.WNDENUMPROC() {
                @Override
                public boolean callback(WinDef.HWND hwnd, com.sun.jna.Pointer data) {
                    try {
                        if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
                        // Skip tool windows / tooltips that are technically visible
                        long exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
                        if ((exStyle & User32Ext.WS_EX_TOOLWINDOW) != 0) return true;
                        // Minimized windows are NOT counted as visible (they are fair
                        // game for background throttling until restored)
                        if (User32Ext.INSTANCE.IsIconic(hwnd)) return true;
                        IntByReference pidRef = new IntByReference();
                        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
                        if (pidRef.getValue() > 0) pids.add((long) pidRef.getValue());
                    } catch (Throwable ignored) {
                    }
                    return true; // continue enumeration
                }
            }, null);
        } catch (Throwable t) {
            // Enumeration failed (non-Windows / restricted) - return empty = no extra exemption
        }
        return pids;
    }
}
