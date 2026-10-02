@echo off
REM BOSS CLI Launcher Script for Windows
REM Version: {{VERSION}}
REM Generated: {{BUILD_DATE}}
REM
REM Converts CLI commands to boss:// deep links and opens them
REM Delegates to boss.ps1 where arguments are handled safely as values.
REM
REM Note: cmd.exe expands defined %VAR% references in the parent command line
REM before boss.bat is invoked. Undefined %VAR% references remain literal.

setlocal EnableExtensions DisableDelayedExpansion

set "BOSS_RAW_ARGS="
set "BOSS_LAUNCHER_NONCE="
set "BOSS_ARGS_TRIES=0"
:claim_args_dir
set /a "BOSS_ARGS_TRIES+=1"
set "BOSS_ARGS_DIR=%TEMP%\boss-args-%RANDOM%%RANDOM%%RANDOM%"
md "%BOSS_ARGS_DIR%" >nul 2>&1 && goto :capture_args
if %BOSS_ARGS_TRIES% lss 20 goto :claim_args_dir
goto :check_args

:capture_args
set "BOSS_LAUNCHER_NONCE=%RANDOM%%RANDOM%%RANDOM%"
setlocal
for %%a in (1) do (
    set "prompt=$_"
    echo on
    for %%b in (1) do rem * #%BOSS_LAUNCHER_NONCE%#%*#
    @echo off
) > "%BOSS_ARGS_DIR%\args.txt"
endlocal
REM Read args.txt to capture the echoed REM line containing the nonce and arguments.
REM Depending on the cmd.exe build and prompt environment:
REM - Some cmd builds (e.g. windows-2025) echo only the expanded 'rem * #<nonce>#...' line (single line).
REM - Other cmd builds echo the outer 'for ... do rem *' statement first and the expanded 'rem * #<nonce>#...' line last.
REM Iterating all non-blank lines ensures the executed rem line is captured in both cases (last line wins).
REM The anchored regex in boss.ps1 (^rem \* #<nonce>#(.*)#\s*$) is the authoritative guard,
REM rejecting any malformed line or mismatched nonce fail-closed.
if exist "%BOSS_ARGS_DIR%\args.txt" for /f "usebackq delims=" %%L in ("%BOSS_ARGS_DIR%\args.txt") do set "BOSS_RAW_ARGS=%%L"
rd /s /q "%BOSS_ARGS_DIR%" >nul 2>&1

:check_args
if not defined BOSS_RAW_ARGS (
    >&2 echo Error: could not read the command-line arguments
    exit /b 1
)

set "BOSS_ARGS_DIR="
set "BOSS_ARGS_TRIES="

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0boss.ps1" -LauncherNonce "%BOSS_LAUNCHER_NONCE%"
set "BOSS_RAW_ARGS="
set "BOSS_LAUNCHER_NONCE="
exit /b %ERRORLEVEL%
