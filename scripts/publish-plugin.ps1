<#
.SYNOPSIS
    BOSS Plugin Store Publisher
    
.DESCRIPTION
    Publishes a plugin JAR to the BOSS Plugin Store.
    
.PARAMETER JarPath
    Path to the plugin JAR file (required)
    
.PARAMETER PluginId
    Plugin ID (e.g., "ai.rever.boss.plugin.myplugin")
    
.PARAMETER DisplayName
    Human-readable plugin name
    
.PARAMETER Version
    Semantic version (e.g., "1.0.0")
    
.PARAMETER Author
    Author name (optional)
    
.PARAMETER Description
    Plugin description (optional)
    
.PARAMETER Changelog
    Changelog for this version (optional)
    
.PARAMETER Tags
    Comma-separated tags (optional)
    
.PARAMETER StoreUrl
    Store URL (or set BOSS_PLUGIN_STORE_URL env var)

.PARAMETER HomepageUrl
    Public project homepage, required when creating a new plugin entry
    
.EXAMPLE
    .\publish-plugin.ps1 -JarPath "my-plugin.jar" -PluginId "my.plugin" -Version "1.0.0"
    
.EXAMPLE
    $env:BOSS_PLUGIN_STORE_TOKEN = "eyJ..."
    .\publish-plugin.ps1 -JarPath "my-plugin.jar"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$JarPath,
    
    [Parameter(Mandatory=$false)]
    [string]$PluginId,
    
    [Parameter(Mandatory=$false)]
    [string]$DisplayName,
    
    [Parameter(Mandatory=$false)]
    [string]$Version,
    
    [Parameter(Mandatory=$false)]
    [string]$Author,
    
    [Parameter(Mandatory=$false)]
    [string]$Description = "",
    
    [Parameter(Mandatory=$false)]
    [string]$Changelog = "",
    
    [Parameter(Mandatory=$false)]
    [string]$Tags = "",
    
    [Parameter(Mandatory=$false)]
    [string]$StoreUrl,
    
    [Parameter(Mandatory=$false)]
    [string]$AnonKey,

    [Parameter(Mandatory=$false)]
    [string]$HomepageUrl
)

# =============================================================================
# Configuration
# =============================================================================

$DefaultStoreUrl = "https://api.risaboss.com/functions/v1/plugin-store"
$TotalSteps = 5

# =============================================================================
# Helper Functions
# =============================================================================

function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    Write-Host $Message -ForegroundColor $Color
}

function Write-Error-Message {
    param([string]$Message)
    Write-Host "Error: $Message" -ForegroundColor Red
}

function Write-Success {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Write-Info {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Blue
}

function Write-Warning-Message {
    param([string]$Message)
    Write-Host "Warning: $Message" -ForegroundColor Yellow
}

function Write-Step {
    param(
        [int]$Step,
        [string]$Message
    )
    Write-Host "[$Step/$TotalSteps] $Message" -ForegroundColor Cyan
}

function Get-Sha256Hash {
    param([string]$FilePath)
    $hash = Get-FileHash -Path $FilePath -Algorithm SHA256
    return $hash.Hash.ToLower()
}

function Get-ManifestValue {
    param(
        [string]$JarPath,
        [string]$Key
    )
    
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        $manifestEntry = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/MANIFEST.MF" }
        
        if ($manifestEntry) {
            $stream = $manifestEntry.Open()
            $reader = New-Object System.IO.StreamReader($stream)
            $content = $reader.ReadToEnd()
            $reader.Close()
            $stream.Close()
            
            $lines = $content -split "`r?`n"
            foreach ($line in $lines) {
                if ($line -match "^${Key}:\s*(.+)$") {
                    $zip.Dispose()
                    return $matches[1].Trim()
                }
            }
        }
        
        $zip.Dispose()
        return $null
    }
    catch {
        return $null
    }
}

function Invoke-PluginStoreRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [string]$ContentType = "application/json"
    )
    
    $headers = @{}
    
    if ($script:AnonKey) {
        $headers["apikey"] = $script:AnonKey
    }
    
    if ($script:AuthToken) {
        $headers["Authorization"] = "Bearer $script:AuthToken"
    }
    
    $params = @{
        Method = $Method
        Uri = $Url
        Headers = $headers
        ContentType = $ContentType
        MaximumRedirection = 0
        ErrorAction = "Stop"
    }
    
    if ($Body -and $Method -ne "GET") {
        if ($ContentType -eq "application/json") {
            $params["Body"] = ($Body | ConvertTo-Json -Depth 10)
        }
        else {
            $params["Body"] = $Body
        }
    }
    
    try {
        Assert-PublishingUrl $Url
        $response = Invoke-RestMethod @params
        return @{
            Success = $true
            Data = $response
            StatusCode = 200
        }
    }
    catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        return @{
            Success = $false
            Error = if ($statusCode) { "HTTP $statusCode" } else { "Request failed" }
            StatusCode = $statusCode
            Data = $null
        }
    }
}

function Assert-PublishingUrl([string]$Value) {
    $uri = [Uri]$Value
    $local = $uri.Host -in @("localhost", "127.0.0.1", "[::1]")
    if (-not ($uri.Scheme -eq "https" -or ($uri.Scheme -eq "http" -and $local)) -or
        $uri.UserInfo -or $uri.Fragment) {
        throw "Invalid publishing URL"
    }
}

# =============================================================================
# Main Script
# =============================================================================

# Resolve token and URL
$script:AuthToken = $env:BOSS_PLUGIN_STORE_TOKEN
$script:StoreUrl = if ($StoreUrl) { $StoreUrl } else { 
    if ($env:BOSS_PLUGIN_STORE_URL) { $env:BOSS_PLUGIN_STORE_URL } else { $DefaultStoreUrl }
}
$script:AnonKey = if ($AnonKey) { $AnonKey } else { $env:SUPABASE_ANON_KEY }

# Validate required arguments
if (-not $JarPath) {
    Write-Error-Message "JAR path is required"
    exit 1
}

if (-not (Test-Path $JarPath)) {
    Write-Error-Message "JAR file not found: $JarPath"
    exit 1
}

if (-not $script:AuthToken) {
    Write-Error-Message "Authentication token is required"
    Write-Host "Set BOSS_PLUGIN_STORE_TOKEN in the environment."
    exit 1
}

Write-Host ""
Write-Info "BOSS Plugin Store Publisher"
Write-Host "============================================"
Write-Host ""

# Step 1: Extract metadata from JAR
Write-Step 1 "Reading JAR metadata..."

$JarFile = Get-Item $JarPath
$JarSize = $JarFile.Length
$JarSha256 = Get-Sha256Hash -FilePath $JarPath

if (-not $PluginId) {
    $PluginId = Get-ManifestValue -JarPath $JarPath -Key "Plugin-Id"
    if (-not $PluginId) {
        Write-Error-Message "Plugin ID not found in manifest. Use -PluginId parameter."
        exit 1
    }
    Write-Info "  Plugin ID: $PluginId (from manifest)"
}
else {
    Write-Info "  Plugin ID: $PluginId"
}

if (-not $DisplayName) {
    $DisplayName = Get-ManifestValue -JarPath $JarPath -Key "Plugin-Name"
    if (-not $DisplayName) {
        $DisplayName = $PluginId
        Write-Warning-Message "Display name not found, using plugin ID"
    }
    else {
        Write-Info "  Display Name: $DisplayName (from manifest)"
    }
}
else {
    Write-Info "  Display Name: $DisplayName"
}

if (-not $Version) {
    $Version = Get-ManifestValue -JarPath $JarPath -Key "Plugin-Version"
    if (-not $Version) {
        Write-Error-Message "Version not found in manifest. Use -Version parameter."
        exit 1
    }
    Write-Info "  Version: $Version (from manifest)"
}
else {
    Write-Info "  Version: $Version"
}

Write-Info "  JAR Size: $JarSize bytes"
Write-Info "  SHA256: $($JarSha256.Substring(0, 16))..."

Write-Host ""

# Step 2: Check if plugin exists
Write-Step 2 "Checking plugin existence..."

$encodedId = [Uri]::EscapeDataString($PluginId)
if ($encodedId -eq "." -or $encodedId -eq "..") { $encodedId = $encodedId.Replace(".", "%2E") }
$checkResult = Invoke-PluginStoreRequest -Method "GET" -Url "$($script:StoreUrl)/$encodedId"

if (-not $checkResult.Success -and $checkResult.StatusCode -ne 404) {
    Write-Error-Message "Plugin existence check failed: $($checkResult.Error)"
    exit 1
}

$PluginExists = $checkResult.Success -and $checkResult.StatusCode -eq 200

if ($PluginExists) {
    Write-Info "  Plugin exists, publishing new version"
}
else {
    Write-Info "  Plugin does not exist, will create new entry"
}

Write-Host ""

# Step 3: Create plugin entry if needed
if (-not $PluginExists) {
    Write-Step 3 "Creating plugin entry..."
    if (-not $HomepageUrl) {
        Write-Error-Message "Provide -HomepageUrl when creating a new plugin"
        exit 1
    }
    
    $tagsArray = @()
    if ($Tags) {
        $tagsArray = $Tags -split "," | ForEach-Object { $_.Trim() }
    }
    
    $publishBody = @{
        pluginId = $PluginId
        displayName = $DisplayName
        description = $Description
        homepageUrl = $HomepageUrl
        tags = $tagsArray
    }
    
    if ($Author) {
        $publishBody["authorName"] = $Author
    }
    
    $publishResult = Invoke-PluginStoreRequest -Method "POST" -Url "$($script:StoreUrl)/publish" -Body $publishBody
    
    if (-not $publishResult.Success) {
        Write-Error-Message "Failed to create plugin entry: $($publishResult.Error)"
        exit 1
    }
    
    Write-Success "  Plugin entry created"
}
else {
    Write-Step 3 "Skipping plugin creation (already exists)"
}

Write-Host ""

# Step 4: Create version and get upload URL
Write-Step 4 "Creating version entry..."

# minBossVersion drives the host-side update gate (PluginUpdateManager) and the
# loader check — hardcoding it would let old hosts pull updates they can't load.
$minBossVersion = "1.0.0"
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $pluginJsonEntry = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/boss-plugin/plugin.json" }
    if ($pluginJsonEntry) {
        $stream = $pluginJsonEntry.Open()
        $reader = New-Object System.IO.StreamReader($stream)
        $pluginJson = $reader.ReadToEnd() | ConvertFrom-Json
        $reader.Close()
        $stream.Close()
        if ($pluginJson.minBossVersion) {
            $minBossVersion = $pluginJson.minBossVersion
        }
    }
    $zip.Dispose()
}
catch {
    Write-Warning "Could not read minBossVersion from plugin.json, defaulting to 1.0.0"
}

$versionBody = @{
    version = $Version
    changelog = $Changelog
    minBossVersion = $minBossVersion
}

$versionResult = Invoke-PluginStoreRequest -Method "POST" -Url "$($script:StoreUrl)/$encodedId/version" -Body $versionBody

if (-not $versionResult.Success) {
    Write-Error-Message "Failed to create version: $($versionResult.Error)"
    exit 1
}

$VersionId = $versionResult.Data.versionId
$UploadUrl = $versionResult.Data.uploadUrl

if (-not $VersionId -or -not $UploadUrl) {
    Write-Error-Message "Failed to get version ID or upload URL from response"
    exit 1
}

Write-Success "  Version created"

Write-Host ""

# Step 5: Upload JAR file
Write-Step 5 "Uploading JAR file..."

try {
    Assert-PublishingUrl $UploadUrl
    $uploadResponse = Invoke-RestMethod -Method Put -Uri $UploadUrl -InFile $JarPath -ContentType "application/octet-stream" -MaximumRedirection 0 -ErrorAction Stop
    Write-Success "  JAR uploaded successfully"
}
catch {
    Write-Error-Message "Failed to upload JAR file"
    exit 1
}

Write-Host ""

# Step 6: Finalize version
Write-Step 5 "Finalizing version..."

$finalizeBody = @{
    versionId = $VersionId
    sha256 = $JarSha256
    jarSize = $JarSize
}

$finalizeResult = Invoke-PluginStoreRequest -Method "POST" -Url "$($script:StoreUrl)/version/finalize" -Body $finalizeBody

if (-not $finalizeResult.Success) {
    Write-Error-Message "Failed to finalize version: $($finalizeResult.Error)"
    exit 1
}

Write-Success "  Version finalized"

Write-Host ""
Write-Host "============================================"
Write-Success "Plugin published successfully!"
Write-Host ""
Write-Host "  Plugin ID: $PluginId"
Write-Host "  Version:   $Version"
Write-Host "  SHA256:    $($JarSha256.Substring(0, 32))..."
Write-Host ""
