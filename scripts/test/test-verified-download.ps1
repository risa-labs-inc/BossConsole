<#
.SYNOPSIS
    Regression harness for scripts/Get-VerifiedDownload.ps1 and the JRE
    acquisition gate in build-windows-standalone.bat.

.DESCRIPTION
    Uses only synthetic local archives and temporary directories - no real
    JRE is downloaded or executed, and no network access is required.

    Functional cases drive the helper through the same local-source path the
    batch file uses for a manually staged jre17.zip (stage to .part, verify
    SHA-256, promote), and a simulated consumer gate mirrors the batch file's
    "errorlevel 0 AND jre17.zip exists" check before extraction/execution.

    Coverage:
      * valid bytes promote and reach extraction/execution markers
      * tampered bytes are refused
      * truncated archives are refused
      * a failed fetch that leaves a stale .part or stale destination is
        never accepted
      * a wrong URL/hash pairing is refused
      * a missing/malformed expected hash fails closed
      * non-https URI schemes (http://, ftp://, ...) are rejected before any
        fetch is attempted
      * nothing is extracted or executed after any refusal
      * the .bat pins exactly one immutable URL + SHA-256 descriptor, gates
        promotion on the helper's exit code, and keeps fetch inputs in
        globals (JRE_SOURCE/JRE_SHA) instead of CALL arguments so cmd's
        argument re-expansion cannot corrupt a percent-bearing URL

    Run:  powershell -NoProfile -File scripts\test\test-verified-download.ps1
    Exit: 0 = all checks passed, 1 = at least one failure.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Check {
    param([string]$Name, [bool]$Condition)
    if ($Condition) {
        $script:Pass++
        Write-Host "  PASS  $Name"
    } else {
        $script:Fail++
        $script:Failures += $Name
        Write-Host "  FAIL  $Name"
    }
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$helper = Join-Path $repoRoot 'scripts/Get-VerifiedDownload.ps1'
$batFile = Join-Path $repoRoot 'build-windows-standalone.bat'

if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
    Write-Host "FATAL: helper not found: $helper"
    exit 1
}

$psExe = (Get-Process -Id $PID).Path
if (-not $psExe) {
    $psExe = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh' } else { 'powershell' }
}

# Invoke the helper exactly as the batch file does: a separate process whose
# exit code is the success signal. -NonInteractive turns any unexpected
# prompt (e.g. a mandatory-parameter bind) into an error instead of a hang.
function Invoke-Fetch {
    param(
        [AllowNull()][string]$Uri,
        [AllowNull()][string]$Sha,
        [string]$Dest
    )
    $out = & $psExe -NoProfile -NonInteractive -File $helper `
        -Uri $Uri -ExpectedSha256 $Sha -Destination $Dest 2>&1 | Out-String
    return @{ Code = $LASTEXITCODE; Output = $out }
}

# Mirrors build-windows-standalone.bat: extraction and the java.exe check
# only run when the fetch succeeded AND jre17.zip exists. Markers stand in
# for "Expand-Archive ran" and "java.exe was invoked" so a refusal can be
# proven to stop before either.
function Invoke-PackageGate {
    param([int]$FetchExitCode, [string]$PackageDir)
    $zip = Join-Path $PackageDir 'jre17.zip'
    if ($FetchExitCode -eq 0 -and (Test-Path -LiteralPath $zip -PathType Leaf)) {
        $temp = Join-Path $PackageDir 'temp'
        Expand-Archive -LiteralPath $zip -DestinationPath $temp -Force
        New-Item -ItemType File -Path (Join-Path $PackageDir 'extracted.marker') -Force | Out-Null
        $java = Get-ChildItem -LiteralPath $temp -Recurse -Filter 'java.exe' -File |
            Select-Object -First 1
        if ($java) {
            New-Item -ItemType File -Path (Join-Path $PackageDir 'executed.marker') -Force | Out-Null
        }
    }
}

function Assert-Refusal {
    param([string]$Name, $Result, [string]$PackageDir)
    $dest = Join-Path $PackageDir 'jre17.zip'
    Check "$Name - exit code non-zero" ($Result.Code -ne 0)
    Check "$Name - destination absent" (-not (Test-Path -LiteralPath $dest))
    Check "$Name - .part removed" (-not (Test-Path -LiteralPath "$dest.part"))
    Invoke-PackageGate -FetchExitCode $Result.Code -PackageDir $PackageDir
    Check "$Name - no extraction after refusal" `
        (-not (Test-Path -LiteralPath (Join-Path $PackageDir 'extracted.marker')))
    Check "$Name - no extracted tree after refusal" `
        (-not (Test-Path -LiteralPath (Join-Path $PackageDir 'temp')))
    Check "$Name - no execution after refusal" `
        (-not (Test-Path -LiteralPath (Join-Path $PackageDir 'executed.marker')))
}

function New-PackageDir {
    param([string]$Name)
    $d = Join-Path $tempRoot $Name
    New-Item -ItemType Directory -Path $d -Force | Out-Null
    return $d
}

# Build a synthetic "JRE" zip with a jdk-*/bin/java.exe layout, matching what
# the batch file's extract step looks for. Bytes differ per tag so hashes do.
function New-FakeJreZip {
    param([string]$Path, [string]$Tag)
    $jdkDir = Join-Path (Join-Path $tempRoot "src-$Tag") "jdk-17-fake-$Tag"
    $binDir = Join-Path $jdkDir 'bin'
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $binDir 'java.exe') -Value "fake-java-$Tag" -NoNewline
    Set-Content -LiteralPath (Join-Path $jdkDir 'release') -Value "tag=$Tag" -NoNewline
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    Compress-Archive -LiteralPath $jdkDir -DestinationPath $Path -Force
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('jre-fetch-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    Write-Host "== Fixture =="
    $zipA = Join-Path $tempRoot 'jreA.zip'
    $zipB = Join-Path $tempRoot 'jreB.zip'
    New-FakeJreZip -Path $zipA -Tag 'alpha'
    New-FakeJreZip -Path $zipB -Tag 'bravo'
    $hashA = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipA).Hash
    $hashB = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipB).Hash
    Check 'synthetic archives have distinct hashes' ($hashA -ne $hashB)

    Write-Host "== Case: valid bytes promote =="
    $pkg = New-PackageDir 'valid'
    $dest = Join-Path $pkg 'jre17.zip'
    $r = Invoke-Fetch -Uri $zipA -Sha $hashA -Dest $dest
    Check 'valid - exit code 0' ($r.Code -eq 0)
    Check 'valid - destination present' (Test-Path -LiteralPath $dest -PathType Leaf)
    Check 'valid - destination hash matches' `
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash -eq $hashA)
    Check 'valid - .part cleaned up' (-not (Test-Path -LiteralPath "$dest.part"))
    Invoke-PackageGate -FetchExitCode $r.Code -PackageDir $pkg
    Check 'valid - extraction ran (positive control)' `
        (Test-Path -LiteralPath (Join-Path $pkg 'extracted.marker'))
    Check 'valid - java.exe found and "executed" (positive control)' `
        (Test-Path -LiteralPath (Join-Path $pkg 'executed.marker'))

    Write-Host "== Case: tampered bytes refused =="
    $pkg = New-PackageDir 'tampered'
    $dest = Join-Path $pkg 'jre17.zip'
    $tampered = Join-Path $tempRoot 'jreA-tampered.zip'
    $bytes = [IO.File]::ReadAllBytes($zipA)
    $mid = [int]($bytes.Length / 2)
    $bytes[$mid] = $bytes[$mid] -bxor 0xFF
    [IO.File]::WriteAllBytes($tampered, $bytes)
    $r = Invoke-Fetch -Uri $tampered -Sha $hashA -Dest $dest
    Assert-Refusal -Name 'tampered' -Result $r -PackageDir $pkg

    Write-Host "== Case: truncated archive refused =="
    $pkg = New-PackageDir 'truncated'
    $dest = Join-Path $pkg 'jre17.zip'
    $truncated = Join-Path $tempRoot 'jreA-truncated.zip'
    $full = [IO.File]::ReadAllBytes($zipA)
    [IO.File]::WriteAllBytes($truncated, $full[0..([int]($full.Length / 2))])
    $r = Invoke-Fetch -Uri $truncated -Sha $hashA -Dest $dest
    Assert-Refusal -Name 'truncated' -Result $r -PackageDir $pkg

    Write-Host "== Case: failed fetch never accepts a leftover .part =="
    $pkg = New-PackageDir 'failed-download'
    $dest = Join-Path $pkg 'jre17.zip'
    # Simulate a previous killed run: a partial file sits at the .part path.
    Set-Content -LiteralPath "$dest.part" -Value 'partial-bytes-from-a-dead-download' -NoNewline
    $missing = Join-Path $tempRoot 'no-such-archive.zip'
    $r = Invoke-Fetch -Uri $missing -Sha $hashA -Dest $dest
    Assert-Refusal -Name 'failed-download' -Result $r -PackageDir $pkg

    Write-Host "== Case: stale .part is discarded, never promoted =="
    $pkg = New-PackageDir 'stale-part'
    $dest = Join-Path $pkg 'jre17.zip'
    Set-Content -LiteralPath "$dest.part" -Value 'stale-partial-bytes' -NoNewline
    $r = Invoke-Fetch -Uri $zipA -Sha $hashA -Dest $dest
    Check 'stale-part - exit code 0' ($r.Code -eq 0)
    Check 'stale-part - destination holds verified bytes, not the stale .part' `
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash -eq $hashA)

    Write-Host "== Case: stale destination is never trusted =="
    $pkg = New-PackageDir 'stale-dest'
    $dest = Join-Path $pkg 'jre17.zip'
    # An unverified file already sits where the gate will look for jre17.zip.
    Set-Content -LiteralPath $dest -Value 'unverified-leftover' -NoNewline
    $r = Invoke-Fetch -Uri $missing -Sha $hashA -Dest $dest
    Assert-Refusal -Name 'stale-dest' -Result $r -PackageDir $pkg

    Write-Host "== Case: wrong fallback/hash pairing refused =="
    $pkg = New-PackageDir 'wrong-pairing'
    $dest = Join-Path $pkg 'jre17.zip'
    $r = Invoke-Fetch -Uri $zipA -Sha $hashB -Dest $dest
    Assert-Refusal -Name 'wrong-pairing' -Result $r -PackageDir $pkg

    Write-Host "== Case: malformed expected hash fails closed =="
    $pkg = New-PackageDir 'bad-hash'
    $dest = Join-Path $pkg 'jre17.zip'
    $r = Invoke-Fetch -Uri $zipA -Sha 'deadbeef' -Dest $dest
    Assert-Refusal -Name 'bad-hash' -Result $r -PackageDir $pkg

    Write-Host "== Case: file:// URI source =="
    $pkg = New-PackageDir 'file-uri'
    $dest = Join-Path $pkg 'jre17.zip'
    $fileUri = $null
    [void][System.Uri]::TryCreate($zipA, [System.UriKind]::Absolute, [ref]$fileUri)
    $r = Invoke-Fetch -Uri $fileUri.AbsoluteUri -Sha $hashA -Dest $dest
    Check 'file-uri - exit code 0' ($r.Code -eq 0)
    Check 'file-uri - destination hash matches' `
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash -eq $hashA)

    Write-Host "== Case: non-https URI schemes rejected =="
    # Plain http:// (and any other non-https, non-file scheme) must be
    # refused before any fetch is attempted - no network is touched.
    foreach ($scheme in @('http://example.invalid/jre17.zip', 'ftp://example.invalid/jre17.zip')) {
        $pkg = New-PackageDir ("scheme-" + $scheme.Split(':')[0])
        $dest = Join-Path $pkg 'jre17.zip'
        $r = Invoke-Fetch -Uri $scheme -Sha $hashA -Dest $dest
        Assert-Refusal -Name "scheme($($scheme.Split(':')[0]))" -Result $r -PackageDir $pkg
        Check "scheme($($scheme.Split(':')[0])) - rejected before fetch" `
            ($r.Output -match 'URI scheme not allowed')
    }

    Write-Host "== Static checks on build-windows-standalone.bat =="
    $bat = Get-Content -LiteralPath $batFile -Raw

    $batUrl = $null
    $m = [regex]::Match($bat, '(?m)^set JRE_URL=(\S+)\s*$')
    if ($m.Success) { $batUrl = $m.Groups[1].Value }
    $batSha = $null
    $m = [regex]::Match($bat, '(?m)^set JRE_SHA=([0-9a-fA-F]+)\s*$')
    if ($m.Success) { $batSha = $m.Groups[1].Value }

    # Exactly one pinned artifact descriptor is allowed: a single immutable
    # release URL and its own SHA-256. Older patch-level fallbacks and any
    # moving "latest" URL are regression bugs - downloading must fail rather
    # than silently downgrade the bundled JRE's security patch level.
    # Descriptor checks run on non-comment lines only: comments legitimately
    # discuss "latest" URLs and old patch levels.
    $batCode = (($bat -split "`r?`n") | Where-Object { $_ -notmatch '^\s*(::|rem\b)' }) -join "`n"
    Check 'bat - exactly one pinned JRE descriptor (JRE_URL + JRE_SHA)' `
        ($null -ne $batUrl -and $null -ne $batSha -and `
         [regex]::Matches($bat, '(?m)^set JRE_URL').Count -eq 1 -and `
         [regex]::Matches($bat, '(?m)^set JRE_SHA').Count -eq 1)
    Check 'bat - hash is a well-formed SHA-256' ($batSha -match '^[0-9a-fA-F]{64}$')
    Check 'bat - URL is an immutable GitHub release asset' `
        ($null -ne $batUrl -and $batUrl -match `
         '^https://github\.com/adoptium/temurin17-binaries/releases/download/[^/]+/[^/]+\.zip$')
    Check 'bat - no moving/latest URL anywhere' ($batCode -notmatch 'latest')
    Check 'bat - no obsolete 17.0.13/12/11 fallback descriptor' `
        ($batCode -notmatch '17\.0\.13|17\.0\.12|17\.0\.11|11a61a94|646f1f60|4bafe2e9')
    Check 'bat - %2B in release tag is escaped as %%2B (first-pass parse)' `
        ($null -ne $batUrl -and $batUrl -match '%%2B')
    Check 'bat - no direct Invoke-WebRequest to the final jre17.zip' `
        ($bat -notmatch "Invoke-WebRequest[^\r\n]*-OutFile '[^']*jre17\.zip'")
    Check 'bat - every fetch is gated on the helper exit code' `
        ([regex]::Matches($bat, 'call :fetch_jre').Count -eq `
         [regex]::Matches($bat, 'if not errorlevel 1 goto :extract').Count)
    Check 'bat - helper is invoked with -File and -ExecutionPolicy Bypass' `
        ($bat -match 'powershell -NoProfile -ExecutionPolicy Bypass -File "[^"]*Get-VerifiedDownload\.ps1"')

    # CALL double-expansion contract: cmd re-expands %1/%2 inside a CALLed
    # subroutine, so an expanded percent-containing argument (the %2B in the
    # release URL, a staging path with %, a hash) would be corrupted. The
    # subroutine must take no arguments and read JRE_SOURCE/JRE_SHA globals.
    $batCodeLines = @($bat -split "`r?`n" | Where-Object { $_ -notmatch '^\s*(::|rem\b)' })
    $callIdx = @()
    for ($i = 0; $i -lt $batCodeLines.Count; $i++) {
        if ($batCodeLines[$i] -match 'call\s+:fetch_jre') { $callIdx += $i }
    }
    Check 'bat - fetch calls exist' ($callIdx.Count -ge 2)
    Check 'bat - no fetch call passes expanded arguments' `
        (@($callIdx | Where-Object { $batCodeLines[$_] -notmatch 'call\s+:fetch_jre\s*$' }).Count -eq 0)
    $allPreceded = $true
    foreach ($i in $callIdx) {
        $j = $i - 1
        while ($j -ge 0 -and $batCodeLines[$j] -match '^\s*$') { $j-- }
        if ($j -lt 0 -or $batCodeLines[$j] -notmatch '^\s*set\s+JRE_SOURCE=') { $allPreceded = $false }
    }
    Check 'bat - every fetch call is immediately preceded by set JRE_SOURCE=' $allPreceded
    Check 'bat - manual staging sources JRE_SOURCE from MANUAL_JRE' `
        ($batCode -match 'set\s+JRE_SOURCE=%MANUAL_JRE%')
    Check 'bat - download sources JRE_SOURCE from JRE_URL' `
        ($batCode -match 'set\s+JRE_SOURCE=%JRE_URL%')

    # Subroutine body: reads globals, contains no positional %N or %~...N
    # references (N = 1-9) that would re-expand a percent-bearing argument.
    # %~dp0 (arg 0, the script's own path) is legitimate and allowed.
    $subStart = -1
    for ($i = 0; $i -lt $batCodeLines.Count; $i++) {
        if ($batCodeLines[$i] -match '^\s*:fetch_jre\b') { $subStart = $i; break }
    }
    $subBody = if ($subStart -ge 0) { $batCodeLines[$subStart..($batCodeLines.Count - 1)] -join "`n" } else { '' }
    Check 'bat - fetch_jre subroutine exists' ($subStart -ge 0)
    Check 'bat - fetch_jre reads JRE_SOURCE global' ($subBody -match '-Uri "%JRE_SOURCE%"')
    Check 'bat - fetch_jre reads JRE_SHA global' ($subBody -match '-ExpectedSha256 "%JRE_SHA%"')
    Check 'bat - fetch_jre has no numbered positional-parameter references' `
        ($subBody -notmatch '%(~[a-zA-Z]*)?[1-9]')

    # Independently recorded from the publisher on 2026-09-20: the GitHub
    # release ".zip.sha256.txt" sidecar and the api.adoptium.net v3 assets
    # endpoint (package.checksum) both report this value. If the bat moves
    # to a newer release, update this pair from the same two sources and
    # require them to match.
    $expectedUrlTail = 'jdk-17.0.20.1%%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip'
    $expectedSha = 'bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352'
    Check 'bat - pinned URL is the current 17.0.20.1+1 artifact' `
        ($null -ne $batUrl -and $batUrl.EndsWith($expectedUrlTail))
    Check 'bat - pinned URL paired with its publisher SHA-256' ($batSha -eq $expectedSha)
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "== Summary: $script:Pass passed, $script:Fail failed =="
if ($script:Failures.Count -gt 0) {
    $script:Failures | ForEach-Object { Write-Host "   FAILED: $_" }
}
if ($script:Fail -gt 0) { exit 1 }
exit 0
