# Code Signing — 0-Cost Options

Without a certificate every install triggers SmartScreen "unknown publisher" — harmless but hurts adoption.

## Option 1: Self-sign (0 cost, portfolio use) — RECOMMENDED for now

Generates a self-signed cert in your user store; does **not** remove SmartScreen for others but documents the process.

```powershell
# 1. Create self-signed code-signing cert (once)
New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=BatterySaver" -KeyUsage DigitalSignature -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddYears(5)

# 2. Find thumbprint
Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert | Format-List Subject, Thumbprint, NotAfter

# 3. Sign the exe (after jpackageExe)
signtool sign /fd SHA256 /sha1 <THUMBPRINT> /tr http://timestamp.digicert.com /td SHA256 build\jpackage\*.exe

# Verify
signtool verify /pa /v build\jpackage\*.exe
```

`build.gradle:signExe` will auto-run this if `signtool` is on PATH and a cert exists; otherwise it logs and skips.

## Option 2: OV code-signing cert (~$70–200/yr)
E.g. SSL.com, Sectigo. Removes SmartScreen after reputation builds (a few dozen installs). Same `signtool sign /fd SHA256 /a` command, cert provided as PFX/`/f`.

## Option 3: EV code-signing cert (~$300+/yr, hardware token)
Removes SmartScreen immediately, no reputation wait. Requires USB token; CI must use token-specific signing action.

## CI
- `signtool` is available on `windows-latest` runners.
- For public release, store PFX in GitHub Secrets and sign in workflow before `upload-artifact`.
- Current workflow (`release.yml`) builds unsigned; add a `Sign` step only after you have a cert.
