#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression tests for the boss.bat :urlencode and :detect_and_route subroutines (#1057, #1059).

The :urlencode subroutine used to interpolate the raw CLI argument into a
single-quoted PowerShell string literal:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString('%str%')"

so a single quote in any argument closed the literal and executed whatever
followed it. The fix routes the value through the environment instead:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString([Environment]::GetEnvironmentVariable('str'))"

:detect_and_route opens its own parse scope (#1059); a literal ! in any
argument must survive on the auto-detect path the same way it does on the
verb paths.

This harness exercises the fixed subroutines the way boss.bat calls them,
including the paren-balanced injection payload that fired on the old code
and a literal-! probe for the auto-detect path. It runs under pwsh on any
OS (the script-tests job exports BOSS_TEST_PWSH), and falls back to
asserting the source shape when cmd.exe is unavailable.
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

Assert-True ($bat -match [regex]::Escape("[Environment]::GetEnvironmentVariable('str')")) `
    'the :urlencode shim reads the value from the environment, not from the command line'

Assert-True (-not ($bat -match [regex]::Escape("EscapeDataString('%str%')"))) `
    'the :urlencode shim no longer interpolates %str% into the PowerShell string literal'

# :detect_and_route must open DisableDelayedExpansion too (#1059): the
# function does not read !var!, and EnableDelayedExpansion here would
# re-create the literal-!-eating defect on the auto-detect path that the
# top-level DisableDelayedExpansion just fixed.
$detectScopeRegex = [regex]'(?ms):detect_and_route\s*\r?\n(?:REM[^\r\n]*\r?\n)*setlocal (Disable|Enable)DelayedExpansion'
$detectScopeMatch = $detectScopeRegex.Match($bat)
Assert-True ($detectScopeMatch.Success) ':detect_and_route is followed by a setlocal scope line'
Assert-True ($detectScopeMatch.Groups[1].Value -eq 'Disable') `
    ':detect_and_route opens DisableDelayedExpansion (EnableDelayedExpansion would eat ! in the auto-detect path)'

# :detect_and_route must never read %arg% outside quotes. cmd substitutes
# the value into the line before it parses the line, so an unquoted read
# lets an & in the argument (a file named R&D.txt) end the command and run
# the rest as a second one. `echo %arg% | findstr` did that on every
# detection line. This is the check the Ubuntu leg can enforce; the live
# probes below show the same thing on cmd.exe.
function Get-UnquotedArgReads {
    param([string[]]$Lines)
    @($Lines | Where-Object {
        $_ -notmatch '^\s*REM\b' -and (($_ -replace '"[^"]*"', '') -match '%arg\b')
    })
}
$batLines = $bat -split "`r?`n"
$routeStart = [array]::IndexOf($batLines, ':detect_and_route')
Assert-True ($routeStart -ge 0) ':detect_and_route label found for the quoting check'
$routeLines = $batLines[$routeStart..($batLines.Count - 1)]
Assert-True (@(Get-UnquotedArgReads @('echo %arg% | findstr /i "^http://" >nul')).Count -eq 1) `
    'the quoting check flags the old `echo %arg% | findstr` line (so it can fail)'
$unquotedReads = @(Get-UnquotedArgReads $routeLines)
Assert-True ($unquotedReads.Count -eq 0) `
    ":detect_and_route reads %arg% only inside quotes (unquoted: $($unquotedReads -join ' || '))"
Assert-True (-not (@($routeLines | Where-Object { $_ -notmatch '^\s*REM\b' -and $_ -match 'findstr' }).Count)) `
    ':detect_and_route runs no findstr subshell'

# :urlencode must guard an EscapeDataString($null) (#1136): the fix casts
# [Environment]::GetEnvironmentVariable to [string] (so a missing var
# reads as '', not $null) and guards an empty value before the call.
# These are source-shape checks - no cmd.exe needed - so they run on
# every CI matrix row instead of being skipped on Ubuntu.
$urlencodeCmdLines = @($bat -split "`r?`n" | Select-String -Pattern 'for /f.*EscapeDataString')
Assert-True ($urlencodeCmdLines.Count -gt 0) ':urlencode still contains the powershell EscapeDataString call'
$urlencodeCmd = $urlencodeCmdLines[0].ToString()
Assert-True ($urlencodeCmd -match '\[string\]') `
    ':urlencode casts [Environment]::GetEnvironmentVariable to [string] so a missing var reads as empty, not $null'
Assert-True ($urlencodeCmd -match "if\s*\(\s*-not\s+\`$v\s*\)\s*\{\s*''\s*\}") `
    ':urlencode guards an empty value before calling [System.Uri]::EscapeDataString'

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
REM Match the real boss.bat top scope: DisableDelayedExpansion so a literal
REM ! in the call argument survives intact until :urlencode (which sets up
REM its own DisableDelayedExpansion block and never reads !var!) can hand
REM the value to PowerShell.
setlocal DisableDelayedExpansion
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

    $plain = $output | Where-Object { $_ -like 'PLAIN:*' } | Select-Object -First 1
    if ($null -eq $plain) {
        Write-Error 'ASSERTION FAILED: No PLAIN: line captured (probe did not emit the expected output)'
        exit 1
    }
    $plain = ($plain -replace '^PLAIN:\[?', '').TrimEnd(']').Trim()
    # Note: a literal % in the CALL argument collapses one batch-expansion layer
# before :urlencode sees it (call-time expansion, unchanged by this fix); the
# injection-relevant characters - quote, bang, space - must survive intact and
# the space must percent-encode. .NET 4.5+ Uri.EscapeDataString leaves the
# RFC 3986 unreserved set (A-Z a-z 0-9 - . _ ~) and these six mark characters
# alone unescaped: ' ( ) ! * - so the literal ' and ! pass through verbatim
# rather than being percent-encoded. The point of the fix is that they reach
# PowerShell as data, not as syntax.
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

# --- :detect_and_route probe (#1059) --------------------------------------

# Inline the CURRENT :detect_and_route from boss.bat. The function reaches
# :detect_url and :detect_domain by goto from inside, so the inlined block
# runs to end-of-file. The argument passed in ("xxxxx!yyyyy") contains a
# literal ! but no http(s)://, no TLD match and no existing file/folder,
# so :detect_and_route falls through to the "Could not detect type" branch
# which echoes the argument verbatim. With EnableDelayedExpansion on (the
# pre-#1059 default), the ! would be eaten at `set "arg=%~1"` and the echo
# would print "Error: Could not determine type for: xxxxyyyyy"; with
# DisableDelayedExpansion the ! survives and the probe passes.
$detectStartIdx = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -eq ':detect_and_route') { $detectStartIdx = $i; break }
}
Assert-True ($detectStartIdx -ge 0) ':detect_and_route subroutine exists in boss.bat'
$detectBlock = ($lines[$detectStartIdx..($lines.Count - 1)] -join "`r`n")

$detectProbe = Join-Path $env:TEMP ("boss-detect-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$detectBody = @"
@echo off
REM Match the real boss.bat top scope: DisableDelayedExpansion so a literal
REM ! in the call argument survives intact. :detect_and_route opens its own
REM DisableDelayedExpansion block at :227 (the same scope the shipped script
REM uses), so any future switch back to EnableDelayedExpansion there would
REM re-eat the ! and this probe would fail.
setlocal DisableDelayedExpansion
call :detect_and_route "xxxxx!yyyyy"
echo ROUTE_EXIT=%ERRORLEVEL%
goto :detect_done
$detectBlock
:detect_done
endlocal
"@
Set-Content -Path $detectProbe -Value $detectBody -Encoding Ascii

try {
    $detectOutput = & cmd.exe /c $detectProbe 2>&1 | ForEach-Object { "$_" }
    $errLine = $detectOutput | Where-Object { $_ -like 'Error: Could not determine type for: *' } | Select-Object -First 1
    if ($null -eq $errLine) {
        Write-Error 'ASSERTION FAILED: No "Error: Could not determine type" line captured (probe did not reach the no-match branch)'
        exit 1
    }
    Assert-True ($errLine -like 'Error: Could not determine type for: "xxxxx!yyyyy"') `
        "literal ! survives the auto-detect path (got: $errLine)"
} finally {
    Remove-Item $detectProbe -ErrorAction SilentlyContinue
}

# --- :detect_and_route file/folder probes (#1136) ------------------------
# The file/folder branches used to read %fullpath%/%ENCODED% inside an
# if (...) (...) else (...) block, so both expanded at parse time before
# the set/call that filled them. The fix is to goto out of the block the
# way :detect_url and :detect_domain already do, so the URL is built with
# the actual full path. Each probe exercises one branch: `boss ./file.txt`
# must reach boss://file?path=<real path> and `boss %TEMP%` must reach
# boss://folder?path=<real path>. Both inline the CURRENT :detect_and_route
# with `start` rewritten to `echo`, so the probe captures the URL without
# launching anything.

$fileProbe = Join-Path $env:TEMP ("boss-detect-file-" + [guid]::NewGuid().ToString('N') + '.txt')
Set-Content -Path $fileProbe -Value 'probe' -Encoding Ascii

$folderProbe = Join-Path $env:TEMP ("boss-detect-folder-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folderProbe | Out-Null

# Strip both the leading `start "" "boss://` and the trailing `"` so the
# probe prints just the URL. The trailing `"` is the closing quote of the
# `start "" "URL"` line and would otherwise leave a stray quote that
# `Length -gt 0` would still consider "non-empty" - the bug we are
# catching. The capture group eats everything up to the next `"`, so
# the closing quote is consumed in the same replacement.
$detectWithEcho = [regex]::Replace($detectBlock, 'start\s+""\s+"boss://([^"]*)"', 'echo boss://$1', [System.Text.RegularExpressions.RegexOptions]::Multiline)

$detectFileProbe = Join-Path $env:TEMP ("boss-detect-file-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$detectFileBody = @"
@echo off
setlocal DisableDelayedExpansion
call :detect_and_route "$fileProbe"
goto :detect_file_done
$detectWithEcho
$block
:detect_file_done
endlocal
"@
Set-Content -Path $detectFileProbe -Value $detectFileBody -Encoding Ascii

try {
    $detectFileOutput = & cmd.exe /c $detectFileProbe 2>&1 | ForEach-Object { "$_" }
    $fileLine = $detectFileOutput | Where-Object { $_ -like 'boss://file?path=*' } | Select-Object -First 1
    Assert-True ($null -ne $fileLine) '`boss ./file.txt` routes to boss://file?path= (not the pre-#1136 empty-path bug)'
    if ($null -ne $fileLine) {
        $fileUrl = ($fileLine -replace '^boss://file\?path=', '')
        Assert-True ($fileUrl.Length -gt 0) "`boss ./file.txt` opens boss://file?path= with a non-empty path (got: '$fileUrl')"
        Assert-True ([System.Uri]::UnescapeDataString($fileUrl) -eq $fileProbe) `
            "boss://file?path= round-trips through [Uri]::UnescapeDataString back to the probe path: got '$fileUrl', expected '$fileProbe'"
    }
} finally {
    Remove-Item $detectFileProbe -ErrorAction SilentlyContinue
    Remove-Item $fileProbe -ErrorAction SilentlyContinue
}

$detectFolderProbe = Join-Path $env:TEMP ("boss-detect-folder-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$detectFolderBody = @"
@echo off
setlocal DisableDelayedExpansion
call :detect_and_route "$folderProbe"
goto :detect_folder_done
$detectWithEcho
$block
:detect_folder_done
endlocal
"@
Set-Content -Path $detectFolderProbe -Value $detectFolderBody -Encoding Ascii

try {
    $detectFolderOutput = & cmd.exe /c $detectFolderProbe 2>&1 | ForEach-Object { "$_" }
    $folderLine = $detectFolderOutput | Where-Object { $_ -like 'boss://folder?path=*' } | Select-Object -First 1
    Assert-True ($null -ne $folderLine) '`boss %TEMP%` routes to boss://folder?path= (not the pre-#1136 empty-path bug)'
    if ($null -ne $folderLine) {
        $folderUrl = ($folderLine -replace '^boss://folder\?path=', '')
        Assert-True ($folderUrl.Length -gt 0) "`boss %TEMP%` opens boss://folder?path= with a non-empty path (got: '$folderUrl')"
        Assert-True ([System.Uri]::UnescapeDataString($folderUrl) -eq $folderProbe) `
            "boss://folder?path= round-trips through [Uri]::UnescapeDataString back to the probe path: got '$folderUrl', expected '$folderProbe'"
    }
} finally {
    Remove-Item $detectFolderProbe -ErrorAction SilentlyContinue
    Remove-Item $folderProbe -Recurse -ErrorAction SilentlyContinue
}

# --- :detect_and_route with & in the argument -----------------------------
# The detection checks used to be `echo %arg% | findstr`, so the payload
# below ran `echo side-effect>amp-marker.txt` as a second command, and a
# real file named R&D.txt routed nowhere. The probe runs from its own
# directory so the marker path has no spaces to break the old command, and
# it also checks that URL and domain detection give the answers they did.
$ampDir = Join-Path $env:TEMP ("boss-detect-amp-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $ampDir | Out-Null
$ampFile = Join-Path $ampDir 'R&D.txt'
Set-Content -Path $ampFile -Value 'probe' -Encoding Ascii
$ampMarker = Join-Path $ampDir 'amp-marker.txt'
$ampPayload = 'nosuch&echo side-effect>amp-marker.txt'

$ampProbe = Join-Path $env:TEMP ("boss-detect-amp-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$ampBody = @"
@echo off
cd /d "$ampDir"
setlocal DisableDelayedExpansion
call :detect_and_route "$ampPayload"
call :detect_and_route "R&D.txt"
call :detect_and_route "HTTP://Example.com/x"
call :detect_and_route "Example.COM"
goto :amp_done
$detectWithEcho
$block
:amp_done
endlocal
"@
Set-Content -Path $ampProbe -Value $ampBody -Encoding Ascii

try {
    $ampOutput = & cmd.exe /c $ampProbe 2>&1 | ForEach-Object { "$_" }
    Assert-True (-not (Test-Path $ampMarker)) 'an & in the argument does not run the rest as a second command'
    $ampErr = $ampOutput | Where-Object { $_ -like 'Error: Could not determine type for: *' } | Select-Object -First 1
    Assert-True ($ampErr -eq "Error: Could not determine type for: `"$ampPayload`"") `
        "the no-match branch prints the whole argument, & included (got: $ampErr)"
    $ampFileLine = $ampOutput | Where-Object { $_ -like 'boss://file?path=*' } | Select-Object -First 1
    Assert-True ($null -ne $ampFileLine) '`boss R&D.txt` routes to boss://file?path='
    if ($null -ne $ampFileLine) {
        $ampFileUrl = ($ampFileLine -replace '^boss://file\?path=', '')
        Assert-True ([System.Uri]::UnescapeDataString($ampFileUrl) -eq $ampFile) `
            "boss://file?path= for R&D.txt round-trips to the probe path: got '$ampFileUrl', expected '$ampFile'"
    }
    Assert-True (@($ampOutput) -contains 'boss://url?url=HTTP%3A%2F%2FExample.com%2Fx') `
        'an http:// prefix in any case is still detected as a URL and passed as-is'
    Assert-True (@($ampOutput) -contains 'boss://url?url=https%3A%2F%2FExample.COM') `
        'a TLD in any case is still detected as a domain and gets https://'
} finally {
    Remove-Item $ampProbe -ErrorAction SilentlyContinue
}

# Mutation check: the same payload through the OLD detection line does
# create the marker, so the probe above can tell the two apart.
$oldAmpProbe = Join-Path $env:TEMP ("boss-detect-amp-mutation-" + [guid]::NewGuid().ToString('N') + '.cmd')
$oldAmpBody = @"
@echo off
cd /d "$ampDir"
setlocal DisableDelayedExpansion
set "arg=$ampPayload"
echo %arg% | findstr /i "^http://" >nul
endlocal
"@
Set-Content -Path $oldAmpProbe -Value $oldAmpBody -Encoding Ascii
try {
    & cmd.exe /c $oldAmpProbe 2>&1 | Out-Null
    Assert-True (Test-Path $ampMarker) 'mutation check: the OLD `echo %arg% | findstr` line runs the payload'
} finally {
    Remove-Item $oldAmpProbe -ErrorAction SilentlyContinue
    Remove-Item $ampDir -Recurse -ErrorAction SilentlyContinue
}

# --- :detect_and_route mutation check (#1136) ----------------------------
# Run the same probe with the OLD parenthesized set/call (the bug we fixed
# in #1136) to confirm the assertions above would have caught the bug.
# In the OLD code `%fullpath%` was read inside the parens at parse time,
# before the `set` ran, so `echo boss://file?path=%fullpath%` printed an
# empty path - which the round-trip above now rejects.
$oldBlockFile = @"
if exist "%~f1\*" (
    set "fullpath=%~f1"
    echo boss://file?path=%fullpath%
)
"@
$oldFileProbe = Join-Path $env:TEMP ("boss-detect-file-mutation-" + [guid]::NewGuid().ToString('N') + '.cmd')
$oldFileBody = @"
@echo off
setlocal DisableDelayedExpansion
$oldBlockFile
endlocal
"@
Set-Content -Path $oldFileProbe -Value $oldFileBody -Encoding Ascii
try {
    $oldFileOutput = & cmd.exe /c $oldFileProbe 2>&1 | ForEach-Object { "$_" }
    $oldFileLine = $oldFileOutput | Where-Object { $_ -like 'boss://file?path=*' } | Select-Object -First 1
    if ($null -ne $oldFileLine) {
        $oldFileUrl = ($oldFileLine -replace '^boss://file\?path=', '')
        # The OLD block's mutation check: the URL path is empty, so the
        # round-trip assertion above would have caught it. Assert that here
        # so a future refactor that weakens the test is also caught.
        Assert-True ([System.Uri]::UnescapeDataString($oldFileUrl) -ne $fileProbe) `
            "mutation check: OLD parenthesized block produces an empty URL path (got: '$oldFileUrl')"
    }
} finally {
    Remove-Item $oldFileProbe -ErrorAction SilentlyContinue
}

Write-Output 'ALL URLencode tests passed'
