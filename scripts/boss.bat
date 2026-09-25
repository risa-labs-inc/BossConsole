@echo off
REM BOSS CLI Launcher Script for Windows
REM Version: {{VERSION}}
REM Generated: {{BUILD_DATE}}
REM
REM Converts CLI commands to boss:// deep links and opens them
REM Delegates to boss.ps1 where arguments are handled safely as values.

setlocal DisableDelayedExpansion

set "BOSS_RAW_ARGS="
set "BOSS_ARGS_TRIES=0"
:claim_args_dir
set /a "BOSS_ARGS_TRIES+=1"
set "BOSS_ARGS_DIR=%TEMP%\boss-args-%RANDOM%%RANDOM%%RANDOM%"
md "%BOSS_ARGS_DIR%" >nul 2>&1 && goto :capture_args
if %BOSS_ARGS_TRIES% lss 20 goto :claim_args_dir
goto :check_args

:capture_args
setlocal
for %%a in (1) do (
    set "prompt=$_"
    echo on
    for %%b in (1) do rem * #%*#
    @echo off
) > "%BOSS_ARGS_DIR%\args.txt"
endlocal
if exist "%BOSS_ARGS_DIR%\args.txt" for /f "usebackq delims=" %%L in ("%BOSS_ARGS_DIR%\args.txt") do set "BOSS_RAW_ARGS=%%L"
rd /s /q "%BOSS_ARGS_DIR%" >nul 2>&1

:check_args
if not defined BOSS_RAW_ARGS (
    >&2 echo Error: could not read the command-line arguments
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0boss.ps1"
exit /b %ERRORLEVEL%
