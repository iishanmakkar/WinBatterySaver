package com.batterysaver.interop;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * powrprof.dll bindings. PowerGetActiveScheme returns the active power scheme
 * GUID without spawning a powercfg.exe process (which the poller did twice
 * every 5 seconds before this existed). Caller must LocalFree the returned GUID.
 */
public interface PowrProfExt extends StdCallLibrary {
    PowrProfExt INSTANCE = Native.load("powrprof", PowrProfExt.class, W32APIOptions.DEFAULT_OPTIONS);

    /** @return ERROR_SUCCESS (0) on success; activePolicyGuid receives a GUID* to LocalFree */
    int PowerGetActiveScheme(Pointer hUserPowerKey, PointerByReference activePolicyGuid);
}
