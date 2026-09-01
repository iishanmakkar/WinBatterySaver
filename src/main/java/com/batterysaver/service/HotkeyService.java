package com.batterysaver.service;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import javafx.application.Platform;

/**
 * Real hotkey implementation using a hidden native window via JNA.
 * RegisterHotKey requires a window message loop - JavaFX has no native pump.
 * Pattern: hidden window + dedicated daemon thread + GetMessage/DispatchMessage loop.
 */
public class HotkeyService {
    private static final int HOTKEY_ID = 1;
    private static final String CLASS_NAME = "BatterySaverHotkeyWnd";

    private HWND hwnd;
    private Thread pumpThread;
    private volatile boolean running = false;
    private WindowProc wndProc; // keep strong ref to avoid GC
    private Runnable onPress;

    // Legacy constructor for backward compat
    public HotkeyService(Runnable onPress) {
        this.onPress = onPress;
    }

    public HotkeyService() {
    }

    /**
     * Register a system-wide hotkey. Must be called once.
     *
     * @param modifiers WinUser MOD_* flags (e.g. MOD_CONTROL|MOD_ALT = 0x0003)
     * @param vk        virtual key code (e.g. 0x42 = 'B')
     * @param onPress   callback on hotkey press (dispatched to FX thread)
     */
    public synchronized void register(int modifiers, int vk, Runnable onPress) {
        if (running) unregister();
        this.onPress = onPress;
        running = true;

        pumpThread = new Thread(() -> {
            try {
                HINSTANCE hInst = Kernel32.INSTANCE.GetModuleHandle(null);

                wndProc = new WindowProc() {
                    @Override
                    public LRESULT callback(HWND hWnd, int uMsg, WPARAM wParam, LPARAM lParam) {
                        if (uMsg == WinUser.WM_HOTKEY) {
                            int id = wParam.intValue();
                            if (id == HOTKEY_ID && HotkeyService.this.onPress != null) {
                                // Ensure FX thread for UI mutations
                                if (Platform.isFxApplicationThread()) {
                                    HotkeyService.this.onPress.run();
                                } else {
                                    Platform.runLater(HotkeyService.this.onPress);
                                }
                            }
                            return new LRESULT(0);
                        } else if (uMsg == WinUser.WM_DESTROY) {
                            User32.INSTANCE.PostQuitMessage(0);
                            return new LRESULT(0);
                        }
                        return User32.INSTANCE.DefWindowProc(hWnd, uMsg, wParam, lParam);
                    }
                };

                WNDCLASSEX wc = new WNDCLASSEX();
                wc.cbSize = wc.size();
                wc.lpfnWndProc = wndProc;
                wc.hInstance = hInst;
                wc.lpszClassName = CLASS_NAME;

                // RegisterClassEx may fail if already registered - that's ok
                User32.INSTANCE.RegisterClassEx(wc);

                hwnd = User32.INSTANCE.CreateWindowEx(
                        0, CLASS_NAME, "BatterySaverHiddenWnd",
                        0, 0, 0, 0, 0,
                        null, null, hInst, null);

                if (hwnd == null) {
                    System.err.println("HotkeyService: CreateWindowEx failed: " + Kernel32.INSTANCE.GetLastError());
                    running = false;
                    return;
                }

                boolean ok = User32.INSTANCE.RegisterHotKey(hwnd, HOTKEY_ID, modifiers, vk);
                if (!ok) {
                    int err = Kernel32.INSTANCE.GetLastError();
                    System.err.println("HotkeyService: RegisterHotKey failed (err=" + err + ") - hotkey already in use?");
                    // Still run message loop so we can unregister cleanly later
                }

                MSG msg = new MSG();
                while (running) {
                    int ret = User32.INSTANCE.GetMessage(msg, null, 0, 0);
                    if (ret == 0 || ret == -1) break; // WM_QUIT or error
                    User32.INSTANCE.TranslateMessage(msg);
                    User32.INSTANCE.DispatchMessage(msg);
                }
            } catch (Exception e) {
                System.err.println("HotkeyService pump error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                cleanupNative();
            }
        }, "hotkey-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void cleanupNative() {
        try {
            if (hwnd != null) {
                // JNA 5.14 UnregisterHotKey takes Pointer, not HWND
                User32.INSTANCE.UnregisterHotKey(hwnd.getPointer(), HOTKEY_ID);
                User32.INSTANCE.DestroyWindow(hwnd);
                hwnd = null;
            }
            User32.INSTANCE.UnregisterClass(CLASS_NAME, Kernel32.INSTANCE.GetModuleHandle(null));
        } catch (Exception ignored) {}
    }

    public synchronized void unregister() {
        running = false;
        if (hwnd != null) {
            User32.INSTANCE.UnregisterHotKey(hwnd.getPointer(), HOTKEY_ID);
            // Wake the GetMessage loop
            User32.INSTANCE.PostMessage(hwnd, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
        }
        // Also post quit in case hwnd null
        User32.INSTANCE.PostQuitMessage(0);
        if (pumpThread != null) {
            pumpThread.interrupt();
            try { pumpThread.join(1000); } catch (InterruptedException ignored) {}
            pumpThread = null;
        }
        // Ensure native cleanup if thread didn't
        if (hwnd != null) cleanupNative();
    }

    // Legacy API delegates to real register with defaults Ctrl+Alt+B
    public void start() {
        if (running) return;
        int mods = WinUser.MOD_CONTROL | WinUser.MOD_ALT;
        int vk = 0x42; // 'B'
        // Try to load from SettingsService if available
        try {
            SettingsService.Config cfg = SettingsService.load();
            mods = cfg.hotkeyModifiers;
            vk = cfg.hotkeyVk;
        } catch (Exception ignored) {}
        register(mods, vk, onPress != null ? onPress : () -> {});
    }

    public void stop() {
        unregister();
    }

    public boolean isRunning() { return running; }
}
