package com.batterysaver.util;

import com.batterysaver.interop.Shell32Ext;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.util.List;

/** Elevation helpers: detect admin rights and relaunch the app elevated. */
public final class ElevationUtil {

    private static final int TOKEN_ELEVATION = 20;

    /** TOKEN_ELEVATION struct: a single DWORD (non-zero = elevated). */
    @Structure.FieldOrder({"TokenIsElevated"})
    public static class TokenElevationStruct extends Structure {
        public int TokenIsElevated;
    }

    private ElevationUtil() {}

    /**
     * True when the current process runs with an elevated (Administrator) token.
     * When NOT elevated, EcoQoS throttling is limited to normal-user processes:
     * Windows denies OpenProcess on elevated/system/service processes.
     */
    public static boolean isRunningElevated() {
        try {
            WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
            if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
                return false;
            }
            try {
                TokenElevationStruct info = new TokenElevationStruct();
                IntByReference returnLength = new IntByReference();
                if (!Advapi32.INSTANCE.GetTokenInformation(token.getValue(), TOKEN_ELEVATION, info, info.size(), returnLength)) {
                    return false;
                }
                return info.TokenIsElevated != 0;
            } finally {
                Kernel32.INSTANCE.CloseHandle(token.getValue());
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Launch an elevated copy of this application (UAC prompt).
     * Blocks until the user resolves the consent dialog.
     *
     * @return true when the elevated copy was actually launched (caller should
     *         exit this instance); false when declined/unavailable
     */
    public static boolean launchElevated() {
        try {
            String exe = ProcessHandle.current().info().command().orElse("");
            if (exe.isBlank()) return false;
            String name = exe.substring(exe.lastIndexOf('\\') + 1).toLowerCase();
            // java.exe can't be meaningfully relaunched elevated (no jar args survive) -
            // only the packaged exe supports the elevation restart
            if (name.equals("java.exe") || name.equals("javaw.exe")) return false;
            long result = Shell32Ext.INSTANCE.ShellExecute(
                    null, "runas", exe, null, null, Shell32Ext.SW_SHOWNORMAL);
            return result > 32;
        } catch (Throwable t) {
            System.err.println("launchElevated failed: " + t.getMessage());
            return false;
        }
    }
}
