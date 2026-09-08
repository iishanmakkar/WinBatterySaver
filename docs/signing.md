# Code Signing — free paths, and what each one fixes

Two different problems, two different fixes:

| Problem | What users see | Fixed by |
|---|---|---|
| SmartScreen "unknown publisher" warning | "More info → Run anyway" — one extra click | Any signature incl. free self-signed (current) |
| **Smart App Control (SAC) hard block** | App silently refuses to run; no bypass offered | **Trusted CA signature only** |
| MSI blocked on locked-down systems | — | MSI + CA signature |

The current release uses a free self-signed cert (created once, reused every build —
`packaging/sign-cert.ps1`). The installed exe/MSI are signed, which satisfies the
SmartScreen click-through but NOT SAC. This document covers the free routes to a
**trusted** signature.

## Free route 1: SignPath Foundation (recommended — actually free)

[SignPath Foundation](https://signpath.org) issues free code-signing certificates to
open-source projects. The signature comes from a recognized CA, so SAC allows it and
SmartScreen stops warning once reputation accrues.

1. Apply at https://signpath.org — need: project URL, GitHub org/repo, description.
   Approval typically takes 1–2 weeks. The `iishanmakkar/WinBatterySaver` repo qualifies.
2. Once approved you get an API token + signing policy id on signpath.io.
3. Sign a release (from the repo root):

   ```powershell
   # one-time: pip install signpath-python (or use their REST API directly)
   # sign the installer exe produced by: gradlew releaseBundle
   python -m signpath sign \
     --artifact build/dist/WindowsBatterySaver-1.0.3.exe \
     --project-slug WinBatterySaver \
     --signing-policy <policy-id> \
     --api-token <token>
   ```

   Or wire it into the gradle build via `signTrusted` (see below).

## Free-adjacent route 2: Azure Trusted Signing ($9.99/mo, instant)

Not free, but the fastest Microsoft-native fix: individual identity validation is
usually same-day, and the cert chains to Microsoft's own CA — the single best SAC /
SmartScreen outcome. `az trusted-signing` signs from the CLI.

## Paid route 3: Certum / SSL.com (~€69–240/yr)

Certum's "Open Source Code Signing" is the cheapest traditional OV cert; EV certs
(~$300+/yr) give instant SmartScreen reputation but need a hardware token.

## Wiring a trusted cert into the build (already done)

`build.gradle` has a `signTrusted` task + `packaging/sign-trusted.ps1`. When a cert
exists, one command re-signs everything:

```powershell
gradlew releaseBundle signTrusted -PsignPfx=C:\path\cert.pfx
# password read from env var WBS_SIGN_PFX_PASS (override: -PsignPfxPassEnv=NAME)
```

For SignPath (no PFX), call their API/tooling per artifact instead of `signTrusted`.

## Current pipeline recap

- `gradlew releaseBundle` → signed exe installer + signed plain **.msi** + portable
  zip, all embedding the signed app image; SHA256 printed for release notes.
- The installer packs the SIGNED app image (`--app-image`), so the exe a user ends
  up running is signed too (1.0.3's first build embedded an unsigned copy — fixed).
- MSI is preferable on SAC machines: `msiexec` (Microsoft-signed) processes the
  package even where unsigned exe launchers are blocked.
