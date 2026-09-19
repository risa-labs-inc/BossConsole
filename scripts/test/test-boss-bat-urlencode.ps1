#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression tests for the boss.bat :urlencode subroutine (#1057).

The subroutine used to interpolate the raw CLI argument into a
single-quoted PowerShell string literal:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString('%str%')"

so a single quote in any argument closed the literal and executed whatever
followed it. The fix routes the value through the environment instead:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString([Environment]::GetEnvironmentVariable('str'))"

This harness exercises the fixed subroutine the way boss.bat calls it,
including the paren-balanced injection payload that fired on the old code.
It runs under pwsh on any OS (the script-tests job exports BOSS_TEST_PWSH),
and falls back to asserting the source shape when cmd.exe is unavailable.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$batPath = Join-Path $repoRoot 'boss.bat'

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

Assert-True ($bat -match [regex]::Escape("EscapeDataString([Environment]::GetEnvironmentVariable('str'))")) `
    'the :urlencode shim reads the value from the environment, not from the command line'

Assert-True (-not ($bat -match [regex]::Escape("EscapeDataString('%str%')"))) `
    'the :urlencode shim no longer interpolates %str% into the PowerShell string literal'

# --- Live behavior checks (need cmd.exe; skipped elsewhere) --------------

if ($env:OS -ne 'Windows_NT' -or -not (Get-Command cmd.exe -ErrorAction SilentlyContinue)) {
    Write-Output 'ok - cmd.exe unavailable, live :urlencode probes skipped (source-shape checks passed)'
    exit 0
}

# Build a probe batch that inlines the CURRENT :urlencode from boss.bat and
# calls it exactly the way the argumented verbs do.
$probe = Join-Path $env:TEMP ("boss-urlencode-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$marker = Join-Path $env:TEMP ("boss-urlencode-marker-" + [guid]::NewGuid().ToString('N'))

# Extract the :urlencode block verbatim from the shipped script.
$lines = Get-Content $batPath
$startIdx = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -eq ':urlencode') { $startIdx = $i; break }
}
Assert-True ($startIdx -ge 0) ':urlencode subroutine exists in boss.bat'
# Trim the block at its own goto :eof - when inlined at the top of a probe
# script, the subroutine's own end-of-subroutine jump would skip the probe
# body entirely (batch recursion trap).
$block = ($lines[$startIdx..($lines.Count - 1)] -join "`r`n")
$eofIdx = $block.IndexOf("goto :eof")
if ($eofIdx -ge 0) { $block = $block.Substring(0, $eofIdx + "goto :eof".Length) }

# The subroutine block goes BELOW the probe calls, exactly like boss.bat:
# batch falls into labels top-down, so an inlined subroutine above the calls
# runs as straight-line code first and recurses.
$probeBody = @"
@echo off
setlocal
set OUTVAR=
set MARKER=$marker
if exist "%MARKER%" del "%MARKER%"
call :urlencode "a'b!c%%d e" OUTVAR
echo PLAIN:[%OUTVAR%]
call :urlencode "x'); New-Item -ItemType File -Path ('%MARKER%'); ('" OUTVAR
echo INJECT:[%OUTVAR%]
if exist "%MARKER%" (echo INJECTION_OCCURRED) else (echo INJECTION_DEAD)
goto :done
$block
:done
endlocal
"@
Set-Content -Path $probe -Value $probeBody -Encoding Ascii

try {
    $output = & cmd.exe /c $probe 2>&1 | ForEach-Object { "$_" }

    $plain = ($output | Where-Object { $_ -like 'PLAIN:*' }) -replace '^PLAIN:\[?', ''
    $plain = $plain.TrimEnd(']').Trim()
    # Note: a literal % in the CALL argument collapses one batch-expansion layer
# before :urlencode sees it (call-time expansion, unchanged by this fix); the
# injection-relevant characters - quote, bang, space - must survive intact
# and the space must percent-encode. Quote chars are deliberately not
# percent-encoded by EscapeDataString (RFC 3986 unreserved), which is fine:
# the goal is the value never reaching the PS parser as code.
Assert-True ($plain -like "a'b!c*%20e") "mixed quote/bang/space argument survives and encodes (got: $plain)"

    # INJECTION_DEAD / INJECTION_OCCURRED is its own bare output line, not a
    # prefix of INJECT:[...], so match against the whole captured output.
    $verdict = ($output | Where-Object { $_ -match 'INJECTION_(DEAD|OCCURRED)' })
    Assert-True ($verdict -match 'INJECTION_DEAD') 'the paren-balanced injection payload that fired on the old code is encoded as data, not executed'

    Assert-True (-not (Test-Path $marker)) 'no marker file created by the injection payload'
} finally {
    Remove-Item $probe -ErrorAction SilentlyContinue
    Remove-Item $marker -ErrorAction SilentlyContinue
}

Write-Output 'ALL URLencode tests passed'
