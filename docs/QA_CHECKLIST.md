# Final QA Checklist (Phase 10 — Manual Run Before Tagging Release)

Run on real Windows 10/11 laptop, standard (non-admin) account + admin account where noted.

## Fresh install (no JDK)
- [ ] `gradlew.bat jpackageAppImage` creates `build/jpackage` or `%TEMP%/BatterySaver` image.
- [ ] `gradlew.bat jpackageExe` succeeds only if WiX installed (`choco install wixtoolset`); otherwise verify `zipImage` fallback.
- [ ] Installer runs standalone on machine with **no JDK** — app launches, shows battery % via `GetSystemPowerStatus`.

## AC plug/unplug (5s poll)
- [ ] Unplug AC — `Battery: – / AC: offline` updates within 5s (see `BatteryStatusService.java:18` 5s scheduler). No restart needed.
- [ ] Replug AC — flips to `online`. History appends to `%APPDATA%\BatterySaver\history.csv`.

## Sleep/resume
- [ ] Enable Power Saver toggle, sleep (lid close or `rundll32 powrprof.dll,SetSuspendState`), resume — toggle remains on (active flag persisted via `SettingsService`). If Modern Standby (S0) detected, verify Settings note visible: `ModernStandbyService.java`.

## Hotkey with tray minimized
- [ ] Default `Ctrl+Alt+B` toggles Power Saver while window minimized to tray. Verify hidden window pump `HotkeyService.java:46` is daemon `hotkey-pump` thread.
- [ ] Change hotkey in Settings (press new combo), Save, verify re-registration and `config.json` stores `hotkeyModifiers`/`hotkeyVk`.
- [ ] If hotkey already taken (e.g. by another app), app logs `RegisterHotKey failed` but does not crash; UI shows warning.

## Single-instance
- [ ] Launch exe twice — second exits in <1s, first writes `focus-me.flag` (`SingleInstanceGuard.java:42`), focuses window within 2s via `Main.java` focus poller. Check `%APPDATA%\BatterySaver\.lock` held.

## Uninstall cleanup
- [ ] Enable "Run at startup" (writes `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\BatterySaver` with `"path" --minimized`), verify via `reg query ... /v BatterySaver`.
- [ ] Uninstall — Run key removed. If not automatic, document manual `StartupService.disable()` needed in uninstaller script (jpackage `win-shortcut` does not auto-clean Run).

## Standard (non-admin) account
- [ ] Run as standard user — no unhandled exceptions. `PowerPlanService.java:12` catches `SecurityException` and shows banner "Power plan changes restricted..." instead of retry-loop.
- [ ] `BatteryHealthService.getHealth()` with WMI/powercfg blocked shows "requires elevated permissions" (`BatteryHealthService.java:22`) not crash. Verify 3s timeout on `ProcessBuilder` does not hang UI thread.

## Battery health edge
- [ ] On device where `powercfg /batteryreport` cycle-count empty (`CYCLE COUNT: -`), UI shows "not reported" not NPE (`BatteryHealthService.java:28` returns -1 → `Main.java: healthLabel` formats as "not reported").
- [ ] `powercfg /batteryreport` 3s timeout not hanging: kill `powercfg` process early and verify fallback.

## Phase 7-8 extras
- [ ] Charge limit 80% (set 60 via slider): charge to ≥80% while plugged in → one notification "consider unplugging", no repeat until <75% (`ChargeLimitReminder.java:9`).
- [ ] Portable mode: create `portable.flag` next to exe (or launch with `--portable`), verify `config.json` appears next to exe not `%APPDATA%`, startup checkbox disabled grey (`Main.java: portableBadge`).
- [ ] Process usage: after 30s, top-5 list shows averaged CPU% (e.g. `chrome 12.3%`), label "Estimated impact..." visible (`ProcessUsageService.java`).
- [ ] History: `history.csv` grows but `trimTo30Days()` caps at 30 days; trend label shows "Battery drained X% in last hour" (`BatteryHistoryService.java`).
- [ ] Brightness EDR block: if antivirus blocks `powershell.exe`, `BrightnessService.java` returns `null` after 3s and UI shows unsupported state rather than hang.

## Release
- [ ] Tag `v*` pushes GitHub Action `windows-latest` build (`release.yml`). Artifact `BatterySaver-installer` contains `.exe`.
- [ ] Update check disabled by default; enable toggle + set `user/repo` slug, restart with internet → tray notice if newer tag exists (`UpdateCheckService.java`); offline → no crash.
- [ ] Self-sign: `gradlew signExe` skips gracefully if `signtool` missing; document in `docs/signing.md`.
