# Creates (once) or reuses the free WBS code-signing cert in CurrentUser\My.
# A persistent cert keeps the publisher identity stable across releases.
$ErrorActionPreference = "Stop"
$subject = "CN=Windows Battery Saver, O=WBS Open Source, C=IN"

$existing = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert |
    Where-Object { $_.Subject -eq $subject -and $_.NotAfter -gt (Get-Date) } |
    Sort-Object NotAfter -Descending | Select-Object -First 1

if ($existing) {
    Write-Output "CERT_THUMBPRINT=$($existing.Thumbprint)"
    Write-Output "CERT_REUSED=1"
    exit 0
}

$cert = New-SelfSignedCertificate -Subject $subject -KeyUsage DigitalSignature -FriendlyName "WBS Battery Saver Code Sign" -CertStoreLocation "Cert:\CurrentUser\My" -Type CodeSigningCert -NotAfter (Get-Date).AddYears(10)
Write-Output "CERT_THUMBPRINT=$($cert.Thumbprint)"
Write-Output "CERT_REUSED=0"
