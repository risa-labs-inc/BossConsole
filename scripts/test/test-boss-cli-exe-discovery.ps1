#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression tests for how the Windows CLI shims find BOSS.exe.

`boss status`, `doctor`, `mcp` and `plugin` are forwarded to BOSS.exe, so the shim has to find it.
Both shims looked only in %LOCALAPPDATA%\Programs\BOSS and %ProgramFiles%\BOSS, but the MSI installs
per user into %LOCALAPPDATA%\BOSS (perUserInstall in composeApp/build.gradle.kts), so on a default
install every forwarded command failed with "BOSS application binary not found". The installer
also lets the user choose the directory, which no fixed list can cover, so Tools > Install BOSS CLI
now writes the running app's own BOSS.exe into the boss.bat it installs, in place of the
`REM {{INSTALLED_EXE}}` line.

The live probes stand a renamed copy of cmd.exe in for BOSS.exe and call the shim with
`status /c echo PROBE-OK`: the stand-in prints PROBE-OK only if the shim found and ran that exact
file, whatever the Windows display language. They need cmd.exe and skip elsewhere; the
source-shape checks run everywhere.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptsDir = Split-Path -Parent $PSScriptRoot
$batPath = Join-Path $scriptsDir 'boss.bat'
$ps1Path = Join-Path $scriptsDir 'boss.ps1'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        Write-Error "ASSERTION FAILED: $Message"
        exit 1
    }
    Write-Output "ok - $Message"
}

# --- Source-shape checks (run everywhere, no cmd.exe needed) -------------

$bat = Get-Content $batPath -Raw
$perUserBat = $bat.IndexOf('if not defined BOSS_EXE if exist "%LOCALAPPDATA%\BOSS\BOSS.exe"')
$programsBat = $bat.IndexOf('if not defined BOSS_EXE if exist "%LOCALAPPDATA%\Programs\BOSS\BOSS.exe"')
$markerBat = $bat.IndexOf('REM {{INSTALLED_EXE}}')
Assert-True ($perUserBat -ge 0) 'boss.bat looks in the MSI per-user directory, %LOCALAPPDATA%\BOSS'
Assert-True ($programsBat -gt $perUserBat) 'boss.bat tries the per-user default before the older guesses'
Assert-True ($markerBat -ge 0 -and $markerBat -lt $perUserBat) 'boss.bat carries the installer marker ahead of every guess'

$ps1 = Get-Content $ps1Path -Raw
$perUserPs = ([regex]::Matches($ps1, [regex]::Escape('"$env:LOCALAPPDATA\BOSS\BOSS.exe"'))).Count
$programsPs = ([regex]::Matches($ps1, [regex]::Escape('"$env:LOCALAPPDATA\Programs\BOSS\BOSS.exe"'))).Count
Assert-True ($perUserPs -eq 2 -and $programsPs -eq 2) 'boss.ps1 looks in the per-user directory in both forwarding branches'
Assert-True ($ps1.IndexOf('LOCALAPPDATA\BOSS\BOSS.exe') -lt $ps1.IndexOf('LOCALAPPDATA\Programs\BOSS\BOSS.exe')) 'boss.ps1 tries it first'

# --- Live behavior checks (need cmd.exe; skipped elsewhere) --------------

if ($env:OS -ne 'Windows_NT' -or -not (Get-Command cmd.exe -ErrorAction SilentlyContinue)) {
    Write-Output 'ok - cmd.exe unavailable, live BOSS.exe discovery probes skipped (source-shape checks passed)'
    exit 0
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('boss-exe-probe-' + [guid]::NewGuid().ToString('N'))
$saved = @{ LOCALAPPDATA = $env:LOCALAPPDATA; ProgramFiles = $env:ProgramFiles; BOSS_EXE = $env:BOSS_EXE }

function New-StandIn([string]$path) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    Copy-Item (Join-Path $env:SystemRoot 'System32\cmd.exe') $path
}

# The layout Tools > Install BOSS CLI produces: boss.bat alone, with no boss.ps1 beside it, and with
# CRLF line endings as the packaged script has them. cmd.exe can misread labels in an LF-only batch
# file, and a checkout may have either, so the ending is fixed here rather than inherited.
function Install-Bat([string]$dir, [string]$content) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $target = Join-Path $dir 'boss.bat'
    [IO.File]::WriteAllText($target, ($content -replace "`r?`n", "`r`n"))
    $target
}

# Windows PowerShell 5.1 turns a native command's stderr into an error record under 'Stop', and the
# "binary not found" probe below writes exactly there; the shim's output is what is asserted on.
function Invoke-Native([scriptblock]$command) {
    $ErrorActionPreference = 'Continue'
    (& $command 2>&1 | ForEach-Object { "$_" }) -join "`n"
}

function Invoke-Shim([string]$bat) {
    Invoke-Native { cmd.exe /c "`"$bat`" status /c echo PROBE-OK" }
}

try {
    $env:LOCALAPPDATA = Join-Path $root 'local'
    $env:ProgramFiles = Join-Path $root 'programfiles'
    Remove-Item Env:BOSS_EXE -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $env:LOCALAPPDATA, $env:ProgramFiles | Out-Null

    $shipped = Install-Bat (Join-Path $root 'bin') $bat
    $output = Invoke-Shim $shipped
    Assert-True ($output -match 'binary not found') 'with no BOSS.exe anywhere the shim says so, so the probes below can tell'

    New-StandIn (Join-Path $env:LOCALAPPDATA 'BOSS\BOSS.exe')
    $output = Invoke-Shim $shipped
    Assert-True ($output -match 'PROBE-OK') 'an installed boss.bat finds BOSS.exe in the MSI per-user directory'

    Remove-Item -Recurse -Force (Join-Path $env:LOCALAPPDATA 'BOSS')
    $chosen = Join-Path $root 'Chosen 100% Dir\BOSS\BOSS.exe'
    New-StandIn $chosen
    # What CLIInstaller.bossBatForInstall writes in place of the marker for this launcher path.
    $escaped = $chosen.Replace('%', '%%')
    $rewritten = $bat.Replace('REM {{INSTALLED_EXE}}', "if not defined BOSS_EXE if exist `"$escaped`" set `"BOSS_EXE=$escaped`"")
    $output = Invoke-Shim (Install-Bat (Join-Path $root 'bin-chosen') $rewritten)
    Assert-True ($output -match 'PROBE-OK') 'a boss.bat written by the installer finds BOSS.exe in a directory the user chose, even with % and a space in it'

    New-StandIn (Join-Path $env:LOCALAPPDATA 'BOSS\BOSS.exe')
    $output = Invoke-Native { powershell -NoProfile -ExecutionPolicy Bypass -File $ps1Path status /c echo PROBE-OK }
    Assert-True ($output -match 'PROBE-OK') 'boss.ps1 finds BOSS.exe in the MSI per-user directory'
}
finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $saved[$name] }
    }
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
