<#
.SYNOPSIS
  Launch the worktree build of BOSS as an isolated dev instance for manual checking.

.DESCRIPTION
  Kept as a script rather than a pasted one-liner because the launch needs half a
  dozen environment and state steps, and a long single line pasted into a shell
  pane is easy to mangle into a continuation prompt that silently never runs.

  Isolated via BOSS_DEV_MODE (~/.boss_debug), so it cannot disturb the operator's
  own ~/.boss install even while that one is running. Workspaces are the one thing
  the two DO share (~/Documents/BOSS/workspaces), so the operator's Last Session is
  backed up once and restore-last-session.ps1 puts it back.

.PARAMETER RenderingMode
  Leave empty for the platform default (HARDWARE_ACCELERATED on Windows). Pass
  OFF_SCREEN to compare against the old behaviour without a rebuild.
#>
[CmdletBinding()]
param(
    [ValidateSet("", "OFF_SCREEN", "HARDWARE_ACCELERATED")]
    [string]$RenderingMode = "",
    [string]$Session = "manual-check-session.json",
    [switch]$KeepCurrentSession
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$exe = Join-Path $root "composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
if (-not (Test-Path $exe)) {
    throw "Not built. Run: .\gradlew.bat :composeApp:createDistributable"
}

# Never touch the operator's own BOSS - match on this worktree's path only.
Get-CimInstance Win32_Process -Filter "Name='BOSS.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 4

# A killed instance leaves this behind; the next launch then forwards to a dead
# process and exits without ever showing a window.
Remove-Item "$env:USERPROFILE\.boss_debug\run\single-instance*" -Force -ErrorAction SilentlyContinue

$ls = Join-Path $env:USERPROFILE "Documents\BOSS\workspaces\Last_Session.json"
if (-not $KeepCurrentSession) {
    if ((Test-Path $ls) -and -not (Test-Path "$ls.preperf-backup")) {
        Copy-Item $ls "$ls.preperf-backup"
        Write-Host "Backed up your Last Session -> $ls.preperf-backup" -ForegroundColor Yellow
    }
    Copy-Item (Join-Path $PSScriptRoot $Session) $ls -Force
}

$env:BOSS_DEV_MODE = "true"
# Dev mode alone would force logging to DEBUG, which is not what a normal run does.
$env:BOSS_LOG_LEVEL = "INFO"
if ($RenderingMode) { $env:BOSS_RENDERING_MODE = $RenderingMode }
else { Remove-Item Env:BOSS_RENDERING_MODE -ErrorAction SilentlyContinue }

Start-Process -FilePath $exe

# Maximize once the window exists. BOSS.exe is a launcher, so the window belongs to
# a DIFFERENT pid than the one Start-Process returns - scan instead of tracking it.
if (-not ("Native.LaunchWin" -as [type])) {
    Add-Type -Namespace Native -Name LaunchWin -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
'@
}
$w = $null
for ($t = 0; $t -lt 120; $t++) {
    $w = Get-Process BOSS -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -and $_.MainWindowHandle -ne 0 } |
        Select-Object -First 1
    if ($w) { break }
    Start-Sleep -Milliseconds 500
}
if ($w) {
    [Native.LaunchWin]::ShowWindow($w.MainWindowHandle, 3) | Out-Null
    [Native.LaunchWin]::SetForegroundWindow($w.MainWindowHandle) | Out-Null
    $mode = if ($RenderingMode) { $RenderingMode } else { "platform default (HARDWARE_ACCELERATED on Windows)" }
    Write-Host "BOSS up, maximized. pid=$($w.Id)  rendering=$mode" -ForegroundColor Green
} else {
    Write-Warning "No BOSS window appeared within 60s."
}
