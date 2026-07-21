#!/usr/bin/env pwsh
<#
.SYNOPSIS
    BOSS CLI Launcher Script for PowerShell
    Version: {{VERSION}}
    Generated: {{BUILD_DATE}}

    Converts CLI commands to boss:// deep links and opens them

.DESCRIPTION
    This script provides a command-line interface for BOSS Console,
    converting commands to deep links and launching them via the OS.

.EXAMPLE
    boss.ps1 url https://example.com
    Opens the URL in Fluck browser

.EXAMPLE
    boss.ps1 terminal -Command "ls -la"
    Opens a terminal tab with the specified command

.EXAMPLE
    boss.ps1 folder C:\Projects\MyProject
    Opens the folder in the codebase plugin
#>

param(
    [Parameter(Position=0, Mandatory=$true)]
    [string]$Command,

    [Parameter(Position=1)]
    [string]$Argument,

    [Parameter()]
    [Alias("c")]
    [string]$CommandToRun
)

function Open-BossDeepLink {
    param([string]$DeepLink)

    try {
        Start-Process $DeepLink
    }
    catch {
        Write-Error "Failed to open deep link: $_"
        exit 1
    }
}

function Invoke-SmartDetection {
    param([string]$Arg)

    # Check if it's a URL (has protocol or common TLD)
    if ($Arg -match '^https?://') {
        # Has http:// or https:// prefix
        $encoded = [System.Uri]::EscapeDataString($Arg)
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
        return
    }
    elseif ($Arg -match '\.(com|org|net|edu|gov|io|co|dev|app|ai|tech|cloud|xyz|me)(/|$)') {
        # Looks like a domain, add https://
        $encoded = [System.Uri]::EscapeDataString("https://$Arg")
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
        return
    }

    # Resolve path (handles relative paths and ~)
    $expandedPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Arg)

    # Check if it's a file or directory
    if (Test-Path $expandedPath) {
        if (Test-Path $expandedPath -PathType Container) {
            # It's a directory
            $encoded = [System.Uri]::EscapeDataString($expandedPath)
            $deepLink = "boss://folder?path=$encoded"
            Open-BossDeepLink $deepLink
            return
        }
        elseif (Test-Path $expandedPath -PathType Leaf) {
            # It's a file
            $encoded = [System.Uri]::EscapeDataString($expandedPath)
            $deepLink = "boss://file?path=$encoded"
            Open-BossDeepLink $deepLink
            return
        }
    }

    # Could not detect type
    Write-Error "Error: Could not determine type for: $Arg"
    Write-Host ""
    Write-Host "Did you mean:"
    Write-Host "  boss.ps1 url $Arg      - Open as URL"
    Write-Host "  boss.ps1 file $Arg     - Open as file"
    Write-Host "  boss.ps1 folder $Arg   - Open as folder"
    Write-Host ""
    Write-Host "Run 'boss.ps1 --help' for usage information"
    exit 1
}

function Show-Help {
    Write-Host "BOSS CLI - Business Operating System Service"
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  boss.ps1 <url-or-path>             Auto-detect and open URL, file, or folder"
    Write-Host "  boss.ps1 <command> [arguments]     Run explicit command"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  url <url>              Opens a URL in Fluck browser"
    Write-Host "  workspace <config>     Loads a workspace configuration"
    Write-Host "  file <path>            Opens a file in the editor"
    Write-Host "  folder [path]          Opens a folder in codebase (defaults to home)"
    Write-Host "  terminal               Opens a terminal tab"
    Write-Host "  terminal -c <command>  Opens a terminal tab with command"
    Write-Host "  plugin <id>            Opens any plugin/panel by ID"
    Write-Host "  help                   Show this help message"
    Write-Host ""
    Write-Host "Smart Detection Examples:"
    Write-Host "  boss.ps1 google.com                 # Auto-detects as URL (adds https://)"
    Write-Host "  boss.ps1 https://github.com         # Auto-detects as URL"
    Write-Host "  boss.ps1 file.txt                   # Auto-detects as file (if exists)"
    Write-Host "  boss.ps1 C:\Downloads               # Auto-detects as folder"
    Write-Host "  boss.ps1 .                          # Auto-detects current directory"
    Write-Host ""
    Write-Host "Explicit Command Examples:"
    Write-Host "  boss.ps1 url https://example.com"
    Write-Host "  boss.ps1 workspace C:\myworkspace.json"
    Write-Host "  boss.ps1 file C:\path\to\file.kt"
    Write-Host "  boss.ps1 folder                       # Opens home directory"
    Write-Host "  boss.ps1 folder C:\path\to\project    # Opens specific directory"
    Write-Host "  boss.ps1 terminal"
    Write-Host "  boss.ps1 terminal -c 'dir'"
    Write-Host "  boss.ps1 plugin bookmarks"
    Write-Host "  boss.ps1 plugin secret-manager"
    Write-Host ""
}

# Main command handling
switch ($Command.ToLower()) {
    "url" {
        if ([string]::IsNullOrEmpty($Argument)) {
            Write-Error "Error: URL argument required"
            Write-Host "Usage: boss.ps1 url <url>"
            exit 1
        }
        $encoded = [System.Uri]::EscapeDataString($Argument)
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
    }

    "workspace" {
        if ([string]::IsNullOrEmpty($Argument)) {
            Write-Error "Error: Workspace config path required"
            Write-Host "Usage: boss.ps1 workspace <config>"
            exit 1
        }
        $encoded = [System.Uri]::EscapeDataString($Argument)
        $deepLink = "boss://workspace?config=$encoded"
        Open-BossDeepLink $deepLink
    }

    "file" {
        if ([string]::IsNullOrEmpty($Argument)) {
            Write-Error "Error: File path required"
            Write-Host "Usage: boss.ps1 file <path>"
            exit 1
        }
        $encoded = [System.Uri]::EscapeDataString($Argument)
        $deepLink = "boss://file?path=$encoded"
        Open-BossDeepLink $deepLink
    }

    "folder" {
        # Use HOME directory as default if no path provided
        if ([string]::IsNullOrEmpty($Argument)) {
            $folderPath = $HOME
        }
        else {
            # Expand relative paths (., .., ~, etc.) to full path
            $folderPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Argument)
        }
        $encoded = [System.Uri]::EscapeDataString($folderPath)
        $deepLink = "boss://folder?path=$encoded"
        Open-BossDeepLink $deepLink
    }

    "terminal" {
        if (-not [string]::IsNullOrEmpty($CommandToRun)) {
            # Terminal with command
            $encoded = [System.Uri]::EscapeDataString($CommandToRun)
            $deepLink = "boss://terminal?command=$encoded"
        }
        elseif (-not [string]::IsNullOrEmpty($Argument) -and ($Argument -eq "-c" -or $Argument -eq "--command")) {
            Write-Error "Error: Command argument required after -c"
            Write-Host "Usage: boss.ps1 terminal -c <command>"
            exit 1
        }
        else {
            # Plain terminal
            $deepLink = "boss://terminal"
        }
        Open-BossDeepLink $deepLink
    }

    "plugin" {
        if ([string]::IsNullOrEmpty($Argument)) {
            Write-Error "Error: Plugin ID required"
            Write-Host "Usage: boss.ps1 plugin <id>"
            exit 1
        }
        $encoded = [System.Uri]::EscapeDataString($Argument)
        $deepLink = "boss://plugin?id=$encoded"
        Open-BossDeepLink $deepLink
    }

    { $_ -in "help", "--help", "-h", "-?" } {
        Show-Help
    }

    default {
        # Try smart detection for URL, file, or folder
        Invoke-SmartDetection $Command
    }
}
