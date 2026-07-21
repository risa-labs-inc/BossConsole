Write-Host "=== Checking Local Code Signing Certificates ===" -ForegroundColor Green
Write-Host ""

Write-Host "1. Code Signing Certificates:" -ForegroundColor Yellow
Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.EnhancedKeyUsageList -match "Code Signing" } | Select-Object Subject, Thumbprint, @{Name="KeyUsage";Expression={$_.EnhancedKeyUsageList.FriendlyName -join ", "}} | Format-Table -AutoSize

Write-Host ""
Write-Host "2. All Certificates in Personal Store:" -ForegroundColor Yellow
Get-ChildItem Cert:\CurrentUser\My | Select-Object Subject, Thumbprint, @{Name="KeyUsage";Expression={$_.EnhancedKeyUsageList.FriendlyName -join ", "}} | Format-Table -AutoSize

Write-Host ""
Write-Host "3. DigiCert Certificate from CI (if present):" -ForegroundColor Yellow
$digiCertThumbprint = "1BBB2BBE34458937861F976D0ECAC51A78A3E0C3"
$digiCert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq $digiCertThumbprint }
if ($digiCert) {
    Write-Host "✅ Found DigiCert certificate locally!" -ForegroundColor Green
    $digiCert | Select-Object Subject, Thumbprint, @{Name="KeyUsage";Expression={$_.EnhancedKeyUsageList.FriendlyName -join ", "}} | Format-Table -AutoSize
} else {
    Write-Host "❌ DigiCert certificate NOT found locally" -ForegroundColor Red
}

Write-Host ""
Write-Host "4. Machine Store Code Signing Certificates:" -ForegroundColor Yellow
Get-ChildItem Cert:\LocalMachine\My | Where-Object { $_.EnhancedKeyUsageList -match "Code Signing" } | Select-Object Subject, Thumbprint, @{Name="KeyUsage";Expression={$_.EnhancedKeyUsageList.FriendlyName -join ", "}} | Format-Table -AutoSize

Write-Host ""
Write-Host "Press any key to exit..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")