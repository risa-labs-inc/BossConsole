<#
.SYNOPSIS
  Paired OFF_SCREEN vs HARDWARE_ACCELERATED comparison for the fluck browser.

.DESCRIPTION
  Runs the two rendering modes ALTERNATELY, N times each, rather than N of one
  followed by N of the other. benchmark.md records why: on this class of machine
  a grouped sweep drifts enough (load, thermals) to invent or erase a 20-40%
  difference, and the run-to-run spread already observed here for a single fixed
  configuration is wide (7.5 to 11.5 on identical settings). Alternating puts
  each pair within a couple of minutes of the other, so drift hits both arms.

  Everything except the rendering mode is held constant: same build, same
  Chromium switches, same forced single-browser-tab layout, same settle time.

  Reported per pair AND as a median of the per-pair ratios -- a ratio of medians
  would hide a pair where the two arms disagreed.
#>
[CmdletBinding()]
param(
    [int]$Pairs = 3,
    [int]$Iterations = 10,
    [int]$SettleSeconds = 60
)

$ErrorActionPreference = "Stop"
$results = Join-Path $PSScriptRoot "results-win"

for ($i = 1; $i -le $Pairs; $i++) {
    foreach ($mode in @("OFF_SCREEN", "HARDWARE_ACCELERATED")) {
        $label = "pair$i-$($mode.ToLower())"
        & (Join-Path $PSScriptRoot "run-boss-arm.ps1") -Label $label -Repeats 1 `
            -Iterations $Iterations -SettleSeconds $SettleSeconds -RenderingMode $mode 2>&1 |
            Where-Object { $_ -notmatch '^\[fluck.*\d+s ' }
    }
}

Write-Host "`n============ PAIRED RENDERING-MODE RESULT ============" -ForegroundColor Cyan
$ratios = @()
for ($i = 1; $i -le $Pairs; $i++) {
    $osrFile = Join-Path $results "pair$i-off_screen-1.json"
    $hwaFile = Join-Path $results "pair$i-hardware_accelerated-1.json"
    if (-not (Test-Path $osrFile) -or -not (Test-Path $hwaFile)) {
        Write-Warning "pair $i incomplete - skipping"
        continue
    }
    $osr = (Get-Content $osrFile -Raw | ConvertFrom-Json)
    $hwa = (Get-Content $hwaFile -Raw | ConvertFrom-Json)
    if ($osr.occludedDuringRun -or $hwa.occludedDuringRun) {
        Write-Warning "pair $i had an occluded run - excluded"
        continue
    }
    $ratio = [math]::Round($hwa.score / $osr.score, 3)
    $ratios += $ratio
    "{0,-6} OFF_SCREEN {1,6}   HARDWARE_ACCELERATED {2,6}   gain {3}%" -f `
        "pair$i", $osr.score, $hwa.score, [math]::Round(100 * ($ratio - 1))
}

if ($ratios.Count) {
    $sorted = $ratios | Sort-Object
    $median = $sorted[[int][math]::Floor($sorted.Count / 2)]
    Write-Host ("`nmedian gain from HARDWARE_ACCELERATED: {0}%  (pairs: {1})" -f `
            [math]::Round(100 * ($median - 1)), ($ratios -join ', ')) -ForegroundColor Green
    $wins = ($ratios | Where-Object { $_ -gt 1 }).Count
    Write-Host "HARDWARE_ACCELERATED won $wins of $($ratios.Count) pairs." -ForegroundColor Green
} else {
    Write-Warning "No usable pairs."
}
