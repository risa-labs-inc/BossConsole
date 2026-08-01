<#
.SYNOPSIS
  Put the operator's "Last Session" workspace back after a benchmark sweep.

.DESCRIPTION
  run-boss-arm.ps1 overwrites ~/Documents/BOSS/workspaces/Last_Session.json with
  a single-browser-tab layout, because workspaces are shared across every BOSS
  install (dev mode included) and a restored terminal pane would contaminate the
  measurement. It takes a one-time backup the first time it does so; this puts
  that backup back.

  Close BOSS first: a running instance rewrites Last Session when it exits and
  would immediately undo this.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$path = Join-Path $env:USERPROFILE "Documents\BOSS\workspaces\Last_Session.json"
$backup = "$path.preperf-backup"

$running = Get-Process BOSS -ErrorAction SilentlyContinue
if ($running) {
    Write-Warning "BOSS is running (pid $($running.Id -join ', ')). It rewrites Last Session on exit -- quit it first, then re-run this."
    return
}

if (-not (Test-Path $backup)) {
    Write-Warning "No backup at $backup -- nothing to restore."
    return
}

Copy-Item $backup $path -Force
Remove-Item $backup -Force
Write-Host "Restored your Last Session workspace from the pre-benchmark backup." -ForegroundColor Green
