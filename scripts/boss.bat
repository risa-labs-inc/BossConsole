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

REM delayed expansion is OFF at the top level: EnableDelayedExpansion would let
REM a literal ! in an argument (e.g. "boss file 'foo!bar.txt'") get eaten by
REM the parser before :urlencode can hand the value to PowerShell. Only
REM :check_arg_quotes turns it on, inside its own scope, to read a value that
REM is already in a variable.
setlocal DisableDelayedExpansion

REM Refuse a " inside an argument before anything reads one (#1617). Every read
REM below wraps the argument in quotes - if "%~1"=="" - and cmd substitutes %~1
REM before it parses the line, so a quote in the value closes that quote early:
REM x"=="x" calc & rem " turns the first check into if "x"=="x" calc & rem ...
REM and runs calc. Checking needs the raw text without cmd parsing it, and an
REM echoed REM line is the one place cmd writes %* out untouched, so capture it
REM there and read it back through a FOR variable.
REM The capture goes in a directory this call claims with md, which fails when
REM the directory exists: %RANDOM% is seeded from the clock, so two boss calls
REM started together would otherwise share a file name and could each read and
REM check the other's arguments. If no directory can be claimed, nothing is
REM captured and :check_arg_quotes refuses the call.
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
call :check_arg_quotes || exit /b 1
REM The forwarded commands pass %* on after :check_arg_quotes has also
REM refused unquoted cmd control characters in their argument tail.
set "BOSS_RAW_ARGS="
set "BOSS_ARGS_DIR="
set "BOSS_ARGS_TRIES="

REM Check if no arguments provided
if "%~1"=="" (
    echo Error: No command specified
    echo Run 'boss --help' for usage information
    exit /b 1
)

REM Parse command
set "COMMAND=%~1"

if /i "%COMMAND%"=="status" goto :cmd_forward_exe
if /i "%COMMAND%"=="doctor" goto :cmd_forward_exe
if /i "%COMMAND%"=="mcp" goto :cmd_forward_exe
if /i "%COMMAND%"=="completion" goto :cmd_forward_exe
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
if "%~2"=="" goto :cmd_forward_exe
if /i "%~2"=="init" goto :cmd_forward_exe
if /i "%~2"=="validate" goto :cmd_forward_exe
if /i "%~2"=="link" goto :cmd_forward_exe
if /i "%~2"=="--help" goto :cmd_forward_exe
if /i "%~2"=="-h" goto :cmd_forward_exe
if /i "%~2"=="help" goto :cmd_forward_exe
if not "%~3"=="" goto :cmd_forward_exe

call :urlencode "%~2" ENCODED
start "" "boss://plugin?id=%ENCODED%"
goto :eof

:cmd_forward_exe
REM Preserve literal exclamation marks in JSON arguments.
setlocal DisableDelayedExpansion
if defined BOSS_EXE if not exist "%BOSS_EXE%" goto :cmd_missing_exe
if not defined BOSS_EXE if exist "%LOCALAPPDATA%\Programs\BOSS\BOSS.exe" set "BOSS_EXE=%LOCALAPPDATA%\Programs\BOSS\BOSS.exe"
if not defined BOSS_EXE if exist "%ProgramFiles%\BOSS\BOSS.exe" set "BOSS_EXE=%ProgramFiles%\BOSS\BOSS.exe"
if not defined BOSS_EXE if exist "%~dp0..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe" set "BOSS_EXE=%~dp0..\composeApp\build\compose\binaries\main\app\BOSS\BOSS.exe"
if not defined BOSS_EXE goto :cmd_missing_exe
if exist "%~dp0boss.ps1" goto :cmd_forward_ps
"%BOSS_EXE%" %*
exit /b %ERRORLEVEL%
goto :eof

:cmd_forward_ps
REM The exit code rides out on its own line, parsed only after powershell
REM returns, so %ERRORLEVEL% still holds the launcher's result. Inside a
REM parenthesized block the whole block parses first, where %ERRORLEVEL% is
REM stale and !ERRORLEVEL! stays literal because delayed expansion is off.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0boss.ps1" %*
exit /b %ERRORLEVEL%
goto :eof

:cmd_missing_exe
>&2 echo Error: BOSS application binary not found. Set BOSS_EXE to the packaged executable.
exit /b 1

:cmd_version
echo BOSS CLI version {{VERSION}}
echo Built: {{BUILD_DATE}}
goto :eof

:cmd_help
echo BOSS CLI - Business Operating System Service
echo Version: {{VERSION}}
echo Built: {{BUILD_DATE}}
echo.
echo Usage:
echo   boss ^<url-or-path^>             Auto-detect and open URL, file, or folder
echo   boss ^<command^> [arguments]     Run explicit command
echo.
echo Commands:
echo   status                 Queries status and health of the running BOSS instance
echo   doctor                 Reports problems in the running BOSS instance (exit 2 when degraded)
echo   mcp ^<action^> [args]    Discovers and invokes desktop MCP tools (list, describe, invoke)
echo   completion ^<shell^>     Generates shell completion script (bash, zsh, fish)
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

REM Refuse a quote inside an argument (#1617). Reads BOSS_RAW_ARGS, the raw
REM command line captured at the top.
REM Usage: call :check_arg_quotes || exit /b 1
REM An argument may be quoted as a whole and hold no quote inside. That is all a
REM path or a URL needs, and it leaves no %~N that can carry a quote into the
REM quoted reads above. So a " may only open at the start or after a space, and
REM only close at the end or before a space. Delayed expansion is on here only:
REM the value is already in a variable, and !var! reads are never re-parsed.
REM The first argument is always checked. status, doctor, mcp, completion and
REM plugin may hand arguments to BOSS.exe as a bare %*, which cmd parses again.
REM Their argument tails may hold quotes, as
REM `boss mcp invoke tool --args {"q":"x"}` does, but an unquoted ^&, ^|, ^<, ^>
REM or caret is refused before the second parse can interpret it (#1673).
REM plugin keeps the strict quote grammar because it reads %~2 and %~3 before
REM deciding whether to forward, but its forwarded tail still needs the
REM metacharacter guard.
:check_arg_quotes
setlocal EnableDelayedExpansion
if not defined BOSS_RAW_ARGS (
    echo Error: could not read the command-line arguments
    endlocal & exit /b 1
)
REM The captured line is `rem * #<arguments>#`; keep what is between the marks.
REM cmd echoes it with a trailing space after the closing mark, so the spaces
REM go first; without that the mark stays, and a quoted last argument reads as
REM "...# with a quote that closes before a # rather than a space.
set "rest=!BOSS_RAW_ARGS:*#=!"
:check_arg_quotes_trim
if defined rest if "!rest:~-1!"==" " set "rest=!rest:~0,-1!" & goto :check_arg_quotes_trim
if defined rest if "!rest:~-1!"=="#" set "rest=!rest:~0,-1!"
REM Length first, so the walk below stops at the last character.
set "s=!rest!#"
set "len=0"
for %%P in (4096 2048 1024 512 256 128 64 32 16 8 4 2 1) do if not "!s:~%%P,1!"=="" (
    set /a "len+=%%P"
    set "s=!s:~%%P!"
)
set /a "last=len-1"
set q=^"
set caret=^^
set "tab=	"
set "open="
set "prev= "
set "bad="
set "badMeta="
set "first="
set "token="
set "tokenStarted="
set "tokenDone="
set "forwarded="
set "loose="
for /l %%i in (0,1,!last!) do if not defined bad if not defined badMeta (
    set "c=!rest:~%%i,1!"
    if "!c!"=="!q!" (
        set "looseQuote="
        if defined loose if defined tokenDone set "looseQuote=1"
        if defined looseQuote (
            if defined open (
                set "open="
            ) else (
                set "open=1"
            )
        ) else (
            if defined open (
                set /a "n=%%i+1"
                for %%n in (!n!) do set "next=!rest:~%%n,1!"
                if defined next if not "!next!"==" " set "bad=1"
                set "open="
            ) else (
                if not "!prev!"==" " set "bad=1"
                set "open=1"
            )
        )
    )
    set "isDelim="
    if not defined open (
        if "!c!"==" " set "isDelim=1"
        if "!c!"=="!tab!" set "isDelim=1"
        if "!c!"=="," set "isDelim=1"
        if "!c!"==";" set "isDelim=1"
        if "!c!"=="=" set "isDelim=1"
    )
    if not defined tokenDone (
        if defined tokenStarted (
            if defined isDelim (
                set "first=!token!"
                if "!first:~0,1!"=="!q!" set "first=!first:~1!"
                if "!first:~-1!"=="!q!" set "first=!first:~0,-1!"
                set "tokenDone=1"
                for %%v in (status doctor mcp completion plugin) do if /i "!first!"=="%%v" set "forwarded=1"
                for %%v in (status doctor mcp completion) do if /i "!first!"=="%%v" set "loose=1"
            ) else (
                set "token=!token!!c!"
            )
        ) else (
            if not defined isDelim (
                set "tokenStarted=1"
                set "token=!c!"
            )
        )
    )
    if defined forwarded if defined tokenDone if not defined open (
        if "!c!"=="&" set "badMeta=1"
        if "!c!"=="|" set "badMeta=1"
        if "!c!"=="<" set "badMeta=1"
        if "!c!"==">" set "badMeta=1"
        if "!c!"=="!caret!" set "badMeta=1"
    )
    set "prev=!c!"
)
if defined bad (
    echo Error: an argument has a double quote inside it.
    echo Quote a whole argument, e.g. boss file "C:\My Files\a.txt". In a URL, write a quote as %%22.
    echo For a terminal command that needs a quote, run boss.ps1 instead.
    endlocal & exit /b 1
)
if defined badMeta (
    echo Error: command arguments contain an unquoted cmd control character.
    echo Quote the whole argument, or run boss.ps1 when literal shell syntax is required.
    endlocal & exit /b 1
)
endlocal & exit /b 0

REM URL encode subroutine
REM Usage: call :urlencode "string to encode" OUTPUT_VAR
:urlencode
REM Never interpolate the value into the PowerShell command line (#1057):
REM a single quote in the value used to close the PS string literal and
REM execute whatever followed it. The value now travels through the
REM environment, which PowerShell reads unexpanded, and delayed expansion
REM is disabled for this block so ! survives the round-trip.
setlocal DisableDelayedExpansion
set "str=%~1"
set "encoded="

REM PowerShell is more reliable for URL encoding on Windows. The value is
REM cast to [string] so a missing env var reads as '' (not $null), and the
REM empty-result guard keeps [System.Uri]::EscapeDataString from ever being
REM called with $null - which throws ArgumentNullException on every input.
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$v = [string][Environment]::GetEnvironmentVariable('str'); if (-not $v) { '' } else { [System.Uri]::EscapeDataString($v) }"`) do set "encoded=%%i"

endlocal & set "%~2=%encoded%"
goto :eof

REM Smart detection for URL, file, or folder
REM Usage: call :detect_and_route "argument"
:detect_and_route
REM Delayed expansion is OFF here too: nothing in this function reads !var!
REM (the only ! in this script are comments at :17/:18/:143/:208). EnableDelayedExpansion
REM would re-create the literal-!-eating defect the top-level DisableDelayedExpansion
REM just fixed, on the auto-detect path and on the %ENCODED% reads in
REM :detect_url and :detect_domain.
REM Exit with the matching endlocal at each branch below.
setlocal DisableDelayedExpansion
set "arg=%~1"

REM Every read of arg below is inside quotes. cmd substitutes the value into
REM the line before it parses the line, so an unquoted read lets an ampersand
REM in the argument, say a file named R and D.txt, end the command and run the rest as
REM a second one. The checks used to echo arg into findstr, which did exactly
REM that on every line; they are now plain string tests with no subshell.
REM (No percent-wrapped arg in these comments: cmd expands it in REM lines.)

REM Check if it's a URL (has http:// or https://)
if /i "%arg:~0,7%"=="http://" goto :detect_url
if /i "%arg:~0,8%"=="https://" goto :detect_url

REM Check for common TLDs (looks like a domain). Substring replacement is
REM case-insensitive, so the value only changes when it contains the TLD in
REM any case - the same answer findstr /i gave.
if not "%arg:.com=%"=="%arg%" goto :detect_domain
if not "%arg:.org=%"=="%arg%" goto :detect_domain
if not "%arg:.net=%"=="%arg%" goto :detect_domain
if not "%arg:.io=%"=="%arg%" goto :detect_domain
if not "%arg:.dev=%"=="%arg%" goto :detect_domain

REM Check if it's a file or folder. Variables read inside a parenthesized
REM block expand at parse time, before any set/call fills them, so the
REM file/folder branches goto out of the block the same way :detect_url
REM and :detect_domain already do and read %fullpath%/%ENCODED% at the
REM top level instead. Without that, `boss ./file.txt` reaches
REM boss://file?path= with an empty path.
if exist "%arg%" goto :detect_file_or_folder

REM Could not detect type
echo Error: Could not determine type for: "%arg%"
echo.
echo Did you mean:
echo   boss url "%arg%"      - Open as URL
echo   boss file "%arg%"     - Open as file
echo   boss folder "%arg%"   - Open as folder
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

:detect_file_or_folder
if exist "%arg%\*" goto :detect_folder
goto :detect_file

:detect_folder
REM It's a directory - expand to full path
set "fullpath=%~f1"
call :urlencode "%fullpath%" ENCODED
start "" "boss://folder?path=%ENCODED%"
endlocal
goto :eof

:detect_file
REM It's a file - expand to full path
set "fullpath=%~f1"
call :urlencode "%fullpath%" ENCODED
start "" "boss://file?path=%ENCODED%"
endlocal
goto :eof
