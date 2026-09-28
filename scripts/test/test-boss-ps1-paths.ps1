#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression tests for boss.ps1's path resolution on the file, workspace and folder verbs.

The app resolves whatever a deep link carries against its OWN working directory
(CLICommandHandler does File(path).absoluteFile), never the caller's, so a relative
path forwarded verbatim reached it as "File not found" and opened nothing. `folder`
already resolved; `file` and `workspace` did not.

Resolve-BossPath is extracted from the shipped boss.ps1 and exercised directly, so
these checks never open a deep link and run on any OS with PowerShell. The verbs
themselves are covered by source-shape checks - invoking them for real would hand a
boss:// URL to the OS.

The bash half is covered by scripts/test/test-cli-shim-paths.sh, which drives the
real shim through a fake xdg-open. boss.bat cannot run on a Linux runner; its
quoting is covered by test-boss-bat-urlencode.ps1's source-shape checks.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptsDir = Split-Path -Parent $PSScriptRoot
$ps1Path = Join-Path $scriptsDir 'boss.ps1'

$failures = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        Write-Output "FAIL - $Message"
        $script:failures++
        return
    }
    Write-Output "ok - $Message"
}

function Assert-Equal {
    param([string]$Expected, [string]$Actual, [string]$Message)
    if ($Expected -ne $Actual) {
        Write-Output "FAIL - $Message"
        Write-Output "  expected: $Expected"
        Write-Output "  actual:   $Actual"
        $script:failures++
        return
    }
    Write-Output "ok - $Message"
}

Assert-True (Test-Path -LiteralPath $ps1Path) "boss.ps1 found at $ps1Path"
$source = Get-Content -LiteralPath $ps1Path -Raw

# --- Source shape: the three verbs must route through the helper ----------
# Checked by text because the verbs cannot be invoked without opening a link.

Assert-True ($source -match '(?m)^function Resolve-BossPath') 'boss.ps1 defines Resolve-BossPath'

foreach ($pair in @(
        @{ Verb = 'workspace'; Var = 'configPath' },
        @{ Verb = 'file'; Var = 'filePath' },
        @{ Verb = 'folder'; Var = 'folderPath' })) {
    $verbRegex = [regex]::new(
        ('"{0}"\s*\{{(.*?)\n    \}}' -f $pair.Verb),
        [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $match = $verbRegex.Match($source)
    Assert-True $match.Success ("the $($pair.Verb) branch was located")
    if ($match.Success) {
        Assert-True ($match.Groups[1].Value -match ('\$' + $pair.Var + '\s*=\s*Resolve-BossPath')) `
        ("the $($pair.Verb) branch resolves its argument through Resolve-BossPath")
        Assert-True ($match.Groups[1].Value -match ('EscapeDataString\(\$' + $pair.Var + '\)')) `
        ("the $($pair.Verb) branch encodes the resolved path, not the raw argument")
    }
}

# Invoke-SmartDetection is a function, not a switch branch, so the loop above cannot
# see it. It is the fourth caller: without it the unmapped-drive catch covers the
# three verbs and not `boss.ps1 <path>`.
$detectionRegex = [regex]::new(
    '(?m)^function Invoke-SmartDetection \{.*?
\}',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
$detectionMatch = $detectionRegex.Match($source)
Assert-True $detectionMatch.Success 'the Invoke-SmartDetection function was located'
if ($detectionMatch.Success) {
    Assert-True ($detectionMatch.Groups[0].Value -match '\$expandedPath\s*=\s*Resolve-BossPath') `
    'the bare-argument route resolves through Resolve-BossPath'
    Assert-True (-not ($detectionMatch.Groups[0].Value -match 'GetUnresolvedProviderPathFromPSPath')) `
    'the bare-argument route does not call the path API directly'
}

# --- Behaviour: run the shipped helper itself -----------------------------
# Extracted rather than dot-sourcing boss.ps1, which would execute its switch.

$helperRegex = [regex]::new(
    '(?m)^function Resolve-BossPath \{.*?\n\}',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
$helperMatch = $helperRegex.Match($source)
Assert-True $helperMatch.Success 'Resolve-BossPath could be extracted for execution'
if (-not $helperMatch.Success) { exit 1 }
. ([scriptblock]::Create($helperMatch.Value))

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("boss-ps1-paths-" + [guid]::NewGuid().ToString('N'))
$nested = Join-Path $root 'src'
New-Item -ItemType Directory -Path $nested -Force | Out-Null
$leaf = Join-Path $nested 'main.kt'
New-Item -ItemType File -Path $leaf -Force | Out-Null

Push-Location -LiteralPath $root
try {
    Assert-Equal $leaf (Resolve-BossPath (Join-Path 'src' 'main.kt')) 'a relative path resolves against the caller'
    Assert-Equal $leaf (Resolve-BossPath (Join-Path '.' (Join-Path 'src' 'main.kt'))) 'a dot-slash path resolves'
    Assert-Equal $leaf (Resolve-BossPath $leaf) 'an absolute path is returned unchanged'
    Assert-Equal (Join-Path $root 'missing.kt') (Resolve-BossPath 'missing.kt') 'a path that does not exist still resolves'

    # GetUnresolvedProviderPathFromPSPath does not glob, so wildcard characters in a
    # name survive literally rather than being expanded or dropped.
    Assert-Equal (Join-Path $root 'a[1].kt') (Resolve-BossPath 'a[1].kt') 'brackets in a name are literal'

    Push-Location -LiteralPath $nested
    try {
        Assert-Equal $leaf (Resolve-BossPath (Join-Path '..' (Join-Path 'src' 'main.kt'))) 'a dot-dot path collapses'
    }
    finally {
        Pop-Location
    }

    # An unmapped drive makes the API throw; the helper returns the argument so the
    # failure is reported by BOSS rather than as a raw PowerShell error. Only
    # meaningful where drive letters exist.
    # $env:OS rather than $IsWindows: the latter does not exist in Windows PowerShell
    # 5.1, and Set-StrictMode makes reading it a terminating error there.
    if ($env:OS -eq 'Windows_NT') {
        $unmapped = 'Q:\nope\main.kt'
        $used = (Get-PSDrive -PSProvider FileSystem | Select-Object -ExpandProperty Name) -contains 'Q'
        if ($used) {
            Write-Output 'ok - drive Q: exists here, unmapped-drive probe skipped'
        }
        else {
            Assert-Equal $unmapped (Resolve-BossPath $unmapped) 'an unmapped drive returns the argument unchanged'
        }
    }
    else {
        Write-Output 'ok - not Windows, unmapped-drive probe skipped'
    }
}
finally {
    Pop-Location
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) {
    Write-Output "$failures assertion(s) failed"
    exit 1
}
Write-Output 'boss.ps1 path tests passed'
