<#
.SYNOPSIS
  Compare two Speedometer 3.1 result files metric by metric.

.DESCRIPTION
  The headline score says which browser is slower; it does not say why. Every
  Speedometer 3.1 test contributes two metrics:

    .../Sync   time spent running the test's own JS and the layout it forces
    .../Async  time spent waiting for the browser to get the frame on screen
               (requestAnimationFrame, then a zero timeout)

  Those two point at different causes. A uniform Sync regression means the
  engine executes script/layout more slowly -- CPU clocks, JIT, or scheduling.
  An Async regression with Sync intact means script is fine and the FRAME
  PIPELINE is slow, which is the failure mode an off-screen (OFF_SCREEN)
  embedder is structurally exposed to: every frame is copied out of Chromium
  before it can be presented.

  Ratio > 1 means -Baseline is slower than -Candidate.

.EXAMPLE
  .\compare-suites.ps1 -Baseline results-win\baseline-1.json -Candidate results-win\edge-1.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Candidate,
    [int]$Top = 12
)

$ErrorActionPreference = "Stop"

function Read-Result([string]$path) {
    if (-not (Test-Path $path)) { throw "No such result file: $path" }
    Get-Content $path -Raw | ConvertFrom-Json
}

$a = Read-Result $Baseline
$b = Read-Result $Candidate

if (-not $a.suites -or -not $b.suites) {
    throw "One of these runs has no per-suite metrics; re-run with the CDP harness."
}

function Get-Metrics($result) {
    $map = @{}
    foreach ($p in $result.suites.PSObject.Properties) { $map[$p.Name] = [double]$p.Value.mean }
    return $map
}

$ma = Get-Metrics $a
$mb = Get-Metrics $b

# Totals by metric kind. Speedometer also emits per-suite rollups without a
# /Sync or /Async suffix; summing those alongside the leaves would double-count,
# so only the leaves are totalled.
function Sum-Kind($map, [string]$suffix) {
    ($map.Keys | Where-Object { $_.EndsWith($suffix) } | ForEach-Object { $map[$_] } | Measure-Object -Sum).Sum
}

$rows = @()
foreach ($kind in @("/Sync", "/Async")) {
    $sa = Sum-Kind $ma $kind
    $sb = Sum-Kind $mb $kind
    $rows += [pscustomobject]@{
        Metric        = $kind.TrimStart('/')
        BaselineMs    = [math]::Round($sa, 1)
        CandidateMs   = [math]::Round($sb, 1)
        Ratio         = if ($sb) { [math]::Round($sa / $sb, 2) } else { $null }
    }
}

Write-Host "`n$($a.browser) (score $($a.score), viewport $($a.viewport))" -ForegroundColor Cyan
Write-Host "vs $($b.browser) (score $($b.score), viewport $($b.viewport))" -ForegroundColor Cyan
Write-Host "`n--- totals (ratio > 1 = '$($a.browser)' is slower) ---"
$rows | Format-Table -AutoSize

# Per-test detail, so a uniform offset can be told apart from a specific
# signature (a handful of suites carrying the whole gap).
$shared = $ma.Keys | Where-Object { $mb.ContainsKey($_) -and $mb[$_] -gt 0.05 }
$detail = $shared | ForEach-Object {
    [pscustomobject]@{
        Metric      = $_
        BaselineMs  = [math]::Round($ma[$_], 2)
        CandidateMs = [math]::Round($mb[$_], 2)
        Ratio       = [math]::Round($ma[$_] / $mb[$_], 2)
    }
}

Write-Host "--- worst $Top for '$($a.browser)' ---"
$detail | Sort-Object Ratio -Descending | Select-Object -First $Top | Format-Table -AutoSize
Write-Host "--- best $Top for '$($a.browser)' ---"
$detail | Sort-Object Ratio | Select-Object -First $Top | Format-Table -AutoSize

$med = ($detail | Sort-Object Ratio)[[int][math]::Floor($detail.Count / 2)].Ratio
Write-Host ("median per-test ratio: {0}   (metrics compared: {1})" -f $med, $detail.Count) -ForegroundColor Green
