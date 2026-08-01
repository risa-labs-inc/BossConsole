<#
.SYNOPSIS
  Fast screen of candidate Chromium switch sets for the Windows fluck browser.

.DESCRIPTION
  A confirmation-grade arm (3 repeats x 10 iterations) costs ~10 minutes. This
  runs each candidate ONCE at a reduced iteration count purely to find which
  ones move the needle at all; anything promising is then re-run properly with
  run-boss-arm.ps1 before being believed. A single short run is not evidence --
  it is triage.

  Why these candidates, given what has already been ruled out on this machine:
    * The engine is NOT on a software rasterizer (ANGLE/D3D11, gpu_compositing
      enabled), so "turn the GPU on" flags have nothing to fix.
    * The renderer is NOT priority- or EcoQoS-throttled (measured: Normal /
      default), so --disable-renderer-backgrounding and friends are no-ops.
    * Idle rAF cadence and setTimeout(0) granularity match Edge exactly, so the
      frame clock and the Windows timer resolution are both fine.
  What is left is the cost of getting each painted frame OUT of Chromium, which
  in OFF_SCREEN mode means a readback into CPU memory every frame. These
  candidates attack that path from different directions.

  CAUTION: --disable-features is not additive in Chromium; the last occurrence
  wins. Every arm restates CalculateNativeWinOcclusion, which the Windows branch
  of FluckEngine.performanceSwitchesFor sets by default.
#>
[CmdletBinding()]
param(
    [int]$Iterations = 5,
    [int]$SettleSeconds = 45,
    [string[]]$Only = @()
)

$ErrorActionPreference = "Stop"
$occlusion = "--disable-features=CalculateNativeWinOcclusion"

$arms = [ordered]@{
    # Control. Must be re-measured in the same session as the candidates: this
    # machine's absolute numbers drift with thermals and background load, so a
    # baseline from an hour ago is not a safe reference.
    "s-baseline" = ""

    # If every frame ends up in CPU memory anyway, GPU compositing adds a
    # GPU->CPU readback per frame instead of removing work. Compositing on the
    # CPU can therefore be FASTER for an off-screen embedder. This is the
    # leading hypothesis and the reason CEF ships the same option.
    "s-nogpucomp" = "--disable-gpu-compositing $occlusion"

    # Same idea taken all the way: no GPU process at all.
    "s-nogpu" = "--disable-gpu $occlusion"

    # If the renderer main thread is stalling on frame submission rather than on
    # the copy itself, removing vsync pacing lets it run ahead.
    "s-novsync" = "--disable-gpu-vsync --disable-frame-rate-limit $occlusion"

    # ANGLE's D3D11 backend is what does the readback. The GL backend has a
    # different readback path; worth one run to see if it is cheaper here.
    "s-anglegl" = "--use-angle=gl $occlusion"
}

foreach ($label in $arms.Keys) {
    if ($Only.Count -gt 0 -and $Only -notcontains $label) { continue }
    & (Join-Path $PSScriptRoot "run-boss-arm.ps1") -Label $label -Extra $arms[$label] `
        -Repeats 1 -Iterations $Iterations -SettleSeconds $SettleSeconds 2>&1 |
        Where-Object { $_ -notmatch '^\[fluck.*\d+s ' }
}

Write-Host "`n================ SCREEN SUMMARY ================" -ForegroundColor Cyan
Get-ChildItem (Join-Path $PSScriptRoot "results-win") -Filter "s-*.json" -ErrorAction SilentlyContinue |
    ForEach-Object {
        $r = Get-Content $_.FullName -Raw | ConvertFrom-Json
        [pscustomobject]@{
            Arm      = $_.BaseName
            Score    = $r.score
            Valid    = $r.valid
            Occluded = $r.occludedDuringRun
            Viewport = $r.viewport
            Extra    = $r.extraArgs
        }
    } | Sort-Object Score -Descending | Format-Table -AutoSize -Wrap
Write-Host "Single short runs -- triage only. Re-run any winner with run-boss-arm.ps1 -Repeats 3." -ForegroundColor Yellow
