# WBS - Smart App Control (SAC) checker
# Tells you if Windows Smart App Control will block WBS on this PC.
# SAC (Windows 11 22H2+, clean installs) blocks every app not signed by a
# certificate authority Microsoft recognizes - free/self-signed apps cannot
# run while SAC is On. This script only READS state; it changes nothing.
$ErrorActionPreference = "SilentlyContinue"

$state = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState).VerifiedAndReputablePolicyState

Write-Host ""
Write-Host "=== Windows Smart App Control status ==="
switch ($state) {
    0 { Write-Host "OFF - WBS runs normally. No action needed." -ForegroundColor Green }
    1 { Write-Host "ON (enforcing) - WBS and other free/self-signed apps are BLOCKED." -ForegroundColor Red
        Write-Host ""
        Write-Host "Your options:"
        Write-Host " 1. Turn SAC off: Settings > Privacy & security > Windows Security"
        Write-Host "    > App & browser control > Smart App Control settings > Off."
        Write-Host "    NOTE: SAC cannot be turned back on without reinstalling Windows."
        Write-Host "    Microsoft Defender stays on and still protects you."
        Write-Host " 2. Or skip installing WBS on this PC." }
    2 { Write-Host "EVALUATION - SAC is still deciding. WBS may run today and get" -ForegroundColor Yellow
        Write-Host "blocked later when evaluation ends. Same options as ON above." }
    default { Write-Host "Unknown ($state) - if WBS fails to launch with a 'Device Guard / Application"
        Write-Host "Control policy' message, use the ON options above." }
}
Write-Host ""
