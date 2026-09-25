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

Assert-True ($bat -match 'for %%b in \(1\) do rem \* #%\*#') `
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

# Stub executable for forwarded commands (BOSS.exe)
$isWin = ($env:OS -eq 'Windows_NT')
$stubExe = if ($isWin) {
    Join-Path $testDir 'fake-boss.cmd'
} else {
    Join-Path $testDir 'fake-boss.sh'
}
if ($isWin) {
    Set-Content -Path $stubExe -Value "@echo FORWARDED:%*`r`n" -Encoding Ascii -NoNewline
} else {
    Set-Content -Path $stubExe -Value "#!/bin/sh`necho `"FORWARDED:`$*`"`n" -Encoding Ascii
    & chmod +x $stubExe
}

function Invoke-BossPs1Direct {
    param([string[]]$ScriptArgs, [string]$RawArgs = $null)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = (Get-Process -Id $PID).Path
    $cmdArgs = @('-NoProfile', '-File', $ps1Path)
    if ($ScriptArgs) {
        $cmdArgs += $ScriptArgs
    }
    $psi.ArgumentList.Clear()
    foreach ($a in $cmdArgs) { $psi.ArgumentList.Add($a) }
    $psi.Environment['BOSS_DEEPLINK_ECHO'] = '1'
    $psi.Environment['BOSS_EXE'] = $stubExe
    if ($null -ne $RawArgs) {
        $psi.Environment['BOSS_RAW_ARGS'] = $RawArgs
    } else {
        $psi.Environment.Remove('BOSS_RAW_ARGS')
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $testDir
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit(30000)) { $p.Kill(); return 'TIMEOUT' }
    return ($out.Result + $err.Result)
}

try {
    # 2.1 Missing command
    $ps1NoArgs = Invoke-BossPs1Direct @()
    Assert-True ($ps1NoArgs -match 'Error: No command specified') `
        "boss.ps1 reports missing command on empty args (got: $($ps1NoArgs.Trim()))"

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
    $ps1Term = Invoke-BossPs1Direct @('terminal', '-c', 'dir /b & echo "hello world" & echo %PATH% ! 日本語')
    Assert-True ($ps1Term -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25PATH%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "boss.ps1 routes terminal with quotes, &, %, !, and Unicode (got: $($ps1Term.Trim()))"

    # 2.7 Simulated BOSS_RAW_ARGS (simulating batch-to-PowerShell handoff)
    $rawUrl = Invoke-BossPs1Direct @() 'rem * #url "https://example.com/search?q=foo%20bar&tag=test!&user=alice''s#frag"# '
    Assert-True ($rawUrl -match [regex]::Escape('boss://url?url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Dfoo%2520bar%26tag%3Dtest%21%26user%3Dalice%27s%23frag')) `
        "batch-to-PowerShell raw args URL handoff preserves queries and symbols"

    $rawTerm = Invoke-BossPs1Direct @() 'rem * #terminal -c "dir /b & echo \"hello world\" & echo %PATH% ! 日本語"# '
    Assert-True ($rawTerm -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25PATH%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "batch-to-PowerShell raw args terminal handoff preserves quotes and &, %"

    $rawFwd = Invoke-BossPs1Direct @() 'rem * #mcp invoke search_workspace --args {"query":"hello world","filter":"a&b!%20"}# '
    Assert-True ($rawFwd.Trim() -match 'FORWARDED:mcp invoke search_workspace --args \{"query":"hello world","filter":"a&b!%20"\}') `
        "batch-to-PowerShell raw args forwarded command preserves JSON quotes and special chars"

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

$winStubExe = Join-Path $winTestDir 'fake-boss.cmd'
Set-Content -Path $winStubExe -Value "@echo FORWARDED:%*`r`n" -Encoding Ascii -NoNewline
$env:BOSS_EXE = $winStubExe
$env:BOSS_DEEPLINK_ECHO = '1'

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
    if (-not $p.WaitForExit(30000)) { $p.Kill(); return 'TIMEOUT' }
    return ($out.Result + $err.Result)
}

try {
    # 3.1 Empty args
    $noArgs = Invoke-BossBatLine $batPath ''
    Assert-True ($noArgs -match 'Error: No command specified') "boss.bat reports missing command (got: $($noArgs.Trim()))"

    # 3.2 Version & Help
    $verOut = Invoke-BossBatLine $batPath '--version'
    Assert-True ($verOut -match 'BOSS CLI version') "boss.bat --version outputs version"

    $helpOut = Invoke-BossBatLine $batPath '--help'
    Assert-True ($helpOut -match 'BOSS CLI - Business Operating System Service') "boss.bat --help outputs help"

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
    $urlPayload = 'url "https://example.com/test?q=1\" & echo pwned > ""' + $sentinel + '"" & rem \""'
    Invoke-BossBatLine $batPath $urlPayload | Out-Null
    Assert-True (-not (Test-Path $sentinel)) "url payload does not execute commands: $urlPayload"

    # 3.4 Explicit File commands
    $fileTest1 = Invoke-BossBatLine $batPath 'file "C:\test\my file & name! %20 \"quoted\" 日本語.txt"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during file test 1'
    Assert-True ($fileTest1 -match [regex]::Escape('boss://file?path=C%3A%5Ctest%5Cmy%20file%20%26%20name%21%20%2520%20%22quoted%22%20%E6%97%A5%E6%9C%AC%E8%AA%9E.txt')) `
        "file with spaces, &, !, %, quotes, and Unicode routes correctly (got: $($fileTest1.Trim()))"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $filePayload = 'file "C:\test\file.txt\" & echo pwned > ""' + $sentinel + '"" & rem \""'
    Invoke-BossBatLine $batPath $filePayload | Out-Null
    Assert-True (-not (Test-Path $sentinel)) "file payload does not execute commands: $filePayload"

    # 3.5 Explicit Folder commands
    $folderTest1 = Invoke-BossBatLine $batPath 'folder "C:\test\my folder & name! 日本語"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during folder test 1'
    Assert-True ($folderTest1 -match [regex]::Escape('boss://folder?path=C%3A%5Ctest%5Cmy%20folder%20%26%20name%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "folder with spaces, &, !, and Unicode routes correctly (got: $($folderTest1.Trim()))"

    $folderPlain = Invoke-BossBatLine $batPath 'folder'
    Assert-True ($folderPlain -match 'boss://folder\?path=') "folder with no argument routes to user profile"

    # 3.6 Explicit Terminal commands
    $termPlain = Invoke-BossBatLine $batPath 'terminal'
    Assert-True ($termPlain -match [regex]::Escape('boss://terminal')) "terminal plain routes to boss://terminal"

    $termCmd = Invoke-BossBatLine $batPath 'terminal -c "dir /b & echo \"hello world\" & echo %PATH% ! 日本語"'
    Assert-True (-not (Test-Path $sentinel)) 'sentinel not created during terminal test'
    Assert-True ($termCmd -match [regex]::Escape('boss://terminal?command=dir%20%2Fb%20%26%20echo%20%22hello%20world%22%20%26%20echo%20%25PATH%25%20%21%20%E6%97%A5%E6%9C%AC%E8%AA%9E')) `
        "terminal -c with spaces, quotes, &, %, !, and Unicode routes correctly (got: $($termCmd.Trim()))"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $termPayload = 'terminal -c "dir & echo pwned > ""' + $sentinel + '"" & rem \""'
    Invoke-BossBatLine $batPath $termPayload | Out-Null
    Assert-True (-not (Test-Path $sentinel)) "terminal payload does not execute commands: $termPayload"

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
    foreach ($line in @(
        'mcp invoke search_workspace --args {"query":"hello world","filter":"a&b!%20"}',
        '"mcp" invoke search_workspace --args {"query":"x"}',
        'status --format "json"',
        'completion "powershell"'
    )) {
        $forwarded = Invoke-BossBatLine $batPath $line
        Assert-True ($forwarded.Trim() -ceq "FORWARDED:$line") `
            "forwarded command keeps arguments intact: boss $line (got: $($forwarded.Trim()))"
    }

    $pluginLink = Invoke-BossBatLine $batPath 'plugin bookmarks'
    Assert-True ($pluginLink -match [regex]::Escape('boss://plugin?id=bookmarks')) `
        "plugin <id> routes to boss://plugin?id=bookmarks"

    $pluginFwd = Invoke-BossBatLine $batPath 'plugin init "my-tool"'
    Assert-True ($pluginFwd.Trim() -ceq 'FORWARDED:plugin init "my-tool"') `
        "plugin init forwards to CLI executable"

    Remove-Item $sentinel -ErrorAction SilentlyContinue
    $fwdPayload = 'mcp x"=="x" echo pwned > "' + $sentinel + '" & rem "'
    $fwdOut = Invoke-BossBatLine $batPath $fwdPayload
    Assert-True (-not (Test-Path $sentinel)) "payload in forwarded command is data, not executed"
    Assert-True ($fwdOut -match 'FORWARDED:') "payload reaches stub as argument"

    # 3.9 Escaped redirection and pipe characters
    foreach ($line in @(
        'a^>capture-gt.txt',
        'a^>^>capture-gt.txt',
        'a^|echo side-effect^>sentinel.txt',
        'a^&echo side-effect^>sentinel.txt',
        'a^<capture-lt.txt'
    )) {
        Remove-Item $sentinel -ErrorAction SilentlyContinue
        $metaOut = Invoke-BossBatLine $batPath $line
        $stray = @(Get-ChildItem $winTestDir -Name | Where-Object { $_ -notin @('fake-boss.cmd', 'R&D file! %20 日本語.txt', 'R&D folder! 日本語') })
        Assert-True ($stray.Count -eq 0) "the capture line does not act on: boss $line (created: $($stray -join ', '))"
        Assert-True ($metaOut -match 'Error: Could not determine type for:') "boss $line reaches detection safely"
    }

    # 3.10 Quote payload injection tests (#1617)
    $quotePayloads = @(
        'x"=="x" echo side-effect>sentinel.txt & rem "',
        'url x"=="x" echo side-effect>sentinel.txt & rem "',
        'file x"=="x" echo side-effect>sentinel.txt & rem "',
        'ab" == "ab" echo side-effect>sentinel.txt & rem "'
    )
    foreach ($payload in $quotePayloads) {
        Remove-Item $sentinel -ErrorAction SilentlyContinue
        Invoke-BossBatLine $batPath $payload | Out-Null
        Assert-True (-not (Test-Path $sentinel)) "quote payload runs nothing: boss $payload"
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
        [void]$b.Proc.WaitForExit(60000)
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
    $takenDir = Join-Path $tempBase $fixedName
    New-Item -ItemType Directory -Path $takenDir -Force | Out-Null
    try {
        Set-Content -Path (Join-Path $takenDir 'args.txt') -Encoding Ascii -Value 'rem * #url "https://example.com/other-call"# '
        $takenOut = Invoke-BossBatLine $fixedBat 'url "https://example.com/my-call"'
        Assert-True ($takenOut -match 'could not read the command-line arguments') `
            "a taken capture directory is refused (got: $($takenOut.Trim()))"
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
    Remove-Item $winTestDir -Recurse -ErrorAction SilentlyContinue
}

Write-Output 'ALL Windows launcher argument-handling tests passed'
