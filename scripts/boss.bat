@echo off
REM BOSS CLI Launcher Script for Windows
REM Version: {{VERSION}}
REM Generated: {{BUILD_DATE}}
REM
REM Converts CLI commands to boss:// deep links and opens them
REM
REM Usage:
REM   boss url <url>                    # Opens URL in browser
REM   boss workspace <config>           # Loads workspace
REM   boss file <path>                  # Opens file in editor
REM   boss folder <path>                # Opens folder in codebase
REM   boss terminal                     # Opens terminal
REM   boss terminal -c <command>        # Opens terminal with command

setlocal enabledelayedexpansion

REM Check if no arguments provided
if "%~1"=="" (
    echo Error: No command specified
    echo Run 'boss --help' for usage information
    exit /b 1
)

REM Parse command
set "COMMAND=%~1"

if /i "%COMMAND%"=="url" goto :cmd_url
if /i "%COMMAND%"=="workspace" goto :cmd_workspace
if /i "%COMMAND%"=="file" goto :cmd_file
if /i "%COMMAND%"=="folder" goto :cmd_folder
if /i "%COMMAND%"=="terminal" goto :cmd_terminal
if /i "%COMMAND%"=="plugin" goto :cmd_plugin
if /i "%COMMAND%"=="--version" goto :cmd_version
if /i "%COMMAND%"=="-v" goto :cmd_version
if /i "%COMMAND%"=="version" goto :cmd_version
if /i "%COMMAND%"=="--help" goto :cmd_help
if /i "%COMMAND%"=="-h" goto :cmd_help
if /i "%COMMAND%"=="help" goto :cmd_help

REM Try smart detection for URL, file, or folder
call :detect_and_route "%~1"
exit /b 0

:cmd_url
if "%~2"=="" (
    echo Error: URL argument required
    echo Usage: boss url ^<url^>
    exit /b 1
)
call :urlencode "%~2" ENCODED
start "" "boss://url?url=%ENCODED%"
goto :eof

:cmd_workspace
if "%~2"=="" (
    echo Error: Workspace config path required
    echo Usage: boss workspace ^<config^>
    exit /b 1
)
call :urlencode "%~2" ENCODED
start "" "boss://workspace?config=%ENCODED%"
goto :eof

:cmd_file
if "%~2"=="" (
    echo Error: File path required
    echo Usage: boss file ^<path^>
    exit /b 1
)
call :urlencode "%~2" ENCODED
start "" "boss://file?path=%ENCODED%"
goto :eof

:cmd_folder
REM Use USERPROFILE directory as default if no path provided
if "%~2"=="" (
    set "folder_path=%USERPROFILE%"
) else (
    REM Expand relative paths (., .., etc.) to full path
    set "folder_path=%~f2"
)
call :urlencode "%folder_path%" ENCODED
start "" "boss://folder?path=%ENCODED%"
goto :eof

:cmd_terminal
REM Check for -c or --command flag
if /i "%~2"=="-c" goto :terminal_with_cmd
if /i "%~2"=="--command" goto :terminal_with_cmd
start "" "boss://terminal"
goto :eof

:terminal_with_cmd
if "%~3"=="" (
    echo Error: Command argument required after -c
    echo Usage: boss terminal -c ^<command^>
    exit /b 1
)
call :urlencode "%~3" ENCODED
start "" "boss://terminal?command=%ENCODED%"
goto :eof

:cmd_plugin
if "%~2"=="" (
    echo Error: Plugin ID required
    echo Usage: boss plugin ^<id^>
    exit /b 1
)
call :urlencode "%~2" ENCODED
start "" "boss://plugin?id=%ENCODED%"
goto :eof

:cmd_version
echo BOSS CLI version {{VERSION}}
echo Built: {{BUILD_DATE}}
goto :eof

:cmd_help
echo BOSS CLI - Business Operating System Service
echo Version: {{VERSION}}
echo.
echo Usage:
echo   boss ^<url-or-path^>             Auto-detect and open URL, file, or folder
echo   boss ^<command^> [arguments]     Run explicit command
echo.
echo Commands:
echo   url ^<url^>              Opens a URL in Fluck browser
echo   workspace ^<config^>     Loads a workspace configuration
echo   file ^<path^>            Opens a file in the editor
echo   folder [path]          Opens a folder in codebase (defaults to home)
echo   terminal               Opens a terminal tab
echo   terminal -c ^<command^>  Opens a terminal tab with command
echo   plugin ^<id^>            Opens any plugin/panel by ID
echo   version                Show CLI version information
echo   help                   Show this help message
echo.
echo Smart Detection Examples:
echo   boss google.com                 # Auto-detects as URL (adds https://)
echo   boss https://github.com         # Auto-detects as URL
echo   boss file.txt                   # Auto-detects as file (if exists)
echo   boss C:\Downloads               # Auto-detects as folder
echo   boss .                          # Auto-detects current directory
echo.
echo Explicit Command Examples:
echo   boss url https://example.com
echo   boss workspace C:\myworkspace.json
echo   boss file C:\path\to\file.kt
echo   boss folder                       # Opens home directory
echo   boss folder C:\path\to\project    # Opens specific directory
echo   boss terminal
echo   boss terminal -c "dir"
echo   boss plugin bookmarks
echo   boss plugin secret-manager
echo.
goto :eof

REM URL encode subroutine
REM Usage: call :urlencode "string to encode" OUTPUT_VAR
:urlencode
setlocal enabledelayedexpansion
set "str=%~1"
set "encoded="

REM PowerShell is more reliable for URL encoding on Windows
for /f "delims=" %%i in ('powershell -NoProfile -Command "[System.Uri]::EscapeDataString('%str%')"') do set "encoded=%%i"

endlocal & set "%~2=%encoded%"
goto :eof

REM Smart detection for URL, file, or folder
REM Usage: call :detect_and_route "argument"
:detect_and_route
setlocal
set "arg=%~1"

REM Check if it's a URL (has http:// or https://)
echo %arg% | findstr /i "^http://" >nul
if %errorlevel%==0 goto :detect_url
echo %arg% | findstr /i "^https://" >nul
if %errorlevel%==0 goto :detect_url

REM Check for common TLDs (looks like a domain)
echo %arg% | findstr /i "\.com" >nul
if %errorlevel%==0 goto :detect_domain
echo %arg% | findstr /i "\.org" >nul
if %errorlevel%==0 goto :detect_domain
echo %arg% | findstr /i "\.net" >nul
if %errorlevel%==0 goto :detect_domain
echo %arg% | findstr /i "\.io" >nul
if %errorlevel%==0 goto :detect_domain
echo %arg% | findstr /i "\.dev" >nul
if %errorlevel%==0 goto :detect_domain

REM Check if it's a file or folder
if exist "%arg%" (
    if exist "%arg%\*" (
        REM It's a directory - expand to full path
        set "fullpath=%~f1"
        call :urlencode "%fullpath%" ENCODED
        start "" "boss://folder?path=%ENCODED%"
        endlocal
        goto :eof
    ) else (
        REM It's a file - expand to full path
        set "fullpath=%~f1"
        call :urlencode "%fullpath%" ENCODED
        start "" "boss://file?path=%ENCODED%"
        endlocal
        goto :eof
    )
)

REM Could not detect type
echo Error: Could not determine type for: %arg%
echo.
echo Did you mean:
echo   boss url %arg%      - Open as URL
echo   boss file %arg%     - Open as file
echo   boss folder %arg%   - Open as folder
echo.
echo Run 'boss --help' for usage information
endlocal
exit /b 1

:detect_url
REM Has http:// or https:// - use as-is
call :urlencode "%arg%" ENCODED
start "" "boss://url?url=%ENCODED%"
endlocal
goto :eof

:detect_domain
REM Looks like a domain - add https://
call :urlencode "https://%arg%" ENCODED
start "" "boss://url?url=%ENCODED%"
endlocal
goto :eof
