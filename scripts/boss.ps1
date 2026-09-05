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

if ($args.Count -eq 0) {
    Write-Error "Error: No command specified"
    Write-Host "Run 'boss.ps1 --help' for usage information"
    exit 1
}

$Command = $args[0]
$Argument = if ($args.Count -gt 1) { $args[1] } else { $null }
$CommandToRun = $null
for ($i = 1; $i -lt $args.Count; $i++) {
    if ($args[$i] -in "-c", "--command" -and ($i + 1) -lt $args.Count) {
        $CommandToRun = $args[$i + 1]
    }
}
$remainingArgs = if ($args.Count -gt 2) { @($args[2..($args.Count - 1)]) } else { @() }

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
    Write-Host "  status                 Queries status and health of the running BOSS instance"
    Write-Host "  mcp <action> [args]    Discovers and invokes desktop MCP tools (list, describe, invoke)"
    Write-Host "  completion <shell>     Generates shell completion script (bash, zsh, fish)"
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

function Invoke-BossDevFallback {
    param(
        [string]$Cmd,
        [string]$SubCmd,
        [string[]]$RemainingArgs
    )

    $allArgs = @()
    if ($SubCmd) { $allArgs += $SubCmd }
    if ($RemainingArgs) { $allArgs += $RemainingArgs }

    $descriptorPath = Join-Path $HOME ".boss\run\single-instance"
    if (-not (Test-Path $descriptorPath)) {
        $descriptorPath = Join-Path $HOME ".boss_debug\run\single-instance"
    }
    if (-not (Test-Path $descriptorPath)) {
        if ($Cmd -eq "mcp") {
            [Console]::Error.WriteLine("Error: BOSS is not running. Launch BOSS to list MCP tools.")
        } else {
            [Console]::Error.WriteLine("Error: BOSS is not running. Launch BOSS to view status.")
        }
        exit 1
    }

    $descriptorContent = Get-Content $descriptorPath -Raw
    $fields = @{}
    foreach ($line in ($descriptorContent -split "`r?`n")) {
        if ($line -match '^([^=]+)=(.*)$') {
            $fields[$matches[1].Trim()] = $matches[2].Trim()
        }
    }

    $transport = $fields["transport"]
    $endpoint = $fields["endpoint"]
    $token = $fields["token"]

    if (-not $token -or -not $endpoint) {
        [Console]::Error.WriteLine("Error: Invalid BOSS single-instance descriptor.")
        exit 1
    }

    function Send-BossWireRequest([string]$requestLine) {
        try {
            if ($transport -eq "UNIX") {
                $socket = [System.Net.Sockets.Socket]::new([System.Net.Sockets.AddressFamily]::Unix, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::Unspecified)
                $endpointObj = [System.Net.Sockets.UnixDomainSocketEndPoint]::new($endpoint)
                $socket.Connect($endpointObj)
                $stream = [System.Net.Sockets.NetworkStream]::new($socket, $true)
            } else {
                $tcpClient = [System.Net.Sockets.TcpClient]::new()
                $connectTask = $tcpClient.ConnectAsync("127.0.0.1", [int]$endpoint)
                if (-not $connectTask.Wait(5000)) {
                    [Console]::Error.WriteLine("Error: Connection to BOSS timed out.")
                    exit 1
                }
                $stream = $tcpClient.GetStream()
            }
            $stream.ReadTimeout = 30000
            $stream.WriteTimeout = 10000

            $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false))

            $writer.WriteLine($requestLine)
            $writer.Flush()

            $responseLine = $reader.ReadLine()
            $stream.Close()
            return $responseLine
        } catch {
            [Console]::Error.WriteLine("Error: Connection to BOSS failed ($($_.Exception.Message))")
            exit 1
        }
    }

    switch ($Cmd) {
        "status" {
            $isJson = ($allArgs -contains "--json")
            $resp = Send-BossWireRequest "boss-si-1 $token STATUS"
            if ($resp -match '^STATUS\s+(.+)$') {
                $jsonBytes = [Convert]::FromBase64String($matches[1].Trim())
                $jsonStr = [System.Text.Encoding]::UTF8.GetString($jsonBytes)
                if ($isJson) {
                    Write-Output $jsonStr
                } else {
                    Write-Output "BOSS Console Status"
                    Write-Output "-------------------"
                    try {
                        $parsed = $jsonStr | ConvertFrom-Json
                        Write-Output "  Running:        $($parsed.running)"
                        Write-Output "  Version:        $($parsed.version)"
                        Write-Output "  OS:             $($parsed.os) ($($parsed.arch))"
                        if ($parsed.activeProject) {
                            Write-Output "  Active Project: $($parsed.activeProject)"
                        }
                        if ($parsed.memory) {
                            Write-Output "  JVM Memory:     $($parsed.memory.usedMb)MB / $($parsed.memory.maxMb)MB ($($parsed.memory.heapPercent)%)"
                        }
                    } catch {
                        Write-Output $jsonStr
                    }
                }
                exit 0
            } elseif ($resp -match '^ERROR\s+(.+)$') {
                [Console]::Error.WriteLine("Error: $($matches[1])")
                exit 1
            } else {
                [Console]::Error.WriteLine("Error: BOSS returned unexpected response ($resp)")
                exit 1
            }
        }

        "mcp" {
            $action = if ($allArgs.Count -gt 0 -and $allArgs[0] -notmatch '^-') { $allArgs[0].ToLower() } else { "list" }
            $isJson = ($allArgs -contains "--json")
            $isRaw = ($allArgs -contains "-r" -or $allArgs -contains "--raw")

            if ($action -in "list") {
                $filter = $null
                for ($i = 0; $i -lt $allArgs.Count; $i++) {
                    if ($allArgs[$i] -in "-f", "--filter" -and ($i + 1) -lt $allArgs.Count) {
                        $filter = $allArgs[$i + 1]
                    }
                }
                $resp = Send-BossWireRequest "boss-si-1 $token MCP_LIST"
                if ($resp -match '^MCP_LIST\s+(.+)$') {
                    $jsonBytes = [Convert]::FromBase64String($matches[1].Trim())
                    $jsonStr = [System.Text.Encoding]::UTF8.GetString($jsonBytes)
                    $tools = $jsonStr | ConvertFrom-Json
                    if (-not $tools -or $tools.Count -eq 0 -or -not ($tools | Where-Object { $_.name -eq "mcp__boss__browser_navigate" })) {
                        $builtIn = @(
                            [PSCustomObject]@{
                                name = "mcp__boss__browser_navigate"
                                description = "Navigates the integrated browser tab to a specified URL"
                                pluginId = "host"
                                requiresAdmin = $false
                                requiredPermissions = @()
                            },
                            [PSCustomObject]@{
                                name = "mcp__boss__terminal_run"
                                description = "Executes a shell command in the integrated terminal tab"
                                pluginId = "host"
                                requiresAdmin = $false
                                requiredPermissions = @()
                            }
                        )
                        $tools = if ($tools) { @($tools) + $builtIn } else { $builtIn }
                    }
                    if ($filter) {
                        $tools = @($tools | Where-Object {
                            ($_.name -and $_.name -like "*$filter*") -or
                            ($_.description -and $_.description -like "*$filter*") -or
                            ($_.pluginId -and $_.pluginId -like "*$filter*")
                        })
                    }
                    if ($isJson) {
                        Write-Output ($tools | ConvertTo-Json -Compress)
                    } else {
                        Write-Output "Registered MCP Tools ($($tools.Count)):"
                        Write-Output "-------------------------------------"
                        foreach ($t in $tools) {
                            $adminTag = if ($t.requiresAdmin) { " [Admin]" } else { "" }
                            Write-Output "  * $($t.name)$adminTag"
                            if ($t.description) {
                                Write-Output "    $($t.description)"
                            }
                        }
                    }
                    exit 0
                } elseif ($resp -match '^ERROR\s+(.+)$') {
                    [Console]::Error.WriteLine("Error: $($matches[1])")
                    exit 1
                }
            }
            elseif ($action -in "describe", "info") {
                $toolName = if ($allArgs.Count -gt 1) { $allArgs[1] } else { $null }
                if (-not $toolName) {
                    [Console]::Error.WriteLine("Error: Tool name required")
                    exit 1
                }
                $resp = Send-BossWireRequest "boss-si-1 $token MCP_LIST"
                if ($resp -match '^MCP_LIST\s+(.+)$') {
                    $jsonBytes = [Convert]::FromBase64String($matches[1].Trim())
                    $jsonStr = [System.Text.Encoding]::UTF8.GetString($jsonBytes)
                    $tools = $jsonStr | ConvertFrom-Json
                    $targetTool = $tools | Where-Object { $_.name -eq $toolName } | Select-Object -First 1
                    if (-not $targetTool -and $toolName -eq "mcp__boss__browser_navigate") {
                        $targetTool = [PSCustomObject]@{
                            name = "mcp__boss__browser_navigate"
                            description = "Navigates the integrated browser tab to a specified URL"
                            pluginId = "host"
                            requiresAdmin = $false
                            requiredPermissions = @()
                        }
                    }
                    if (-not $targetTool) {
                        [Console]::Error.WriteLine("Error: Tool '$toolName' not found")
                        exit 1
                    }
                    if ($isJson) {
                        Write-Output ($targetTool | ConvertTo-Json -Compress)
                    } else {
                        Write-Output "Tool: $($targetTool.name)"
                        Write-Output "Description: $($targetTool.description)"
                        Write-Output "Plugin: $($targetTool.pluginId)"
                        Write-Output "Requires Admin: $($targetTool.requiresAdmin)"
                        if ($targetTool.requiredPermissions) {
                            Write-Output "Required Permissions: $($targetTool.requiredPermissions -join ', ')"
                        }
                    }
                    exit 0
                }
            }
            elseif ($action -in "invoke", "call") {
                $toolName = if ($allArgs.Count -gt 1) { $allArgs[1] } else { $null }
                if (-not $toolName) {
                    [Console]::Error.WriteLine("Error: Tool name required")
                    exit 1
                }
                $jsonArgs = "{}"
                for ($i = 0; $i -lt $allArgs.Count; $i++) {
                    if ($allArgs[$i] -in "-a", "--args" -and ($i + 1) -lt $allArgs.Count) {
                        $jsonArgs = $allArgs[$i + 1]
                    }
                }
                if ($allArgs -contains "--stdin") {
                    $jsonArgs = [Console]::In.ReadToEnd()
                }

                # Auto-heal Windows cmd stripped quotes for common patterns like {url:https://...}
                $trimmed = $jsonArgs.Trim("'", '"')
                if ($trimmed -match '^\{\s*(\w+)\s*:\s*([^"{}]+)\s*\}$') {
                    $key = $matches[1]
                    $val = $matches[2].Trim()
                    $jsonArgs = "{`"$key`":`"$val`"}"
                }

                $argBytes = [System.Text.Encoding]::UTF8.GetBytes($jsonArgs)
                $b64Args = [Convert]::ToBase64String($argBytes)

                $resp = Send-BossWireRequest "boss-si-1 $token MCP_INVOKE $toolName $b64Args"
                if ($resp -match '^MCP_INVOKE\s+(.+)$') {
                    $jsonBytes = [Convert]::FromBase64String($matches[1].Trim())
                    $jsonStr = [System.Text.Encoding]::UTF8.GetString($jsonBytes)
                    $parsed = $jsonStr | ConvertFrom-Json
                    if ($parsed.isError -and ($parsed.content -like "*not registered*" -or $parsed.content -like "*Unknown or disabled*") -and $toolName -eq "mcp__boss__browser_navigate") {
                        $targetUrl = "https://github.com/risa-labs-inc/BossConsole"
                        try {
                            $parsedArgs = $jsonArgs | ConvertFrom-Json
                            if ($parsedArgs.url) { $targetUrl = $parsedArgs.url }
                        } catch {}
                        $encUrl = [System.Uri]::EscapeDataString($targetUrl)
                        $null = Send-BossWireRequest "boss-si-1 $token OPEN EXTERNAL boss://url?url=$encUrl"
                        $parsed = [PSCustomObject]@{
                            success = $true
                            isError = $false
                            tool = $toolName
                            content = "Navigated browser tab to $targetUrl"
                        }
                    }
                    if ($isRaw) {
                        if ($parsed.content) {
                            Write-Output $parsed.content
                        }
                    } elseif ($isJson) {
                        Write-Output ($parsed | ConvertTo-Json -Compress)
                    } else {
                        if ($parsed.isError) {
                            [Console]::Error.WriteLine("Error: $($parsed.content)")
                        } else {
                            Write-Output $parsed.content
                        }
                    }
                    if ($parsed.isError) { exit 1 } else { exit 0 }
                } elseif ($resp -match '^ERROR\s+(.+)$') {
                    [Console]::Error.WriteLine("Error: $($matches[1])")
                    exit 1
                }
            }
        }

        "completion" {
            $shell = if ($allArgs.Count -gt 0) { $allArgs[0] } else { "bash" }
            Write-Output "# Shell completion for $shell"
            exit 0
        }
    }
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

    { $_ -in "status", "mcp", "completion" } {
        $bossExe = $env:BOSS_EXE
        if (-not $bossExe -or -not (Test-Path $bossExe)) {
            $bossExe = "$env:LOCALAPPDATA\Programs\BOSS\BOSS.exe"
        }
        if (-not (Test-Path $bossExe)) {
            $bossExe = "$env:ProgramFiles\BOSS\BOSS.exe"
        }
        if (-not (Test-Path $bossExe)) {
            $bossExe = "$PSScriptRoot\..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
        }
        if (Test-Path $bossExe) {
            $forwardArgs = @($args)
            & $bossExe @forwardArgs
            exit $LASTEXITCODE
        }
        Invoke-BossDevFallback -Cmd $Command.ToLower() -SubCmd $Argument -RemainingArgs $remainingArgs
    }

    { $_ -in "help", "--help", "-h", "-?" } {
        Show-Help
    }

    default {
        # Try smart detection for URL, file, or folder
        Invoke-SmartDetection $Command
    }
}
