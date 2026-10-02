#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression and argument-handling tests for the BOSS Windows CLI launcher (boss.bat and boss.ps1) (#1057, #1059, #1136, #1617).

Verifies:
1. boss.bat acts as a minimal safe entry point delegating to boss.ps1 where arguments are handled as values.
2. Argument parsing and handling for explicit commands (url, file, folder, terminal, plugin, version, help),
   automatic routing, and forwarded commands (status, doctor, mcp, completion).
3. Preservation of spaces, Unicode, !, %, &, quotes, and URL queries without unintended command execution.
4. App launches are stubbed via BOSS_DEEPLINK_ECHO and BOSS_EXE rather than testing extracted subroutines.
5. Live tests run on all platforms for boss.ps1, plus live cmd.exe probes on Windows.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$batPath = Join-Path $repoRoot 'boss.bat'
$ps1Path = Join-Path $repoRoot 'boss.ps1'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        Write-Error "ASSERTION FAILED: $Message"
        exit 1
    }
    Write-Output "ok - $Message"
}

# --- 1. Source-shape checks (run everywhere, no cmd.exe needed) -------------

Assert-True (Test-Path $batPath) 'boss.bat exists'
Assert-True (Test-Path $ps1Path) 'boss.ps1 exists'

$bat = Get-Content $batPath -Raw
$ps1 = Get-Content $ps1Path -Raw

Assert-True ($bat -match 'powershell.*-File\s+"%~dp0boss\.ps1"') `
    'boss.bat delegates to boss.ps1 as a minimal entry point (#1617)'

Assert-True ($bat -match 'for %%b in \(1\) do rem \* #%BOSS_LAUNCHER_NONCE%#%\*#') `
    'the raw arguments are captured on an echoed REM line (#1617)'

Assert-True ($bat -match 'set "BOSS_ARGS_DIR=%TEMP%\\boss-args-%RANDOM%%RANDOM%%RANDOM%"') `
    'the capture directory name is randomized'

Assert-True (-not ($bat -match '(?m)^\s*call\b')) `
    'boss.bat contains no call statements (avoids cmd call-time argument re-parsing)'

Assert-True (-not ($bat -match 'findstr')) `
    'boss.bat contains no findstr subshells'

Assert-True (-not ($bat -match 'start\s+""')) `
    'boss.bat contains no start commands (routing moved to boss.ps1)'

Assert-True ($ps1 -match '\[System\.Uri\]::EscapeDataString') `
    'boss.ps1 uses [System.Uri]::EscapeDataString for URL encoding'

Assert-True ($ps1 -match 'BOSS_DEEPLINK_ECHO') `
    'boss.ps1 supports stubbing deep link launches via BOSS_DEEPLINK_ECHO'

# --- 2. Live boss.ps1 logic tests (run everywhere under pwsh) ---------------

$tempBase = if ($env:TEMP) { $env:TEMP } else { [System.IO.Path]::GetTempPath() }
$testDir = Join-Path $tempBase ("boss-launcher-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDir | Out-Null
$sentinel = Join-Path $testDir 'sentinel.txt'

function New-NativeArgvEchoExe {
    param([string]$ExePath)
    $src = [System.IO.Path]::ChangeExtension($ExePath, '.cs')
    $code = @'
using System;
public static class Program {
    public static int Main(string[] args) {
        Console.WriteLine("FORWARDED:" + string.Join(" ", args));
        Console.WriteLine("ARGV_COUNT:" + args.Length);
        for (int i = 0; i < args.Length; i++) {
            Console.WriteLine("ARG[" + i + "]:" + args[i]);
            if (args[i] == "--exit-code" && i + 1 < args.Length) {
                int ec;
                if (int.TryParse(args[i + 1], out ec)) {
                    return ec;
                }
            }
        }
        return 0;
    }
}
'@
    Set-Content -Path $src -Value $code -Encoding Utf8
    $compiled = $false

    $cscCandidates = @(
        "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
        "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
    ) + @(Get-ChildItem "$env:WINDIR\Microsoft.NET\Framework*\v*\csc.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

    foreach ($csc in $cscCandidates) {
        if ($csc -and (Test-Path $csc)) {
            $p = Start-Process -FilePath $csc -ArgumentList @('/nologo', '/target:exe', "/out:$ExePath", $src) -Wait -PassThru -NoNewWindow
            if ($p.ExitCode -eq 0 -and (Test-Path $ExePath)) {
                $compiled = $true
                break
            }
        }
    }

    if (-not $compiled) {
        try {
            $escapedSrc = $src.Replace("'", "''")
            $escapedExe = $ExePath.Replace("'", "''")
            $cmd = "Add-Type -TypeDefinition (Get-Content -LiteralPath '$escapedSrc' -Raw) -OutputType ConsoleApplication -OutputAssembly '$escapedExe'"
            $p = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Command', $cmd) -Wait -PassThru -NoNewWindow
            if ($p.ExitCode -eq 0 -and (Test-Path $ExePath)) {
                $compiled = $true
            }
        } catch {}
    }

    Remove-Item $src -ErrorAction SilentlyContinue
    if (-not $compiled -or -not (Test-Path $ExePath)) {
        throw "Failed to compile native argv-echo executable at $ExePath"
    }
}

# Stub executable for forwarded commands (BOSS.exe)
$isWin = if ($PSVersionTable.PSVersion.Major -ge 6) { $IsWindows } else { ($env:OS -eq 'Windows_NT') }
$stubExe = if ($isWin) {
    Join-Path $testDir 'fake-boss.exe'
} else {
    Join-Path $testDir 'fake-boss.sh'
}
if ($isWin) {
    New-NativeArgvEchoExe $stubExe
} else {
    $stubContent = @'
#!/bin/sh
echo "FORWARDED:$*"
echo "ARGV_COUNT:$#"
i=0
ec=0
prev=""
for a in "$@"; do
  echo "ARG[$i]:$a"
  i=$((i + 1))
  if [ "$prev" = "--exit-code" ]; then
    ec="$a"
  fi
  prev="$a"
done
exit $ec
'@
    Set-Content -Path $stubExe -Value $stubContent -Encoding Ascii
    & chmod +x $stubExe
}

function Invoke-BossPs1Direct {
    param(
        [string[]]$ScriptArgs = @(),
        [string]$RawArgs = '',
        [string]$LauncherNonce = '',
        [hashtable]$ExtraEnv = $null,
        [switch]$NoAutoNonce,
        [string]$HostExe = ''
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = if ($HostExe) { $HostExe } else { (Get-Process -Id $PID).Path }
    $cmdArgs = @('-NoProfile', '-File', $ps1Path)

    if (-not [string]::IsNullOrEmpty($RawArgs)) {
        if (-not [string]::IsNullOrEmpty($LauncherNonce)) {
            $cmdArgs += @('-LauncherNonce', $LauncherNonce)
            $psi.Environment['BOSS_RAW_ARGS'] = $RawArgs
        } elseif (-not $NoAutoNonce) {
            $autoNonce = "testnonce" + (Get-Random)
            $cmdArgs += @('-LauncherNonce', $autoNonce)
            if ($RawArgs -match '^rem \* #(.*)#\s*$') {
                $psi.Environment['BOSS_RAW_ARGS'] = "rem * #$autoNonce#$($matches[1])#"
            } else {
                $psi.Environment['BOSS_RAW_ARGS'] = $RawArgs
            }
        } else {
            $psi.Environment['BOSS_RAW_ARGS'] = $RawArgs
        }
    } elseif (-not [string]::IsNullOrEmpty($LauncherNonce)) {
        $cmdArgs += @('-LauncherNonce', $LauncherNonce)
    }

    if ($ScriptArgs) {
        $cmdArgs += $ScriptArgs
    }
    $psi.ArgumentList.Clear()
    foreach ($a in $cmdArgs) { $psi.ArgumentList.Add($a) }
    $psi.Environment['BOSS_DEEPLINK_ECHO'] = '1'
    $psi.Environment['BOSS_EXE'] = $stubExe
    if ([string]::IsNullOrEmpty($RawArgs)) {
        [void]$psi.Environment.Remove('BOSS_RAW_ARGS')
    }
    if ($null -ne $ExtraEnv) {
        foreach ($k in $ExtraEnv.Keys) {
            $psi.Environment[$k] = $ExtraEnv[$k]
        }
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $testDir
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit(30000)) {
        $p.Kill()
        [void]$p.WaitForExit(5000)
        $script:LastPs1ExitCode = -1
        return 'TIMEOUT'
    }
    $script:LastPs1ExitCode = $p.ExitCode
    return ($out.Result + $err.Result)
}

try {
    # 2.1 Missing command
    $ps1NoArgs = Invoke-BossPs1Direct @()
    Assert-True ($ps1NoArgs -match 'Error: No command specified') `
        "boss.ps1 reports missing command on empty args (got: $($ps1NoArgs.Trim()))"
    Assert-True ($script:LastPs1ExitCode -eq 1) "boss.ps1 empty args returns exit code 1"

    # Forwarding exit codes through boss.ps1 -> stub
    $directZero = Invoke-BossPs1Direct @('status', '--format', 'json')
    Assert-True ($script:LastPs1ExitCode -eq 0) "direct boss.ps1 forwarding returns exit code 0"
    $directNonzero = Invoke-BossPs1Direct @('status', '--exit-code', '42')
    Assert-True ($script:LastPs1ExitCode -eq 42) "direct boss.ps1 forwarding propagates nonzero exit code 42"

    # 2.1b Hostile BOSS_RAW_ARGS ignored when boss.ps1 is directly invoked
    $hostileDirect = Invoke-BossPs1Direct -ScriptArgs @('status') -RawArgs 'rem * #evil#url "https://evil.com"#' -NoAutoNonce
    Assert-True ($hostileDirect.Trim() -match '^FORWARDED:status') `
        "direct invocation ignores hostile BOSS_RAW_ARGS and real arguments win"
    Assert-True ($hostileDirect -match 'ARGV_COUNT:1') "direct invocation argv count is 1"
    Assert-True ($hostileDirect -match [regex]::Escape('ARG[0]:status')) "direct invocation ARG[0] is status"

    # 2.1c Nonce mismatch fails closed
    $nonceMismatch = Invoke-BossPs1Direct -RawArgs 'rem * #other-nonce#status#' -LauncherNonce 'good-nonce'
    Assert-True ($nonceMismatch -match 'Error: Invalid launcher raw arguments.') `
        "mismatched launcher nonce is rejected"

    # 2.1d Partial or invalid named invocation
    $partialArg = Invoke-BossPs1Direct @('-Argument', 'x')
    Assert-True ($partialArg -match 'Error: Invalid named launcher arguments.') `
        "boss.ps1 -Argument x reports invalid named launcher arguments"

    $partialRun = Invoke-BossPs1Direct @('-CommandToRun', 'x')
    Assert-True ($partialRun -match 'Error: Invalid named launcher arguments.') `
        "boss.ps1 -CommandToRun x reports invalid named launcher arguments"

    $validNamed = Invoke-BossPs1Direct @('-Command', 'url', '-Argument', 'https://example.com')
    Assert-True ($validNamed -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com')) `
        "valid named launcher invocation routes correctly"

    # 2.1e .ps1 script delegate forwarding and error handling
    $ps1DelegateStub = Join-Path $testDir 'fake-boss-delegate.ps1'
    $ps1DelegateContent = @'
$argsArray = @($args)
Write-Output "FORWARDED:$($argsArray -join ' ')"
Write-Output "ARGV_COUNT:$($argsArray.Count)"
for ($i = 0; $i -lt $argsArray.Count; $i++) {
    Write-Output "ARG[$i]:$($argsArray[$i])"
}
$ec = 0
for ($i = 0; $i -lt $argsArray.Count; $i++) {
    if ($argsArray[$i] -eq '--exit-code' -and ($i + 1) -lt $argsArray.Count) {
        $ec = [int]$argsArray[$i + 1]
    }
}
exit $ec
'@
    Set-Content -Path $ps1DelegateStub -Value $ps1DelegateContent -Encoding Ascii

    $ps1DelegateZero = Invoke-BossPs1Direct -ScriptArgs @('status', 'arg with space', '--format', 'json') -ExtraEnv @{ BOSS_EXE = $ps1DelegateStub }
    Assert-True ($ps1DelegateZero -match 'FORWARDED:status arg with space --format json') `
        "ps1 delegate receives forwarded arguments intact"
    Assert-True ($ps1DelegateZero -match 'ARGV_COUNT:4') "ps1 delegate receives 4 arguments"
    Assert-True ($ps1DelegateZero -match [regex]::Escape('ARG[1]:arg with space')) "ps1 delegate preserves argument with space"
    Assert-True ($script:LastPs1ExitCode -eq 0) "ps1 delegate forwarding returns exit code 0"

    $ps1DelegateNonzero = Invoke-BossPs1Direct -ScriptArgs @('status', '--exit-code', '42') -ExtraEnv @{ BOSS_EXE = $ps1DelegateStub }
    Assert-True ($script:LastPs1ExitCode -eq 42) "ps1 delegate forwarding propagates nonzero exit code 42"

    $ps1ThrowStub = Join-Path $testDir 'fake-boss-throw.ps1'
    Set-Content -Path $ps1ThrowStub -Value 'throw "simulated delegate failure without exit code"' -Encoding Ascii
    $ps1ThrowOut = Invoke-BossPs1Direct -ScriptArgs @('status') -ExtraEnv @{ BOSS_EXE = $ps1ThrowStub }
    Assert-True ($script:LastPs1ExitCode -eq 1) "ps1 delegate throwing without exit code exits with code 1"
    Assert-True ($ps1ThrowOut -match 'Error: Delegate script failed:') "ps1 delegate throwing reports error"

    $ps1ErrorStub = Join-Path $testDir 'fake-boss-error.ps1'
    Set-Content -Path $ps1ErrorStub -Value 'Write-Error "simulated non-terminating error without exit code"' -Encoding Ascii
    $ps1ErrorOut = Invoke-BossPs1Direct -ScriptArgs @('status') -ExtraEnv @{ BOSS_EXE = $ps1ErrorStub }
    Assert-True ($script:LastPs1ExitCode -eq 1) "ps1 delegate with error and no exit code exits with code 1"

    $batStub = Join-Path $testDir 'fake-boss.bat'
    Set-Content -Path $batStub -Value '@echo off' -Encoding Ascii
    $batRefuseOut = Invoke-BossPs1Direct -ScriptArgs @('status') -ExtraEnv @{ BOSS_EXE = $batStub }
    Assert-True ($script:LastPs1ExitCode -eq 1) "pointing BOSS_EXE to .bat file exits with code 1"
    Assert-True ($batRefuseOut -match 'Error: BOSS_EXE must be a native executable or PowerShell script, not a batch file.') `
        "pointing BOSS_EXE to .bat file is refused with descriptive error"

    $cmdStub = Join-Path $testDir 'fake-boss.cmd'
    Set-Content -Path $cmdStub -Value '@echo off' -Encoding Ascii
    $cmdRefuseOut = Invoke-BossPs1Direct -ScriptArgs @('status') -ExtraEnv @{ BOSS_EXE = $cmdStub }
    Assert-True ($script:LastPs1ExitCode -eq 1) "pointing BOSS_EXE to .cmd file exits with code 1"
    Assert-True ($cmdRefuseOut -match 'Error: BOSS_EXE must be a native executable or PowerShell script, not a batch file.') `
        "pointing BOSS_EXE to .cmd file is refused with descriptive error"

    # 2.2 Version and Help
    $ps1Ver = Invoke-BossPs1Direct @('--version')
    Assert-True ($ps1Ver -match 'BOSS CLI version') "boss.ps1 --version outputs version"

    $ps1Help = Invoke-BossPs1Direct @('--help')
    Assert-True ($ps1Help -match 'BOSS CLI - Business Operating System Service') "boss.ps1 --help outputs help"

    # 2.3 Explicit URL with queries, spaces, Unicode, !, %, &, quotes
    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $ps1Url1 = Invoke-BossPs1Direct @('url', 'https://example.com/search?q=foo bar&tag=test!&user=alice''s#frag')
    Assert-True ($ps1Url1 -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dfoo%20bar%26tag%3Dtest%21%26user%3Dalice%27s%23frag')) `
        "boss.ps1 routes url with spaces, !, &, and fragments (got: $($ps1Url1.Trim()))"

    $ps1Url2 = Invoke-BossPs1Direct @('url', 'https://example.com/search?q="hello"&lang=日本語&cat=café')
    Assert-True ($ps1Url2 -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3D%22hello%22%26lang%3D%E6%97%A5%E6%9C%AC%E8%AA%9E%26cat%3Dcaf%C3%A9')) `
        "boss.ps1 routes url with internal quotes and Unicode (got: $($ps1Url2.Trim()))"

    # 2.4 Explicit File with spaces, Unicode, !, %, &
    $ps1File = Invoke-BossPs1Direct @('file', 'C:\test\my file & name! %20 "quoted" 日本語.txt')
    Assert-True ($ps1File -match [regex]::Escape('boss://file?path=C%3A%5Ctest%5Cmy%20file%20%26%20name%21%20%2520%20%22quoted%22%20%E6%97%A5%E6%9C%AC%E8%AA%9E.txt')) `
        "boss.ps1 routes file with spaces, &, !, %, quotes, and Unicode (got: $($ps1File.Trim()))"

    # 2.5 Explicit Folder
    $ps1Folder = Invoke-BossPs1Direct @('folder', 'C:\test\my folder & name! 日本語')
    Assert-True ($ps1Folder -match [regex]::Escape('boss://folder?path=C%3A%5Ctest%5Cmy%20folder%20%26%20name%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "boss.ps1 routes folder with spaces, &, !, and Unicode (got: $($ps1Folder.Trim()))"

    # 2.6 Explicit Terminal with -c
    $ps1Term = Invoke-BossPs1Direct @('terminal', '-c', 'dir /b & echo "hello world" & echo %TESTVAR% ! 日本語')
    Assert-True ($ps1Term -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25TESTVAR%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "boss.ps1 routes terminal with quotes, &, %, !, and Unicode (got: $($ps1Term.Trim()))"

    # 2.7 Simulated BOSS_RAW_ARGS (simulating batch-to-PowerShell handoff)
    $rawUrl = Invoke-BossPs1Direct @() 'rem * #url "https://example.com/search?q=foo%20bar&tag=test!&user=alice''s#frag"# '
    Assert-True ($rawUrl -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dfoo%2520bar%26tag%3Dtest%21%26user%3Dalice%27s%23frag')) `
        "batch-to-PowerShell raw args URL handoff preserves queries and symbols"

    $rawTerm = Invoke-BossPs1Direct @() 'rem * #terminal -c "dir /b & echo \"hello world\" & echo %TESTVAR% ! 日本語"# '
    Assert-True ($rawTerm -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25TESTVAR%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "batch-to-PowerShell raw args terminal handoff preserves quotes and &, %"

    $rawFwd = Invoke-BossPs1Direct @() 'rem * #mcp invoke search_workspace --args {"query":"hello world","filter":"a&b!%20"}# '
    Assert-True ($rawFwd.Trim() -match 'FORWARDED:mcp invoke search_workspace --args \{"query":"hello world","filter":"a&b!%20"\}') `
        "batch-to-PowerShell raw args forwarded command preserves JSON quotes and special chars (got: $($rawFwd.Trim()))"
    Assert-True ($rawFwd -match 'ARGV_COUNT:5') "forwarded command argv count is 5"
    Assert-True ($rawFwd -match [regex]::Escape('ARG[0]:mcp')) "forwarded command ARG[0] is mcp"
    Assert-True ($rawFwd -match [regex]::Escape('ARG[1]:invoke')) "forwarded command ARG[1] is invoke"
    Assert-True ($rawFwd -match [regex]::Escape('ARG[2]:search_workspace')) "forwarded command ARG[2] is search_workspace"
    Assert-True ($rawFwd -match [regex]::Escape('ARG[3]:--args')) "forwarded command ARG[3] is --args"
    Assert-True ($rawFwd -match [regex]::Escape('ARG[4]:{"query":"hello world","filter":"a&b!%20"}')) `
        "forwarded command ARG[4] preserves JSON quotes intact"

    # Forwarded command with empty string argument
    $rawEmpty = Invoke-BossPs1Direct @() 'rem * #status "" --format json# '
    Assert-True ($rawEmpty -match 'ARGV_COUNT:4') "forwarded command with empty string preserves argv count 4"
    Assert-True ($rawEmpty -match [regex]::Escape('ARG[0]:status')) "ARG[0] is status"
    Assert-True ($rawEmpty -match '(?m)^ARG\[1\]:\s*$') "ARG[1] is empty string"
    Assert-True ($rawEmpty -match [regex]::Escape('ARG[2]:--format')) "ARG[2] is --format"
    Assert-True ($rawEmpty -match [regex]::Escape('ARG[3]:json')) "ARG[3] is json"

    # 2.7b Trailing backslashes before closing quote
    $rawFld1 = Invoke-BossPs1Direct @() 'rem * #folder "C:\some dir\"# '
    Assert-True ($rawFld1 -match [regex]::Escape('boss://folder?path=C%3A%5Csome%20dir%5C')) `
        "raw args folder with single trailing backslash keeps backslash"

    $rawFld2 = Invoke-BossPs1Direct @() 'rem * #folder "C:\some dir\\"# '
    Assert-True ($rawFld2 -match [regex]::Escape('boss://folder?path=C%3A%5Csome%20dir%5C')) `
        "raw args folder with double trailing backslash collapses to single backslash"

    # 2.7c Launcher boundary percent expansion probe (TESTVAR defined, cmd bypassed)
    $probe1 = Invoke-BossPs1Direct -RawArgs 'rem * #terminal -c "echo %TESTVAR%"# ' -ExtraEnv @{ TESTVAR = 'EXPANDED' }
    Assert-True ($probe1 -match [regex]::Escape('boss://terminal?command=echo%20%25TESTVAR%25')) `
        "launcher boundary probe: boss.ps1 does no percent expansion with defined variable"

    if ($isWin -and (Get-Command 'powershell.exe' -ErrorAction SilentlyContinue)) {
        $probe1WinPS = Invoke-BossPs1Direct -RawArgs 'rem * #terminal -c "echo %TESTVAR%"# ' -ExtraEnv @{ TESTVAR = 'EXPANDED' } -HostExe 'powershell.exe'
        Assert-True ($probe1WinPS -match [regex]::Escape('boss://terminal?command=echo%20%25TESTVAR%25')) `
            "launcher boundary probe under powershell.exe 5.1: does no percent expansion with defined variable"
    }

    # Injection payloads targeting sentinel file
    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $injRaw = Invoke-BossPs1Direct @() ('rem * #nosuch&echo pwned > "' + $sentinel + '"# ')
    Assert-True (-not (Test-Path $sentinel)) "simulated raw args payload runs no command"
    Assert-True ($injRaw -match 'Error: Could not determine type for:') "simulated raw args payload reports unrecognized type"

    # 2.8 Automatic Routing live tests
    $autoUrl = Invoke-BossPs1Direct @('https://example.com/search?q=test&filter=1!&pct=%25')
    Assert-True ($autoUrl -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dtest%26filter%3D1%21%26pct%3D%2525')) `
        "boss.ps1 auto-detects URL with queries, &, %, and !"

    $autoDomain = Invoke-BossPs1Direct @('example.com/path?foo=1&bar=2')
    Assert-True ($autoDomain -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fpath%3Ffoo%3D1%26bar%3D2')) `
        "boss.ps1 auto-detects domain with queries"

    $liveFile = Join-Path $testDir 'R&D file! %20 日本語.txt'
    Set-Content -Path $liveFile -Value 'test' -Encoding Ascii
    $autoFile = Invoke-BossPs1Direct @($liveFile)
    Assert-True ($autoFile -match 'boss://file\?path=') "boss.ps1 auto-detects real file"

    $liveFolder = Join-Path $testDir 'R&D folder! 日本語'
    New-Item -ItemType Directory -Path $liveFolder | Out-Null
    $autoFolder = Invoke-BossPs1Direct @($liveFolder)
    Assert-True ($autoFolder -match 'boss://folder\?path=') "boss.ps1 auto-detects real folder"

} finally {
    Remove-Item $testDir -Recurse -ErrorAction SilentlyContinue
}

# --- 3. Live cmd.exe + boss.bat tests (run on Windows) ----------------------

if (-not $isWin -or -not (Get-Command cmd.exe -ErrorAction SilentlyContinue)) {
    Write-Output 'ok - cmd.exe unavailable on this OS, live boss.bat probes skipped (source and live boss.ps1 checks passed)'
    exit 0
}

$winTestDir = Join-Path $tempBase ("boss-bat-live-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $winTestDir | Out-Null
$sentinel = Join-Path $winTestDir 'sentinel.txt'

$winStubExe = Join-Path $winTestDir 'fake-boss.exe'
New-NativeArgvEchoExe $winStubExe
$env:BOSS_EXE = $winStubExe
$env:BOSS_DEEPLINK_ECHO = '1'

# TESTVAR must stay undefined for the initial batch probes because
# cmd command-line mode (the /c string) preserves an undefined percent reference
# while cmd batch mode (%VAR% inside a .bat) deletes it.
# Preserving %TESTVAR% through boss.bat proves the launcher does no batch-mode
# percent pass over captured arguments.
Remove-Item Env:\TESTVAR -ErrorAction SilentlyContinue

$argsDirsBefore = @(Get-ChildItem $tempBase -Directory -Filter 'boss-args-*' -Name -ErrorAction SilentlyContinue)

function Invoke-BossBatLine {
    param([string]$Bat, [string]$ArgLine)
    $psi = [System.Diagnostics.ProcessStartInfo]::new('cmd.exe')
    $psi.Arguments = '/d /s /c ""' + $Bat + '" ' + $ArgLine + '"'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $winTestDir
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit(30000)) {
        $p.Kill()
        [void]$p.WaitForExit(5000)
        $script:LastBatExitCode = -1
        return 'TIMEOUT'
    }
    $script:LastBatExitCode = $p.ExitCode
    return ($out.Result + $err.Result)
}

try {
    # 3.1 Empty args
    $noArgs = Invoke-BossBatLine $batPath ''
    Assert-True ($noArgs -match 'Error: No command specified') "boss.bat reports missing command (got: $($noArgs.Trim()))"
    Assert-True ($script:LastBatExitCode -eq 1) "boss.bat empty args returns exit code 1 (got: $script:LastBatExitCode)"

    # 3.2 Version & Help
    $verOut = Invoke-BossBatLine $batPath '--version'
    Assert-True ($verOut -match 'BOSS CLI version') "boss.bat --version outputs version"

    $helpOut = Invoke-BossBatLine $batPath '--help'
    Assert-True ($helpOut -match 'BOSS CLI - Business Operating System Service') "boss.bat --help outputs help"

    # 3.2b Caller-boundary percent expansion (TESTVAR defined)
    # cmd expands a defined variable in the parent /c string before the batch file runs.
    # No batch launcher can prevent parent-process command-line expansion.
    $env:TESTVAR = 'EXPANDED'
    try {
        $callerProbe = Invoke-BossBatLine $batPath 'terminal -c "echo %TESTVAR%"'
        Assert-True ($callerProbe -match 'command=echo%20EXPANDED') `
            "caller-boundary: defined %TESTVAR% expands in parent cmd before batch runs"
    } finally {
        Remove-Item Env:\TESTVAR -ErrorAction SilentlyContinue
    }

    # 3.2c Batch-boundary percent expansion probe (TESTVAR defined)
    # When passed to cmd /c, %%TESTVAR%% has its inner %TESTVAR% expanded by parent cmd
    # to produce %EXPANDED%, which then arrives whole through boss.bat and boss.ps1
    # without batch-mode corruption.
    $env:TESTVAR = 'EXPANDED'
    try {
        $batchProbe = Invoke-BossBatLine $batPath 'terminal -c "echo %%TESTVAR%%"'
        Assert-True ($batchProbe -match [regex]::Escape('command=echo%20%25EXPANDED%25')) `
            "batch-boundary probe: %%TESTVAR%% delivers %EXPANDED% through launcher safely (got: $($batchProbe.Trim()))"
    } finally {
        Remove-Item Env:\TESTVAR -ErrorAction SilentlyContinue
    }

    # 3.2d Delayed expansion guard probe under /v:on
    $psiDelayed = [System.Diagnostics.ProcessStartInfo]::new('cmd.exe')
    $psiDelayed.Arguments = '/v:on /d /s /c ""' + $batPath + '" terminal -c "echo hello!world""'
    $psiDelayed.UseShellExecute = $false
    $psiDelayed.RedirectStandardOutput = $true
    $psiDelayed.RedirectStandardError = $true
    $psiDelayed.WorkingDirectory = $winTestDir
    $pDelayed = [System.Diagnostics.Process]::Start($psiDelayed)
    $outDelayed = $pDelayed.StandardOutput.ReadToEndAsync()
    $errDelayed = $pDelayed.StandardError.ReadToEndAsync()
    $delayedCompleted = $pDelayed.WaitForExit(30000)
    if (-not $delayedCompleted) {
        $pDelayed.Kill()
        [void]$pDelayed.WaitForExit(5000)
    }
    Assert-True $delayedCompleted "cmd /v:on probe completed within timeout"
    Assert-True ($pDelayed.ExitCode -eq 0) "cmd /v:on probe exited with code 0 (got: $($pDelayed.ExitCode))"
    $resDelayed = $outDelayed.Result + $errDelayed.Result
    Assert-True ($resDelayed -match [regex]::Escape('command=echo%20hello%21world')) `
        "DisableDelayedExpansion protects ! under cmd /v:on (got: $($resDelayed.Trim()))"

    # 3.3 Explicit URL commands with queries, spaces, Unicode, !, %, &, quotes
    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $urlTest1 = Invoke-BossBatLine $batPath 'url "https://example.com/search?q=foo%20bar&tag=test!&user=alice''s#frag"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during url test 1'
    Assert-True ($urlTest1 -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dfoo%2520bar%26tag%3Dtest%21%26user%3Dalice%27s%23frag')) `
        "url with queries, &, %, !, and fragment routes correctly (got: $($urlTest1.Trim()))"

    $urlTest2 = Invoke-BossBatLine $batPath 'url "https://example.com/search?q=\"hello\"&lang=日本語&cat=café"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during url test 2'
    Assert-True ($urlTest2 -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3D%22hello%22%26lang%3D%E6%97%A5%E6%9C%AC%E8%AA%9E%26cat%3Dcaf%C3%A9')) `
        "url with internal quotes and Unicode routes correctly (got: $($urlTest2.Trim()))"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $urlPayload = 'url "https://example.com/test?q=1 & echo pwned > ""' + $sentinel + '"" & rem \""'
    $urlOut = Invoke-BossBatLine $batPath $urlPayload
    Assert-True ($urlOut -ne 'TIMEOUT') "url payload completed within timeout"
    Assert-True (-not (Test-Path $sentinel)) "url payload does not execute commands: $urlPayload"
    Assert-True ($urlOut -match [regex]::Escape('boss://url?url=')) "url payload reaches routing safely as data"

    # 3.4 Explicit File commands
    $fileTest1 = Invoke-BossBatLine $batPath 'file "C:\test\my file & name! %20 \"quoted\" 日本語.txt"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during file test 1'
    Assert-True ($fileTest1 -match [regex]::Escape('boss://file?path=C%3A%5Ctest%5Cmy%20file%20%26%20name%21%20%2520%20%22quoted%22%20%E6%97%A5%E6%9C%AC%E8%AA%9E.txt')) `
        "file with spaces, &, !, %, quotes, and Unicode routes correctly (got: $($fileTest1.Trim()))"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $filePayload = 'file "C:\test\file.txt & echo pwned > ""' + $sentinel + '"" & rem \""'
    $fileOut = Invoke-BossBatLine $batPath $filePayload
    Assert-True ($fileOut -ne 'TIMEOUT') "file payload completed within timeout"
    Assert-True (-not (Test-Path $sentinel)) "file payload does not execute commands: $filePayload"
    Assert-True ($fileOut -match [regex]::Escape('boss://file?path=')) "file payload reaches routing safely as data"

    # 3.5 Explicit Folder commands
    $folderTest1 = Invoke-BossBatLine $batPath 'folder "C:\test\my folder & name! 日本語"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during folder test 1'
    Assert-True ($folderTest1 -match [regex]::Escape('boss://folder?path=C%3A%5Ctest%5Cmy%20folder%20%26%20name%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "folder with spaces, &, !, and Unicode routes correctly (got: $($folderTest1.Trim()))"

    $folderPlain = Invoke-BossBatLine $batPath 'folder'
    Assert-True ($folderPlain -match 'boss://folder\?path=') "folder with no argument routes to user profile"

    $folderWin1 = Invoke-BossBatLine $batPath 'folder "C:\some dir\"'
    Assert-True ($folderWin1 -match [regex]::Escape('boss://folder?path=C%3A%5Csome%20dir%5C')) `
        "folder with single trailing backslash keeps backslash in live cmd"

    $folderWin2 = Invoke-BossBatLine $batPath 'folder "C:\some dir\\"'
    Assert-True ($folderWin2 -match [regex]::Escape('boss://folder?path=C%3A%5Csome%20dir%5C')) `
        "folder with double trailing backslash collapses to single backslash in live cmd"

    # 3.6 Explicit Terminal commands
    $termPlain = Invoke-BossBatLine $batPath 'terminal'
    Assert-True ($termPlain -match [regex]::Escape('boss://terminal')) "terminal plain routes to boss://terminal"

    $termCmd = Invoke-BossBatLine $batPath 'terminal -c "dir /b & echo \"hello world\" & echo %TESTVAR% ! 日本語"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during terminal test'
    Assert-True ($termCmd -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25TESTVAR%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "terminal -c with spaces, quotes, &, %, !, and Unicode routes correctly (got: $($termCmd.Trim()))"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $termPayload = 'terminal -c "dir & echo pwned > ""' + $sentinel + '"" & rem \""'
    $termOut = Invoke-BossBatLine $batPath $termPayload
    Assert-True ($termOut -ne 'TIMEOUT') "terminal payload completed within timeout"
    Assert-True (-not (Test-Path $sentinel)) "terminal payload does not execute commands: $termPayload"
    Assert-True ($termOut -match [regex]::Escape('boss://terminal?command=')) "terminal payload reaches routing safely as data"

    # 3.7 Automatic Routing
    $autoUrl = Invoke-BossBatLine $batPath '"https://example.com/search?q=test&filter=1!&pct=%25"'
    Assert-True ($autoUrl -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dtest%26filter%3D1%21%26pct%3D%2525')) `
        "auto-detect URL with queries, &, %, and ! (got: $($autoUrl.Trim()))"

    $autoDomain = Invoke-BossBatLine $batPath '"example.com/path?foo=1&bar=2"'
    Assert-True ($autoDomain -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fpath%3Ffoo%3D1%26bar%3D2')) `
        "auto-detect domain with queries (got: $($autoDomain.Trim()))"

    $liveWinFile = Join-Path $winTestDir 'R&D file! %20 日本語.txt'
    Set-Content -Path $liveWinFile -Value 'test' -Encoding Ascii
    $autoFile = Invoke-BossBatLine $batPath ('"' + $liveWinFile + '"')
    Assert-True ($autoFile -match 'boss://file\?path=') "auto-detect existing file routes to boss://file?path="

    $liveWinFolder = Join-Path $winTestDir 'R&D folder! 日本語'
    New-Item -ItemType Directory -Path $liveWinFolder | Out-Null
    $autoFolder = Invoke-BossBatLine $batPath ('"' + $liveWinFolder + '"')
    Assert-True ($autoFolder -match 'boss://folder\?path=') "auto-detect existing folder routes to boss://folder?path="

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $badAuto = 'nosuch&echo pwned > "' + $sentinel + '"'
    $badAutoOut = Invoke-BossBatLine $batPath ('"' + $badAuto + '"')
    Assert-True (-not (Test-Path $sentinel)) "auto-detect payload does not execute command: $badAuto"
    Assert-True ($badAutoOut -match 'Error: Could not determine type for:') "unrecognized target outputs error"

    # 3.8 Forwarded CLI commands
    # Structural quotes are consumed, emitting value tokens to BOSS.exe
    $fwdJson = Invoke-BossBatLine $batPath 'status --format "json"'
    Assert-True ($fwdJson.Trim() -match '^FORWARDED:status --format json') `
        "forwarded command unquotes structural quotes: boss status --format json"
    Assert-True ($script:LastBatExitCode -eq 0) "forwarded status command propagates exit code 0"
    Assert-True ($fwdJson -match 'ARGV_COUNT:3') "status argv count is 3"
    Assert-True ($fwdJson -match [regex]::Escape('ARG[0]:status')) "status ARG[0] is status"
    Assert-True ($fwdJson -match [regex]::Escape('ARG[1]:--format')) "status ARG[1] is --format"
    Assert-True ($fwdJson -match [regex]::Escape('ARG[2]:json')) "status ARG[2] is json"

    # Nonzero exit code propagation through native exe -> boss.ps1 -> boss.bat -> cmd
    $fwdNonzero42 = Invoke-BossBatLine $batPath 'status --exit-code 42'
    Assert-True ($fwdNonzero42 -match 'ARGV_COUNT:3') "status --exit-code 42 argv count is 3"
    Assert-True ($script:LastBatExitCode -eq 42) "forwarded command propagates nonzero exit code 42 (got: $script:LastBatExitCode)"

    $fwdNonzero7 = Invoke-BossBatLine $batPath 'mcp invoke search_workspace --exit-code 7'
    Assert-True ($fwdNonzero7 -match 'ARGV_COUNT:5') "mcp --exit-code 7 argv count is 5"
    Assert-True ($script:LastBatExitCode -eq 7) "forwarded command propagates nonzero exit code 7 (got: $script:LastBatExitCode)"

    $fwdComp = Invoke-BossBatLine $batPath 'completion "powershell"'
    Assert-True ($fwdComp.Trim() -match '^FORWARDED:completion powershell') `
        "forwarded command unquotes structural quotes: boss completion powershell"
    Assert-True ($fwdComp -match 'ARGV_COUNT:2') "completion argv count is 2"
    Assert-True ($fwdComp -match [regex]::Escape('ARG[0]:completion')) "completion ARG[0] is completion"
    Assert-True ($fwdComp -match [regex]::Escape('ARG[1]:powershell')) "completion ARG[1] is powershell"

    $fwdMcpName = Invoke-BossBatLine $batPath '"mcp" invoke search_workspace --args {"query":"x"}'
    Assert-True ($fwdMcpName.Trim() -match '^FORWARDED:mcp invoke search_workspace --args \{"query":"x"\}') `
        "forwarded command unquotes structural quotes on command name"
    Assert-True ($fwdMcpName -match 'ARGV_COUNT:5') "mcp named argv count is 5"
    Assert-True ($fwdMcpName -match [regex]::Escape('ARG[4]:{"query":"x"}')) "mcp named ARG[4] has JSON quotes"

    $fwdMcpFull = Invoke-BossBatLine $batPath 'mcp invoke search_workspace --args {"query":"hello world","filter":"a&b!%20"}'
    Assert-True ($fwdMcpFull.Trim() -match '^FORWARDED:mcp invoke search_workspace --args \{"query":"hello world","filter":"a&b!%20"\}') `
        "forwarded command keeps complex args intact"
    Assert-True ($fwdMcpFull -match 'ARGV_COUNT:5') "mcp complex argv count is 5"
    Assert-True ($fwdMcpFull -match [regex]::Escape('ARG[4]:{"query":"hello world","filter":"a&b!%20"}')) `
        "mcp complex ARG[4] preserves JSON quotes and spaces across native-exe boundary"

    # Forwarded command with empty string argument through boss.bat
    $fwdEmpty = Invoke-BossBatLine $batPath 'status "" --format json'
    Assert-True ($fwdEmpty -match 'ARGV_COUNT:4') "boss.bat forwarded empty argument preserves argv count 4 (got: $($fwdEmpty.Trim()))"
    Assert-True ($fwdEmpty -match [regex]::Escape('ARG[0]:status')) "empty arg test ARG[0] is status"
    Assert-True ($fwdEmpty -match '(?m)^ARG\[1\]:\s*$') "empty arg test ARG[1] is empty string"
    Assert-True ($fwdEmpty -match [regex]::Escape('ARG[2]:--format')) "empty arg test ARG[2] is --format"
    Assert-True ($fwdEmpty -match [regex]::Escape('ARG[3]:json')) "empty arg test ARG[3] is json"

    # Forwarded command with backslashes and spaces through boss.bat
    $fwdPath = Invoke-BossBatLine $batPath 'mcp invoke tool --path "C:\my dir\"'
    Assert-True ($fwdPath -match 'ARGV_COUNT:5') "boss.bat forwarded trailing backslash path preserves argv count 5"
    Assert-True ($fwdPath -match [regex]::Escape('ARG[4]:C:\my dir\')) "trailing backslash path arrives intact at native-exe"

    $pluginLink = Invoke-BossBatLine $batPath 'plugin bookmarks'
    Assert-True ($pluginLink -match [regex]::Escape('boss://plugin?id=bookmarks')) `
        "plugin <id> routes to boss://plugin?id=bookmarks"

    $pluginFwd = Invoke-BossBatLine $batPath 'plugin init "my-tool"'
    Assert-True ($pluginFwd.Trim() -match '^FORWARDED:plugin init my-tool') `
        "plugin init forwards to CLI executable"
    Assert-True ($pluginFwd -match 'ARGV_COUNT:3') "plugin init argv count is 3"
    Assert-True ($pluginFwd -match [regex]::Escape('ARG[0]:plugin')) "plugin ARG[0] is plugin"
    Assert-True ($pluginFwd -match [regex]::Escape('ARG[1]:init')) "plugin ARG[1] is init"
    Assert-True ($pluginFwd -match [regex]::Escape('ARG[2]:my-tool')) "plugin ARG[2] is my-tool"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $fwdPayload = 'mcp x"=="x" echo pwned > "' + $sentinel + '" & rem "'
    $fwdOut = Invoke-BossBatLine $batPath $fwdPayload
    Assert-True (-not (Test-Path $sentinel)) "payload in forwarded command is data, not executed"
    Assert-True ($fwdOut -match 'FORWARDED:') "payload reaches stub as argument"

    # Forwarded command to .ps1 delegate script through boss.bat
    $winPs1Stub = Join-Path $winTestDir 'fake-boss-delegate.ps1'
    Set-Content -Path $winPs1Stub -Value $ps1DelegateContent -Encoding Ascii
    $env:BOSS_EXE = $winPs1Stub
    try {
        $batPs1Out = Invoke-BossBatLine $batPath 'status "bat to ps1" --exit-code 42'
        Assert-True ($batPs1Out -match 'FORWARDED:status bat to ps1 --exit-code 42') `
            "boss.bat -> boss.ps1 -> .ps1 delegate forwards arguments intact"
        Assert-True ($batPs1Out -match 'ARGV_COUNT:4') `
            "boss.bat -> boss.ps1 -> .ps1 delegate preserves argv count 4"
        Assert-True ($script:LastBatExitCode -eq 42) `
            "boss.bat -> boss.ps1 -> .ps1 delegate propagates exit code 42"
    } finally {
        $env:BOSS_EXE = $winStubExe
    }

    # 3.9 Escaped redirection and pipe characters
    $filesBefore = @(Get-ChildItem $winTestDir -Name)
    foreach ($line in @(
        'a^>capture-gt.txt',
        'a^>^>capture-gt.txt',
        'a^|echo side-effect^>sentinel.txt',
        'a^&echo side-effect^>sentinel.txt',
        'a^<capture-lt.txt'
    )) {
        Remove-Item $sentinel -ErrorAction SilentlyContinue
        $metaOut = Invoke-BossBatLine $batPath $line
        $stray = @(Get-ChildItem $winTestDir -Name | Where-Object { $_ -notin $filesBefore })
        Assert-True ($stray.Count -eq 0) "the capture line does not act on: boss $line (created: $($stray -join ', '))"
        $firstToken = ($line -replace '\^', '').Split(' ')[0]
        Assert-True ($metaOut -match ('Error: Could not determine type for: "' + [regex]::Escape($firstToken) + '"')) `
            "boss $line reaches detection safely as token: $firstToken (got: $($metaOut.Trim()))"
    }

    # 3.10 Quote payload injection tests (#1617)
    $quotePayloads = @(
        @{ Line = 'x"=="x" echo side-effect>sentinel.txt & rem "'; Pattern = 'Error: Could not determine type for:' },
        @{ Line = 'url x"=="x" echo side-effect>sentinel.txt & rem "'; Pattern = 'boss://url\?url=' },
        @{ Line = 'file x"=="x" echo side-effect>sentinel.txt & rem "'; Pattern = 'boss://file\?path=' },
        @{ Line = 'ab" == "ab" echo side-effect>sentinel.txt & rem "'; Pattern = 'Error: Could not determine type for:' }
    )
    foreach ($entry in $quotePayloads) {
        $payload = $entry.Line
        Remove-Item $sentinel -ErrorAction SilentlyContinue
        $quoteOut = Invoke-BossBatLine $batPath $payload
        Assert-True ($quoteOut -ne 'TIMEOUT') "quote payload completed within timeout: boss $payload"
        Assert-True (-not (Test-Path $sentinel)) "quote payload runs nothing: boss $payload"
        Assert-True ($quoteOut -match $entry.Pattern) "quote payload reaches routing/detection as data: boss $payload"
    }

    # 3.11 Parallel calls check
    $batch = @(0..7 | ForEach-Object {
        $n = $_
        $line = if ($n % 2) { "url `"https://example.com/n$n`"" } else { "terminal -c `"echo test$n`"" }
        $psi = [System.Diagnostics.ProcessStartInfo]::new('cmd.exe')
        $psi.Arguments = '/d /s /c ""' + $batPath + '" ' + $line + '"'
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.WorkingDirectory = $winTestDir
        $p = [System.Diagnostics.Process]::Start($psi)
        [pscustomobject]@{ N = $n; Proc = $p; Out = $p.StandardOutput.ReadToEndAsync() }
    })
    foreach ($b in $batch) {
        $completed = $b.Proc.WaitForExit(60000)
        if (-not $completed) {
            $b.Proc.Kill()
            [void]$b.Proc.WaitForExit(5000)
        }
        Assert-True $completed "parallel call $($b.N) completed within timeout"
        Assert-True ($b.Proc.ExitCode -eq 0) "parallel call $($b.N) exited with code 0 (got: $($b.Proc.ExitCode))"
        $got = $b.Out.Result
        if ($b.N % 2) {
            Assert-True ($got -match [regex]::Escape("boss://url?url=https%3A%2F%2Fexample.com%2Fn$($b.N)")) `
                "parallel call $($b.N) routed its own URL"
        } else {
            Assert-True ($got -match [regex]::Escape("boss://terminal?command=echo%20test$($b.N)")) `
                "parallel call $($b.N) routed its own terminal command"
        }
    }

    # 3.12 Directory collision / exhaustion refusal
    $fixedName = 'boss-args-1617fixed'
    $batContent = Get-Content $batPath -Raw
    $fixedBat = Join-Path $winTestDir 'boss-fixed-name.bat'
    Set-Content -Path $fixedBat -Encoding Ascii -NoNewline `
        -Value ($batContent.Replace('boss-args-%RANDOM%%RANDOM%%RANDOM%', $fixedName))
    Copy-Item $ps1Path (Join-Path $winTestDir 'boss.ps1') -Force
    $takenDir = Join-Path $tempBase $fixedName
    New-Item -ItemType Directory -Path $takenDir -Force | Out-Null
    try {
        Set-Content -Path (Join-Path $takenDir 'args.txt') -Encoding Ascii -Value 'rem * #url "https://example.com/other-call"# '
        $takenOut = Invoke-BossBatLine $fixedBat 'url "https://example.com/my-call"'
        Assert-True ($takenOut -match 'could not read the command-line arguments') `
            "a taken capture directory is refused (got: $($takenOut.Trim()))"
        Assert-True ($script:LastBatExitCode -eq 1) "exhaustion refusal exits with code 1"
        Assert-True ($takenOut -notmatch 'other-call') 'the other call''s arguments are never used'
    } finally {
        Remove-Item $takenDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    # 3.13 Leftover directory cleanup
    $leftover = @(Get-ChildItem $tempBase -Directory -Filter 'boss-args-*' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notin $argsDirsBefore })
    Assert-True ($leftover.Count -eq 0) `
        "every call removed its capture directory (left: $(($leftover | ForEach-Object Name) -join ', '))"

} finally {
    Remove-Item Env:\BOSS_EXE -ErrorAction SilentlyContinue
    Remove-Item Env:\BOSS_DEEPLINK_ECHO -ErrorAction SilentlyContinue
    Remove-Item Env:\TESTVAR -ErrorAction SilentlyContinue
    Remove-Item $winTestDir -Recurse -ErrorAction SilentlyContinue
}

Write-Output 'ALL Windows launcher argument-handling tests passed'
