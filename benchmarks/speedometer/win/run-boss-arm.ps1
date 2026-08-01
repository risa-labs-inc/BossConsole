<#
.SYNOPSIS
  Measure the BOSS fluck browser on Speedometer 3.1 under one set of Chromium
  switches, on Windows.

.DESCRIPTION
  One "arm" = one BOSS_CHROMIUM_EXTRA_SWITCHES value, measured -Repeats times.
  Each repeat launches a FRESH BOSS process, because Chromium switches are read
  once at engine creation -- reusing a process would silently measure the
  previous arm's flags.

  The BOSS under test is a dev-mode instance (BOSS_DEV_MODE=true -> ~/.boss_debug),
  so it cannot lock, mutate, or be confused with the operator's own ~/.boss
  install, which may be running at the same time. Process cleanup matches on the
  worktree's executable path for the same reason: it must never kill the
  operator's BOSS.

  CAUTION on -Extra: Chromium's --enable-features / --disable-features are NOT
  additive -- the last occurrence wins. An arm that passes its own
  --disable-features REPLACES the platform default (CalculateNativeWinOcclusion),
  so every arm below that touches --disable-features restates it.

.EXAMPLE
  .\run-boss-arm.ps1 -Label baseline -Repeats 3
  .\run-boss-arm.ps1 -Label nobg -Extra "--disable-renderer-backgrounding" -Repeats 3
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$Extra = "",
    [int]$Repeats = 3,
    [int]$Iterations = 10,
    [int]$Port = 9222,
    [int]$SettleSeconds = 60,
    # Pinned explicitly rather than left to the platform default, so an arm always
    # measures the mode it says it does. HARDWARE_ACCELERATED is now the Windows
    # default (see JxBrowserConfig.renderingMode); OFF_SCREEN is the old behaviour
    # and remains the default on macOS and Linux.
    [ValidateSet("OFF_SCREEN", "HARDWARE_ACCELERATED")]
    [string]$RenderingMode = "OFF_SCREEN",
    [string]$Results = "results-win",
    [string]$AppExe = "$PSScriptRoot\..\..\..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
)

$ErrorActionPreference = "Stop"
$harness = Join-Path $PSScriptRoot "out"
$LastSessionPath = Join-Path $env:USERPROFILE "Documents\BOSS\workspaces\Last_Session.json"

# The operator's own Last Session lives at the path this script overwrites. Keep
# one pristine copy so it can be put back (restore-last-session.ps1).
$backup = "$LastSessionPath.preperf-backup"
if ((Test-Path $LastSessionPath) -and -not (Test-Path $backup)) {
    Copy-Item $LastSessionPath $backup
    Write-Host "Backed up your Last Session to $backup" -ForegroundColor Yellow
}

# Maximizing matters more than it looks: the fluck viewport is the BOSS window
# minus tab bar, toolbar and sidebar, and at this display's 150% scaling an
# un-maximized window lands well under Speedometer's 850x650 minimum. Every arm
# must also get the SAME viewport, or the flag comparison measures window size.
if (-not ("Win32Window" -as [type])) {
    Add-Type -Namespace Native -Name Win32Window -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
'@
}

function Show-Maximized {
    # Deliberately NOT keyed on the launched process. BOSS.exe is a launcher: the
    # window belongs to a different PID it spawns, so $proc.MainWindowHandle stays
    # zero forever and the maximize silently never happens. Scan every BOSS process
    # from THIS worktree instead, and take the first that owns a window.
    for ($t = 0; $t -lt 40; $t++) {
        $w = Get-Process BOSS -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase) -and $_.MainWindowHandle -ne 0 } |
            Select-Object -First 1
        if ($w) {
            [Native.Win32Window]::ShowWindow($w.MainWindowHandle, 3) | Out-Null   # SW_MAXIMIZE
            [Native.Win32Window]::SetForegroundWindow($w.MainWindowHandle) | Out-Null
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
# Only ever match BOSS processes launched from THIS worktree's build output.
$worktreeRoot = (Resolve-Path "$PSScriptRoot\..\..\..").Path

function Stop-DevBoss {
    $procs = Get-CimInstance Win32_Process -Filter "Name='BOSS.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase) }
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($procs) {
        # Chromium children outlive a killed host briefly and hold the profile lock;
        # the next launch would then fall back to a temp profile and measure something
        # subtly different.
        Start-Sleep -Seconds 3
    }
    # A killed instance leaves its single-instance descriptor behind. The next launch
    # finds it, forwards its command line to a process that no longer exists, and
    # exits -- which shows up here as "DevTools never came up". Dev dir only.
    Remove-Item "$env:USERPROFILE\.boss_debug\run\single-instance*" -Force -ErrorAction SilentlyContinue
}

function Wait-DevTools([int]$p, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-WebRequest -Uri "http://127.0.0.1:$p/json/version" -UseBasicParsing -TimeoutSec 3
            return $true
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    return $false
}

if (-not (Test-Path $AppExe)) {
    throw "BOSS.exe not found at $AppExe -- run: .\gradlew.bat :composeApp:createDistributable"
}

New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot $Results) | Out-Null
Write-Host "=== arm '$Label'  extra='$Extra'  repeats=$Repeats" -ForegroundColor Cyan

for ($i = 1; $i -le $Repeats; $i++) {
    Stop-DevBoss

    $env:BOSS_DEV_MODE = "true"
    # BOSS_DEV_MODE alone would drop the global log level to DEBUG
    # (BossLogger.configureFromEnvironment), and the BROWSER category logs on
    # navigation and frame events -- i.e. dev mode would be measured doing work a
    # production run never does. BOSS_LOG_LEVEL is checked first, so pin it.
    $env:BOSS_LOG_LEVEL = "INFO"
    $env:BOSS_BROWSER_REMOTE_DEBUGGING_PORT = "$Port"
    $env:BOSS_CHROMIUM_EXTRA_SWITCHES = $Extra
    $env:BOSS_RENDERING_MODE = $RenderingMode

    # Force a known layout: ONE full-width browser tab, nothing else.
    #
    # This is not tidiness. Workspaces live in ~/Documents/BOSS/workspaces and are
    # shared by every BOSS install, dev-mode included, so whatever the operator
    # last had open is what a dev run restores. A restored terminal pane next to
    # the browser both shrinks the fluck viewport (below Speedometer's 850x650
    # minimum here) and repaints continuously alongside it -- measuring the split
    # layout instead of the browser. Rewritten before EVERY launch because BOSS
    # saves "Last Session" back over this file when it exits cleanly.
    Copy-Item (Join-Path $PSScriptRoot "bench-last-session.json") $LastSessionPath -Force

    Write-Host "[$Label] run $i/$Repeats -- launching BOSS" -ForegroundColor DarkGray
    Start-Process -FilePath $AppExe | Out-Null

    try {
        if (-not (Wait-DevTools $Port 180)) {
            throw "DevTools port $Port never came up -- is BOSS_BROWSER_REMOTE_DEBUGGING_PORT wired in this build?"
        }
        # Speedometer 3.1 paces on requestAnimationFrame, which Chromium throttles
        # in a window the OS reports hidden. Raise BOSS and keep it raised.
        Start-Sleep -Seconds 5
        if (-not (Show-Maximized)) {
            Write-Warning "No BOSS main window appeared; the harness will refuse the run if it is hidden."
        }
        # Let the app settle before measuring. BOSS is still loading plugins,
        # starting services and checking for updates for a while after its window
        # appears; benchmarking into that startup burst measures the startup, not
        # the browser, and it is not what a user sees on an app they have had open.
        # Also gives the layout time to reach its final size.
        Start-Sleep -Seconds $SettleSeconds

        $out = Join-Path $PSScriptRoot "$Results\$Label-$i.json"
        & java -cp $harness SpeedometerCdp --name "fluck-$Label" --attach "$Port" `
            --iterations "$Iterations" --out $out
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "[$Label] run $i failed (exit $LASTEXITCODE)"
        }
    } finally {
        Stop-DevBoss
        Remove-Item Env:BOSS_CHROMIUM_EXTRA_SWITCHES -ErrorAction SilentlyContinue
        Remove-Item Env:BOSS_RENDERING_MODE -ErrorAction SilentlyContinue
        Remove-Item Env:BOSS_BROWSER_REMOTE_DEBUGGING_PORT -ErrorAction SilentlyContinue
        Remove-Item Env:BOSS_DEV_MODE -ErrorAction SilentlyContinue
    }
}

# Summarize this arm. Median, not mean: a single occluded or disturbed run would
# drag a mean and hide itself.
$scores = Get-ChildItem (Join-Path $PSScriptRoot "$Results") -Filter "$Label-*.json" |
    ForEach-Object { (Get-Content $_.FullName -Raw | ConvertFrom-Json) } |
    Where-Object { -not $_.occludedDuringRun } |
    ForEach-Object { [double]$_.score }
if ($scores) {
    $sorted = $scores | Sort-Object
    $median = $sorted[[int][math]::Floor($sorted.Count / 2)]
    Write-Host ("=== arm '{0}': runs={1} median={2} all={3}" -f `
            $Label, $scores.Count, $median, ($scores -join ' / ')) -ForegroundColor Green
} else {
    Write-Warning "=== arm '$Label': no usable runs"
}
