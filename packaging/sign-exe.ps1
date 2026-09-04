# Signs one exe with the WBS code-signing cert (thumbprint = $args[0]) using a
# trusted timestamp server. Idempotent: skips files that already carry a valid signature.
param([Parameter(Mandatory=$true)][string]$Thumbprint,
      [Parameter(Mandatory=$true)][string]$Path)

$sig = Get-AuthenticodeSignature -FilePath $Path
if ($sig.Status -eq 'Valid') {
    Write-Output "AlreadySigned"
    exit 0
}
# jpackage marks the app-image launcher read-only - clear it or signing is denied
$readAttr = Get-Item -LiteralPath $Path
if ($readAttr.IsReadOnly) { $readAttr.IsReadOnly = $false }
$result = Set-AuthenticodeSignature -FilePath $Path `
    -Certificate (Get-Item "Cert:\CurrentUser\My\$Thumbprint") `
    -TimestampServer 'http://timestamp.digicert.com'
Write-Output $result.Status
