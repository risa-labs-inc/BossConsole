<#
.SYNOPSIS
  Interleaved A/B between two already-built BOSS distributions.

.DESCRIPTION
  For answering "did commit X cost performance?" on a machine whose absolute
  scores move by ~2x with ambient load. Measured on this box: one unchanged
  build scored 23.8 at one point in a session and 19.4 an hour later. Any
  comparison that runs all of A then all of B is measuring the hour, not the code.

  So both builds are staged on disk first and this alternates A, B, A, B... Each
  pair is minutes apart, so drift hits both arms, and the per-pair RATIO is the
  result rather than either absolute number.

  Build the two distributions before running, e.g.:
    git checkout <control>; ./gradlew :composeApp:createDistributable
    Copy-Item -Recurse composeApp\build\compose\binaries\main\app  ...\app-control
    git checkout <candidate>; ./gradlew :composeApp:createDistributable

  Keep both INSIDE the worktree: run-boss-arm.ps1 only ever kills BOSS processes
  whose path is under the worktree root, which is what stops it touching the
  operator's own install.
#>
[CmdletBinding()]
param(
    [int]$Pairs = 3,
    [int]$Iterations = 10,
    [int]$SettleSeconds = 60,
    [string]$LabelA = "control",
    [string]$LabelB = "head",
    [string]$AppA = "$PSScriptRoot\..\..\..\composeApp\build\compose\binaries\main\app-control\BOSS\BOSS.exe",
    [string]$AppB = "$PSScriptRoot\..\..\..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
)

$ErrorActionPreference = "Stop"
foreach ($p in @($AppA, $AppB)) {
    if (-not (Test-Path $p)) { throw "Missing build: $p" }
}
$results = Join-Path $PSScriptRoot "results-win"

for ($i = 1; $i -le $Pairs; $i++) {
    foreach ($arm in @(@{ n = $LabelA; x = $AppA }, @{ n = $LabelB; x = $AppB })) {
        & (Join-Path $PSScriptRoot "run-boss-arm.ps1") -Label "ab$i-$($arm.n)" -Repeats 1 `
            -Iterations $Iterations -SettleSeconds $SettleSeconds `
            -RenderingMode HARDWARE_ACCELERATED -AppExe $arm.x 2>&1 |
            Where-Object { $_ -notmatch '^\[fluck.*\d+s ' }
    }
}

Write-Host "`n============ PAIRED BUILD A/B ============" -ForegroundColor Cyan
$ratios = @()
for ($i = 1; $i -le $Pairs; $i++) {
    $fa = Join-Path $results "ab$i-$LabelA-1.json"
    $fb = Join-Path $results "ab$i-$LabelB-1.json"
    if (-not (Test-Path $fa) -or -not (Test-Path $fb)) { Write-Warning "pair $i incomplete"; continue }
    $a = Get-Content $fa -Raw | ConvertFrom-Json
    $b = Get-Content $fb -Raw | ConvertFrom-Json
    if ($a.occludedDuringRun -or $b.occludedDuringRun) { Write-Warning "pair $i occluded - excluded"; continue }
    $ratio = [math]::Round($b.score / $a.score, 3)
    $ratios += $ratio
    "{0,-6} {1,-8} {2,6}   {3,-8} {4,6}   delta {5}%" -f `
        "pair$i", $LabelA, $a.score, $LabelB, $b.score, [math]::Round(100 * ($ratio - 1))
}
if ($ratios.Count) {
    $sorted = $ratios | Sort-Object
    $median = $sorted[[int][math]::Floor($sorted.Count / 2)]
    Write-Host ("`nmedian {0} vs {1}: {2}%  (pairs: {3})" -f `
            $LabelB, $LabelA, [math]::Round(100 * ($median - 1)), ($ratios -join ', ')) -ForegroundColor Green
    Write-Host "Negative = $LabelB is slower." -ForegroundColor DarkGray
} else {
    Write-Warning "No usable pairs."
}
