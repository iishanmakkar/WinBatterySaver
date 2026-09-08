# WBS - trusted-CA code signing (the permanent fix for Smart App Control).
#
# Once the project has a real certificate-authority certificate, signing is:
#   gradlew releaseBundle signTrusted -PsignPfx=C:\path\cert.pfx -PsignPfxPass=...
# or call this script directly per file:
#   powershell -File sign-trusted.ps1 -Pfx cert.pfx -PasswordEnv WBS_SIGN_PFX_PASS -Path some.exe
#
# Where to get a recognized-CA cert (see docs/signing.md for full detail):
#   - SignPath Foundation  : FREE for open-source projects (application + approval)
#   - Certum (SimplySign)  : ~69 EUR/yr, open-source code signing, trusted CA
#   - Azure Trusted Signing: $9.99/mo, Microsoft's own CA (best SAC/SmartScreen outcome)
param(
    [Parameter(Mandatory=$true)][string]$Path,     # exe to sign
    [string]$Pfx,                                  # path to PFX file
    [string]$PasswordEnv = 'WBS_SIGN_PFX_PASS',    # env var holding the PFX password
    [string]$Timestamp = 'http://timestamp.digicert.com'
)

$ErrorActionPreference = 'Stop'

# locate signtool (Windows SDK)
$signtool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match 'x64' } |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $signtool) { $signtool = 'signtool' }

$pass = [Environment]::GetEnvironmentVariable($PasswordEnv)
if (-not $pass) { throw "PFX password not found in env var $PasswordEnv" }

& $signtool sign /fd SHA256 /td SHA256 /tr $Timestamp /f $Pfx /p $pass $Path
if ($LASTEXITCODE -ne 0) { throw "signtool failed with exit $LASTEXITCODE" }

$status = (Get-AuthenticodeSignature -FilePath $Path).Status
Write-Output "SIGNED $Path -> $status"
