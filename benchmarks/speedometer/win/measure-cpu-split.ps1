<#
.SYNOPSIS
  Attribute CPU time between the BOSS host (JVM/Compose) and Chromium during a
  benchmark run.

.DESCRIPTION
  The flag screen came back flat, which says the remaining cost is not in
  Chromium's configuration. The next question is where it IS: inside the
  renderer, or in the host process that has to receive every off-screen frame.

  This samples per-process CPU-seconds around a run and reports the delta, split
  by role:

    BOSS.exe          the JVM: Compose UI, and the Java side of the off-screen
                      frame handoff
    boss-browser.exe  Chromium, further split by --type= (renderer, gpu-process,
                      browser, ...)

  Interpretation: a renderer that burns roughly the CPU-seconds you would expect
  from the workload, next to a host burning comparable or greater CPU, means the
  embedding is the cost -- and no Chromium switch will fix that. A renderer
  burning far more CPU than Edge's for the same work would instead point back
  inside the engine.

  Run this INSTEAD of run-boss-arm.ps1's own launch: it does the whole cycle.
#>
[CmdletBinding()]
param(
    [int]$Iterations = 10,
    [int]$Port = 9222,
    [int]$SettleSeconds = 60,
    [string]$Extra = "",
    [string]$Label = "cpusplit",
    [string]$AppExe = "$PSScriptRoot\..\..\..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
)

$ErrorActionPreference = "Stop"
$worktreeRoot = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$LastSessionPath = Join-Path $env:USERPROFILE "Documents\BOSS\workspaces\Last_Session.json"

function Get-CpuTable {
    $rows = @{}
    foreach ($p in Get-Process -Name BOSS, boss-browser -ErrorAction SilentlyContinue) {
        try {
            # Only this worktree's BOSS; the operator's own install must not be counted.
            if ($p.ProcessName -eq "BOSS" -and -not ($p.Path -and $p.Path.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase))) {
                continue
            }
            $key = "$($p.ProcessName)#$($p.Id)"
            $rows[$key] = $p.TotalProcessorTime.TotalSeconds
        } catch {
            # Process exited between enumeration and read; it cannot have done
            # meaningful work in the window we care about.
        }
    }
    return $rows
}

function Get-ProcRole([int]$procId, [string]$name) {
    if ($name -eq "BOSS") { return "BOSS host (JVM/Compose)" }
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue).CommandLine
    if ($cmd -match '--type=([a-zA-Z-]+)') { return "chromium:$($Matches[1])" }
    return "chromium:browser"
}

Get-CimInstance Win32_Process -Filter "Name='BOSS.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3
Remove-Item "$env:USERPROFILE\.boss_debug\run\single-instance*" -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $PSScriptRoot "bench-last-session.json") $LastSessionPath -Force

$env:BOSS_DEV_MODE = "true"
$env:BOSS_LOG_LEVEL = "INFO"
$env:BOSS_BROWSER_REMOTE_DEBUGGING_PORT = "$Port"
$env:BOSS_CHROMIUM_EXTRA_SWITCHES = $Extra

Start-Process -FilePath $AppExe | Out-Null

if (-not ("Native.Win32Window2" -as [type])) {
    Add-Type -Namespace Native -Name Win32Window2 -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
'@
}
for ($t = 0; $t -lt 60; $t++) {
    $w = Get-Process BOSS -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase) -and $_.MainWindowHandle -ne 0 } |
        Select-Object -First 1
    if ($w) {
        [Native.Win32Window2]::ShowWindow($w.MainWindowHandle, 3) | Out-Null
        [Native.Win32Window2]::SetForegroundWindow($w.MainWindowHandle) | Out-Null
        break
    }
    Start-Sleep -Milliseconds 500
}
Write-Host "settling ${SettleSeconds}s..." -ForegroundColor DarkGray
Start-Sleep -Seconds $SettleSeconds

$before = Get-CpuTable
$sw = [System.Diagnostics.Stopwatch]::StartNew()

& java -cp (Join-Path $PSScriptRoot "out") SpeedometerCdp --name "fluck-$Label" --attach "$Port" `
    --iterations "$Iterations" --out (Join-Path $PSScriptRoot "results-win\$Label.json")

$sw.Stop()
$after = Get-CpuTable

$wall = $sw.Elapsed.TotalSeconds
$rows = foreach ($key in $after.Keys) {
    $delta = $after[$key] - ($before[$key] | ForEach-Object { $_ })
    if ($null -eq $before[$key]) { $delta = $after[$key] }   # process started during the run
    if ($delta -le 0.05) { continue }
    $parts = $key.Split('#')
    [pscustomobject]@{
        Role     = Get-ProcRole ([int]$parts[1]) $parts[0]
        Pid      = [int]$parts[1]
        CpuSec   = [math]::Round($delta, 1)
        CoresAvg = [math]::Round($delta / $wall, 2)
    }
}

Write-Host "`n--- CPU attributed over $([math]::Round($wall,1))s of benchmark wall time ---" -ForegroundColor Cyan
$rows | Sort-Object CpuSec -Descending | Format-Table -AutoSize
$total = ($rows | Measure-Object CpuSec -Sum).Sum
$host_ = ($rows | Where-Object { $_.Role -like "BOSS host*" } | Measure-Object CpuSec -Sum).Sum
Write-Host ("total {0}s CPU  |  BOSS host {1}s ({2}%)  |  chromium {3}s" -f `
        [math]::Round($total, 1), [math]::Round($host_, 1),
        [math]::Round(100 * $host_ / [math]::Max($total, 0.01)), [math]::Round($total - $host_, 1)) -ForegroundColor Green

Get-CimInstance Win32_Process -Filter "Name='BOSS.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($worktreeRoot, [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
