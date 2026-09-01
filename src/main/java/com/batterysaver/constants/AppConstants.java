package com.batterysaver.constants;

/**
 * Rebrand: Windows Battery Saver (WBS)
 */
public final class AppConstants {
    public static final String APP_NAME = "Windows Battery Saver";
    public static final String APP_SHORT_NAME = "WBS";
    public static final String APP_FULL_NAME = "Windows Battery Saver (WBS)";
    public static final String VERSION = "1.0.0";
    public static final String WINDOW_TITLE_EXPANDED = "WBS - Windows Battery Saver";
    public static final String TRAY_TOOLTIP_PREFIX = "WBS";
    public static final String GITHUB_REPO = "iishanmakkar/WinBatterySaver";
    // Compact window size - real-software: 620x260 to prevent bottom clipping (WMC ~600x180, was 260x90 tiny)
    public static final int COMPACT_WIDTH = 620;
    public static final int COMPACT_HEIGHT = 260;
    // Expanded window size - WMC-like: wider, like WMC main 760x540
    public static final int EXPANDED_WIDTH = 760;
    public static final int EXPANDED_HEIGHT = 560;

    private AppConstants() {}
}
