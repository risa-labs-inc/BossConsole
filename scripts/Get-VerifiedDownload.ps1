<#
.SYNOPSIS
    Downloads a file to a ".part" staging file, verifies its SHA-256, and
    only then promotes it to the destination.

.DESCRIPTION
    Fail-closed fetch used by build-windows-standalone.bat to acquire the
    bundled JRE. Guarantees:

      * The destination only ever contains bytes whose SHA-256 matched the
        independently recorded -ExpectedSha256 value for that artifact.
      * A missing or malformed expected hash refuses the fetch outright.
      * A failed or killed download never leaves an acceptable file behind:
        the staged ".part" is deleted on failure, and any pre-existing file
        at the destination is removed before fetching, so a later
        "does the file exist?" check cannot be fooled by a stale artifact.
      * A ".part" left behind by an earlier killed run is deleted, never
        promoted.

    -Uri accepts https:// URLs as well as local paths and file:// URIs.
    Every other URI scheme (including plain http://) is rejected outright.
    The local-source mode exists so a manually staged jre17.zip and the
    regression harness exercise the exact same staging, hashing and
    promotion path as a real download - without any network access.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Get-VerifiedDownload.ps1 `
        -Uri "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip" `
        -ExpectedSha256 "bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352" `
        -Destination "BOSS-Standalone-Windows\jre17.zip"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Uri,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSha256,

    [Parameter(Mandatory = $true)]
    [string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Uri = $Uri.Trim()
$ExpectedSha256 = $ExpectedSha256.Trim()
$partPath = "$Destination.part"

function Remove-IfPresent {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

try {
    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw "Expected SHA-256 is missing or malformed; refusing to fetch $Uri"
    }
    $expected = $ExpectedSha256.ToUpperInvariant()

    # Fail closed: a stale .part or a stale destination is never trusted.
    # Only bytes fetched and verified in this invocation may occupy the
    # destination path.
    Remove-IfPresent $partPath
    Remove-IfPresent $Destination

    $parent = Split-Path -Parent $Destination
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    if ($Uri -match '^https://') {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Write-Host "[INFO] Downloading $Uri"
        Invoke-WebRequest -Uri $Uri -OutFile $partPath -UserAgent 'Mozilla/5.0' -MaximumRedirection 5 -UseBasicParsing
    } else {
        $sourcePath = $Uri
        if ($Uri -match '^file://') {
            $sourcePath = ([uri]$Uri).LocalPath
        } elseif ($Uri -match '^[a-zA-Z][a-zA-Z0-9+.-]*:' -and $Uri -notmatch '^[a-zA-Z]:[\\/]') {
            # A URI scheme that is neither https nor file (e.g. plain http,
            # ftp) is rejected. A drive-letter path like C:\x is not a scheme.
            throw "URI scheme not allowed: $Uri - use https://, file://, or a local path"
        }
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Local source not found: $sourcePath"
        }
        Write-Host "[INFO] Staging $sourcePath"
        Copy-Item -LiteralPath $sourcePath -Destination $partPath -Force
    }

    if (-not (Test-Path -LiteralPath $partPath -PathType Leaf)) {
        throw "Fetch reported success but produced no file: $Uri"
    }
    if ((Get-Item -LiteralPath $partPath).Length -eq 0) {
        throw "Fetched file is empty: $Uri"
    }

    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $partPath).Hash
    if ($actual -ne $expected) {
        throw "SHA-256 mismatch for $Uri (expected $expected, got $actual); refusing to use the file"
    }

    Move-Item -LiteralPath $partPath -Destination $Destination -Force
    Write-Host "[SUCCESS] SHA-256 verified ($actual); promoted to $Destination"
    exit 0
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)"
    Remove-IfPresent $partPath
    Remove-IfPresent $Destination
    exit 1
}
