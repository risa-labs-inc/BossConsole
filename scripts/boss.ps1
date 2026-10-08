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
    boss.ps1 terminal -c "ls -la"
    Opens a terminal tab with the specified command

.EXAMPLE
    boss.ps1 folder C:\Projects\MyProject
    Opens the folder in the codebase plugin
#>

function Parse-CommandLine {
    param([string]$CmdLine)
    $tokens = [System.Collections.Generic.List[string]]::new()
    if ([string]::IsNullOrWhiteSpace($CmdLine)) { return @($tokens) }

    $sb = [System.Text.StringBuilder]::new()
    $inQuotes = $false
    $hasToken = $false
    $tokenStartedWithQuote = $false
    $len = $CmdLine.Length
    $i = 0

    while ($i -lt $len) {
        $c = $CmdLine[$i]

        if (-not $inQuotes -and ($c -eq ' ' -or $c -eq "`t")) {
            if ($hasToken) {
                $tokens.Add($sb.ToString())
                [void]$sb.Clear()
                $hasToken = $false
                $tokenStartedWithQuote = $false
            }
            $i++
            continue
        }

        if (-not $hasToken) {
            $hasToken = $true
            if ($c -eq '"') {
                $tokenStartedWithQuote = $true
                $inQuotes = $true
                $i++
                continue
            }
        }

        if ($tokenStartedWithQuote) {
            if ($c -eq '\') {
                $slashCount = 0
                while ($i -lt $len -and $CmdLine[$i] -eq '\') {
                    $slashCount++
                    $i++
                }
                if ($i -lt $len -and $CmdLine[$i] -eq '"') {
                    if ($slashCount % 2 -eq 0) {
                        for ($k = 0; $k -lt [int]($slashCount / 2); $k++) { [void]$sb.Append('\') }
                        $inQuotes = -not $inQuotes
                        $i++
                    } else {
                        # Deliberate divergence from standard MSVCRT for trailing slashes on Windows paths (e.g. "C:\dir\" vs "C:\dir\\"):
                        # In MSVCRT, \" escapes the quote, eating subsequent arguments until the next quote.
                        # For Windows CLI path arguments with structural outer quotes, a single trailing slash before the closing quote
                        # is treated as a literal trailing slash delimiter, while \\" collapses to a single backslash.
                        if ($inQuotes -and ($i + 1 -ge $len -or [string]::IsNullOrWhiteSpace($CmdLine.Substring($i + 1)))) {
                            $numSlashes = if ($slashCount -eq 1) { 1 } else { [int]($slashCount / 2) }
                            for ($k = 0; $k -lt $numSlashes; $k++) { [void]$sb.Append('\') }
                            $inQuotes = $false
                            $i++
                        } else {
                            for ($k = 0; $k -lt [Math]::Floor($slashCount / 2); $k++) { [void]$sb.Append('\') }
                            [void]$sb.Append('"')
                            $i++
                        }
                    }
                } else {
                    for ($k = 0; $k -lt $slashCount; $k++) { [void]$sb.Append('\') }
                }
                continue
            }

            if ($c -eq '"') {
                $inQuotes = -not $inQuotes
                $i++
                continue
            }

            [void]$sb.Append($c)
            $i++
            continue
        }

        # Token did not start with an outer quote (e.g. {"query":"x"} or bare words).
        # Deliberate asymmetry: retain embedded quotes intact so that JSON payloads passed without
        # outer quotes (or where outer quotes enclosed the whole argument) preserve their inner structure.
        if ($c -eq '\') {
            $slashCount = 0
            while ($i -lt $len -and $CmdLine[$i] -eq '\') {
                $slashCount++
                $i++
            }
            if ($i -lt $len -and $CmdLine[$i] -eq '"') {
                for ($k = 0; $k -lt [Math]::Floor($slashCount / 2); $k++) { [void]$sb.Append('\') }
                if ($slashCount % 2 -eq 1) {
                    [void]$sb.Append('"')
                } else {
                    [void]$sb.Append('"')
                    $inQuotes = -not $inQuotes
                }
                $i++
            } else {
                for ($k = 0; $k -lt $slashCount; $k++) { [void]$sb.Append('\') }
            }
            continue
        }

        if ($c -eq '"') {
            $inQuotes = -not $inQuotes
            [void]$sb.Append('"')
            $i++
            continue
        }

        [void]$sb.Append($c)
        $i++
    }

    if ($hasToken) {
        $tokens.Add($sb.ToString())
    }

    return @($tokens)
}

function Escape-DataStringRfc3986 {
    param([string]$str)
    if ([string]::IsNullOrEmpty($str)) { return "" }
    $escaped = [System.Uri]::EscapeDataString($str)
    return $escaped.Replace("!", "%21").Replace("'", "%27").Replace("(", "%28").Replace(")", "%29").Replace("*", "%2A")
}

$launcherNonce = $null
if ($args.Count -ge 2 -and $args[0] -eq '-LauncherNonce') {
    $launcherNonce = $args[1]
    $args = @(if ($args.Count -gt 2) { $args[2..($args.Count - 1)] } else { @() })
}

if (-not [string]::IsNullOrEmpty($launcherNonce)) {
    if ($env:BOSS_RAW_ARGS) {
        $rawLine = $env:BOSS_RAW_ARGS
        $env:BOSS_RAW_ARGS = $null
        $env:BOSS_LAUNCHER_NONCE = $null
        # Anchored regex matching the echoed REM line captured by boss.bat.
        $noncePattern = '^rem \* #' + [regex]::Escape($launcherNonce) + '#(.*)#\s*$'
        if ($rawLine -match $noncePattern) {
            $rawArgs = $matches[1]
            $args = @(Parse-CommandLine $rawArgs)
        } else {
            [Console]::Error.WriteLine("Error: Invalid launcher raw arguments.")
            exit 1
        }
    } else {
        [Console]::Error.WriteLine("Error: Missing launcher raw arguments.")
        exit 1
    }
} else {
    # Direct invocation of boss.ps1:
    # If BOSS_RAW_ARGS was set in the environment, do not trust it.
    # Real command-line arguments in $args win.
    $env:BOSS_RAW_ARGS = $null
    $env:BOSS_LAUNCHER_NONCE = $null
}

# Preserve the original named script interface alongside positional CLI forwarding.
if ($args.Count -gt 0 -and $args[0] -in '-Command', '-Argument', '-CommandToRun') {
    $legacy = @{}
    for ($i = 0; $i -lt $args.Count; $i += 2) {
        if ($i + 1 -ge $args.Count -or $args[$i] -notin '-Command', '-Argument', '-CommandToRun', '-c') {
            [Console]::Error.WriteLine("Error: Invalid named launcher arguments.")
            exit 1
        }
        $legacy[$args[$i].TrimStart('-')] = $args[$i + 1]
    }
    if (-not $legacy.ContainsKey('Command') -or [string]::IsNullOrWhiteSpace($legacy['Command'])) {
        [Console]::Error.WriteLine("Error: Invalid named launcher arguments.")
        exit 1
    }
    $args = @($legacy['Command'])
    if ($legacy.ContainsKey('Argument')) { $args += $legacy['Argument'] }
    $legacyRun = if ($legacy.ContainsKey('CommandToRun')) { $legacy['CommandToRun'] } else { $legacy['c'] }
    if ($legacyRun) { $args += @('-c', $legacyRun) }
}

if ($args.Count -eq 0 -or [string]::IsNullOrWhiteSpace($args[0])) {
    [Console]::Error.WriteLine("Error: No command specified")
    [Console]::Out.WriteLine("Run 'boss --help' for usage information")
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

function Open-BossDeepLink {
    param([string]$DeepLink)

    if ($env:BOSS_DEEPLINK_ECHO -eq "1") {
        Write-Output $DeepLink
        return
    }

    try {
        Start-Process -FilePath $DeepLink
    }
    catch {
        [Console]::Error.WriteLine("Failed to open deep link: $_")
        exit 1
    }
}

function Format-CommandLineArg {
    param([string]$Arg)
    if ([string]::IsNullOrEmpty($Arg)) {
        return '""'
    }
    if ($Arg -notmatch '[\s"]' -and -not $Arg.EndsWith('\')) {
        return $Arg
    }
    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.Append('"')
    for ($i = 0; $i -lt $Arg.Length; $i++) {
        $c = $Arg[$i]
        if ($c -eq '\') {
            $slashCount = 1
            while (($i + 1) -lt $Arg.Length -and $Arg[$i + 1] -eq '\') {
                $slashCount++
                $i++
            }
            if ($i + 1 -ge $Arg.Length) {
                for ($k = 0; $k -lt ($slashCount * 2); $k++) { [void]$sb.Append('\') }
            } elseif ($Arg[$i + 1] -eq '"') {
                for ($k = 0; $k -lt ($slashCount * 2 + 1); $k++) { [void]$sb.Append('\') }
                [void]$sb.Append('"')
                $i++
            } else {
                for ($k = 0; $k -lt $slashCount; $k++) { [void]$sb.Append('\') }
            }
        } elseif ($c -eq '"') {
            [void]$sb.Append('\"')
        } else {
            [void]$sb.Append($c)
        }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Forward-To-BossExe {
    param([string[]]$ArgumentsToForward)
    $bossExe = $env:BOSS_EXE
    if ($bossExe -and -not (Test-Path -LiteralPath $bossExe -PathType Leaf)) {
        [Console]::Error.WriteLine("Error: BOSS_EXE does not name an executable file.")
        exit 1
    }
    if (-not $bossExe -or -not (Test-Path -LiteralPath $bossExe)) {
        $bossExe = "$env:LOCALAPPDATA\Programs\BOSS\BOSS.exe"
    }
    if (-not (Test-Path -LiteralPath $bossExe)) {
        $bossExe = "$env:ProgramFiles\BOSS\BOSS.exe"
    }
    if (-not (Test-Path -LiteralPath $bossExe)) {
        $bossExe = "$PSScriptRoot\..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
    }
    if (Test-Path -LiteralPath $bossExe) {
        if ($bossExe -match '\.(bat|cmd)$') {
            [Console]::Error.WriteLine("Error: BOSS_EXE must be a native executable or PowerShell script, not a batch file.")
            exit 1
        }

        $forwardArgs = if ($null -ne $ArgumentsToForward) { @($ArgumentsToForward) } else { @() }
        if ($bossExe -match '\.ps1$') {
            try {
                $global:LASTEXITCODE = $null
                $errCount = $Error.Count
                & $bossExe @forwardArgs | Out-Host
                $callSuccess = $?
                if ($null -ne $global:LASTEXITCODE) {
                    exit $global:LASTEXITCODE
                }
                # A delegate script that encounters non-terminating errors (e.g. via Write-Error)
                # without an explicit exit code is treated as failed. Note that a delegate which
                # internally tolerates non-terminating errors (-ErrorAction SilentlyContinue) and
                # exits normally will also be reported as exit 1 unless it provides an explicit exit code.
                if ($Error.Count -gt $errCount -or -not $callSuccess) {
                    exit 1
                }
                exit 0
            } catch {
                [Console]::Error.WriteLine("Error: Delegate script failed: $_")
                exit 1
            }
        }

        $formattedArgs = foreach ($a in $forwardArgs) { Format-CommandLineArg $a }
        $cmdLine = $formattedArgs -join ' '

        try {
            $psi = [System.Diagnostics.ProcessStartInfo]::new()
            $psi.FileName = $bossExe
            $psi.Arguments = $cmdLine
            $psi.UseShellExecute = $false
            $p = [System.Diagnostics.Process]::Start($psi)
            $p.WaitForExit()
            exit $p.ExitCode
        } catch {
            [Console]::Error.WriteLine("Error: Failed to launch executable '$bossExe': $_")
            exit 1
        }
    }
    [Console]::Error.WriteLine("Error: BOSS application binary not found. Set BOSS_EXE to the packaged executable or a .ps1 delegate script.")
    exit 1
}

function Invoke-SmartDetection {
    param([string]$Arg)

    # Check if it's a URL (has protocol or common TLD)
    if ($Arg -match '^https?://') {
        # Has http:// or https:// prefix
        $encoded = Escape-DataStringRfc3986 $Arg
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
        return
    }
    elseif ($Arg -match '\.(com|org|net|edu|gov|io|co|dev|app|ai|tech|cloud|xyz|me)(/|$)') {
        # Looks like a domain, add https://
        $encoded = Escape-DataStringRfc3986 "https://$Arg"
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
        return
    }

    # Resolve path (handles relative paths and ~)
    $expandedPath = try {
        $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Arg)
    } catch {
        $Arg
    }

    # Check if it's a file or directory
    if (Test-Path -LiteralPath $expandedPath -ErrorAction SilentlyContinue) {
        if (Test-Path -LiteralPath $expandedPath -PathType Container -ErrorAction SilentlyContinue) {
            # It's a directory
            $encoded = Escape-DataStringRfc3986 $expandedPath
            $deepLink = "boss://folder?path=$encoded"
            Open-BossDeepLink $deepLink
            return
        }
        elseif (Test-Path -LiteralPath $expandedPath -PathType Leaf -ErrorAction SilentlyContinue) {
            # It's a file
            $encoded = Escape-DataStringRfc3986 $expandedPath
            $deepLink = "boss://file?path=$encoded"
            Open-BossDeepLink $deepLink
            return
        }
    }

    # Could not detect type
    [Console]::Error.WriteLine("Error: Could not determine type for: `"$Arg`"")
    Write-Host ""
    Write-Host "Did you mean:"
    Write-Host "  boss url `"$Arg`"      - Open as URL"
    Write-Host "  boss file `"$Arg`"     - Open as file"
    Write-Host "  boss folder `"$Arg`"   - Open as folder"
    Write-Host ""
    Write-Host "Run 'boss --help' for usage information"
    exit 1
}

function Show-Help {
    Write-Host "BOSS CLI - Business Operating System Service"
    Write-Host "Version: {{VERSION}}"
    Write-Host "Built: {{BUILD_DATE}}"
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  boss <url-or-path>             Auto-detect and open URL, file, or folder"
    Write-Host "  boss <command> [arguments]     Run explicit command"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  status                 Queries status and health of the running BOSS instance"
    Write-Host "  doctor                 Reports problems in the running BOSS instance (exit 2 when degraded)"
    Write-Host "  mcp <action> [args]    Discovers and invokes desktop MCP tools (list, describe, invoke)"
    Write-Host "  completion <shell>     Generates shell completion script (bash, zsh, fish)"
    Write-Host "  url <url>              Opens a URL in Fluck browser"
    Write-Host "  workspace <config>     Loads a workspace configuration"
    Write-Host "  file <path>            Opens a file in the editor"
    Write-Host "  folder [path]          Opens a folder in codebase (defaults to home)"
    Write-Host "  terminal               Opens a terminal tab"
    Write-Host "  terminal -c <command>  Opens a terminal tab with command"
    Write-Host "  plugin <id>            Opens any plugin/panel by ID"
    Write-Host "  version                Show CLI version information"
    Write-Host "  help                   Show this help message"
    Write-Host ""
    Write-Host "Smart Detection Examples:"
    Write-Host "  boss google.com                 # Auto-detects as URL (adds https://)"
    Write-Host "  boss https://github.com         # Auto-detects as URL"
    Write-Host "  boss file.txt                   # Auto-detects as file (if exists)"
    Write-Host "  boss C:\Downloads               # Auto-detects as folder"
    Write-Host "  boss .                          # Auto-detects current directory"
    Write-Host ""
    Write-Host "Explicit Command Examples:"
    Write-Host "  boss url https://example.com"
    Write-Host "  boss workspace C:\myworkspace.json"
    Write-Host "  boss file C:\path\to\file.kt"
    Write-Host "  boss folder                       # Opens home directory"
    Write-Host "  boss folder C:\path\to\project    # Opens specific directory"
    Write-Host "  boss terminal"
    Write-Host "  boss terminal -c 'dir'"
    Write-Host "  boss plugin bookmarks"
    Write-Host "  boss plugin secret-manager"
    Write-Host ""
}

# Main command handling
switch ($Command.ToLower()) {
    "url" {
        if ([string]::IsNullOrEmpty($Argument)) {
            [Console]::Error.WriteLine("Error: URL argument required")
            [Console]::Out.WriteLine("Usage: boss url <url>")
            exit 1
        }
        $encoded = Escape-DataStringRfc3986 $Argument
        $deepLink = "boss://url?url=$encoded"
        Open-BossDeepLink $deepLink
    }

    "workspace" {
        if ([string]::IsNullOrEmpty($Argument)) {
            [Console]::Error.WriteLine("Error: Workspace config path required")
            [Console]::Out.WriteLine("Usage: boss workspace <config>")
            exit 1
        }
        $encoded = Escape-DataStringRfc3986 $Argument
        $deepLink = "boss://workspace?config=$encoded"
        Open-BossDeepLink $deepLink
    }

    "file" {
        if ([string]::IsNullOrEmpty($Argument)) {
            [Console]::Error.WriteLine("Error: File path required")
            [Console]::Out.WriteLine("Usage: boss file <path>")
            exit 1
        }
        $filePath = try {
            $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Argument)
        } catch {
            $Argument
        }
        $encoded = Escape-DataStringRfc3986 $filePath
        $deepLink = "boss://file?path=$encoded"
        Open-BossDeepLink $deepLink
    }

    "folder" {
        if ([string]::IsNullOrEmpty($Argument)) {
            $folderPath = if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }
        }
        else {
            $folderPath = try {
                $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Argument)
            } catch {
                $Argument
            }
        }
        $encoded = Escape-DataStringRfc3986 $folderPath
        $deepLink = "boss://folder?path=$encoded"
        Open-BossDeepLink $deepLink
    }

    "terminal" {
        if (-not [string]::IsNullOrEmpty($CommandToRun)) {
            $encoded = Escape-DataStringRfc3986 $CommandToRun
            $deepLink = "boss://terminal?command=$encoded"
        }
        elseif (-not [string]::IsNullOrEmpty($Argument) -and ($Argument -in "-c", "--command")) {
            [Console]::Error.WriteLine("Error: Command argument required after -c")
            [Console]::Out.WriteLine("Usage: boss terminal -c <command>")
            exit 1
        }
        else {
            $deepLink = "boss://terminal"
        }
        Open-BossDeepLink $deepLink
    }

    "plugin" {
        $subcommands = @("init", "validate", "link", "--help", "-h", "help")
        if (-not [string]::IsNullOrEmpty($Argument) -and $Argument.ToLower() -notin $subcommands -and $args.Count -le 2) {
            $encoded = Escape-DataStringRfc3986 $Argument
            $deepLink = "boss://plugin?id=$encoded"
            Open-BossDeepLink $deepLink
        } else {
            Forward-To-BossExe $args
        }
    }

    { $_ -in "status", "doctor", "mcp", "completion" } {
        Forward-To-BossExe $args
    }

    { $_ -in "version", "--version", "-v" } {
        Write-Host "BOSS CLI version {{VERSION}}"
        Write-Host "Built: {{BUILD_DATE}}"
    }

    { $_ -in "help", "--help", "-h", "-?" } {
        Show-Help
    }

    default {
        # Try smart detection for URL, file, or folder
        Invoke-SmartDetection $Command
    }
}
