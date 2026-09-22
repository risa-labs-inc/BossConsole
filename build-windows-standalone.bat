@echo off
setlocal
echo ========================================
echo    BOSS Standalone Windows Creator
echo    (No Java Installation Required)
echo ========================================
echo.

set PACKAGE_DIR=%~dp0BOSS-Standalone-Windows
set JRE_DIR=%PACKAGE_DIR%\jre
set JRE_ZIP=%PACKAGE_DIR%\jre17.zip
set MANUAL_JRE=%~dp0jre17.zip

:: ---------------------------------------------------------------------------
:: Pinned JRE artifact: the current Temurin 17 GA release for Windows x64.
:: The URL is an immutable release asset paired with that artifact's own
:: SHA-256, recorded from the publisher on 2026-09-20 via two independent
:: sources that must agree:
::   1. the GitHub release "<asset>.sha256.txt" sidecar, and
::   2. the api.adoptium.net v3 assets endpoint (package.checksum).
:: Never substitute a "latest" or otherwise moving URL, and never downgrade
:: to an older security patch level: on failure this script stops with
:: instructions instead. Get-VerifiedDownload.ps1 refuses to promote bytes
:: that do not match.
set JRE_URL=https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip
set JRE_SHA=bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352

echo [INFO] Creating standalone package with bundled JRE...

:: Clean and create package directory
if exist "%PACKAGE_DIR%" rmdir /s /q "%PACKAGE_DIR%"
mkdir "%PACKAGE_DIR%"

:: Copy our existing application
echo [INFO] Copying BOSS application...
copy "BOSS-Windows-Package\BOSS-8.8.0-all.jar" "%PACKAGE_DIR%\"
copy "BOSS-Windows-Package\README.md" "%PACKAGE_DIR%\"
xcopy "BOSS-Windows-Package\pty4j-native" "%PACKAGE_DIR%\pty4j-native" /E /I /Q

:: A jre17.zip staged next to this script is accepted only after it verifies
:: against the pinned SHA-256 above - the same artifact, nothing else.
:: NOTE: fetch inputs travel in variables, never as CALL arguments - CALL
:: re-expands numbered arguments inside the subroutine, which would corrupt
:: the percent-encoded plus sign in the release URL into a positional
:: parameter reference.
if exist "%MANUAL_JRE%" (
    echo [INFO] Found manually provided %MANUAL_JRE% - verifying SHA-256...
    set JRE_SOURCE=%MANUAL_JRE%
    call :fetch_jre
    if not errorlevel 1 goto :extract
    echo [ERROR] %MANUAL_JRE% does not match the pinned JRE SHA-256 - refusing it
)

:: Download the pinned JRE release. The helper stages to a .part file,
:: requires the download command to succeed, verifies the artifact's SHA-256,
:: and only then promotes it to %JRE_ZIP%.
echo [INFO] Downloading portable Java Runtime (JRE 17)...
echo This may take a few minutes...

echo [INFO] Attempting Temurin 17.0.20.1+1 release asset...
set JRE_SOURCE=%JRE_URL%
call :fetch_jre
if not errorlevel 1 goto :extract

:: On failure, stop. Do not fall back to an older security patch level.
echo [ERROR] JRE download failed
echo.
echo Please manually download this exact JRE 17 release for Windows x64:
echo    %JRE_URL%
echo Then:
echo 1. Save it as: %MANUAL_JRE%
echo 2. Re-run this script - it will verify the SHA-256 before use.
echo    A file that does not match the pinned hash is refused.
echo.
pause
exit /b 1

:extract
:: Extract JRE (the archive was SHA-256 verified before promotion)
echo [INFO] Extracting JRE...
powershell -NoProfile -Command "Expand-Archive -Path '%JRE_ZIP%' -DestinationPath '%PACKAGE_DIR%\temp' -Force"
if errorlevel 1 (
    echo [ERROR] Failed to extract %JRE_ZIP%
    pause
    exit /b 1
)

:: Move JRE to correct location
echo [INFO] Setting up JRE structure...
for /d %%i in ("%PACKAGE_DIR%\temp\jdk*") do (
    echo Moving JRE from %%i
    move "%%i" "%JRE_DIR%"
    goto :moved
)

:: If no jdk* folder found, try other patterns
for /d %%i in ("%PACKAGE_DIR%\temp\*jre*") do (
    echo Moving JRE from %%i
    move "%%i" "%JRE_DIR%"
    goto :moved
)

:: Direct move if structure is different
if exist "%PACKAGE_DIR%\temp\bin\java.exe" (
    echo Moving direct JRE structure
    move "%PACKAGE_DIR%\temp" "%JRE_DIR%"
    goto :moved
)

:moved
if exist "%PACKAGE_DIR%\temp" rmdir /s /q "%PACKAGE_DIR%\temp"
del "%JRE_ZIP%"

if not exist "%JRE_DIR%\bin\java.exe" (
    echo [ERROR] JRE setup failed - java.exe not found
    echo Expected location: %JRE_DIR%\bin\java.exe
    echo.
    echo Please check the extracted files in: %JRE_DIR%
    pause
    exit /b 1
)

echo [SUCCESS] JRE extracted successfully

:: Test the JRE
echo [INFO] Testing JRE...
"%JRE_DIR%\bin\java.exe" -version
if errorlevel 1 (
    echo [WARNING] JRE test failed, but continuing...
) else (
    echo [SUCCESS] JRE is working correctly
)

:: Create standalone launcher that uses bundled JRE
echo [INFO] Creating standalone launcher...
(
echo @echo off
echo echo ========================================
echo echo        BOSS - Standalone Edition
echo echo        ^(No Java Installation Required^)
echo echo ========================================
echo echo.
echo echo Starting BOSS... Please wait.
echo echo.
echo.
echo :: Get the directory where this script is located
echo set SCRIPT_DIR=%%~dp0
echo.
echo :: Use bundled JRE
echo set JAVA_EXE=%%SCRIPT_DIR%%jre\bin\java.exe
echo.
echo :: Check if bundled JRE exists
echo if not exist "%%JAVA_EXE%%" ^(
echo     echo [ERROR] Bundled Java Runtime not found!
echo     echo Expected: %%JAVA_EXE%%
echo     echo Please ensure the 'jre' folder is in the same directory.
echo     pause
echo     exit /b 1
echo ^)
echo.
echo :: Launch with bundled JRE and optimized settings
echo echo [INFO] Using bundled Java Runtime...
echo "%%JAVA_EXE%%" -Xmx2g -Xms512m -Djava.library.path="%%SCRIPT_DIR%%pty4j-native" -Dpty4j.preferred.native.folder="%%SCRIPT_DIR%%pty4j-native" -jar "%%SCRIPT_DIR%%BOSS-8.8.0-all.jar" %%*
echo.
echo if errorlevel 1 ^(
echo     echo [ERROR] Failed to start BOSS
echo     echo Check if all files are present and try running as Administrator
echo     pause
echo ^)
) > "%PACKAGE_DIR%\BOSS-Standalone.bat"

:: Create info file
echo [INFO] Creating package information...
(
echo # BOSS Standalone Windows Edition
echo.
echo ## ✅ No Java Installation Required!
echo This package includes everything needed to run BOSS:
echo - BOSS Application ^(BOSS-8.8.0-all.jar^)
echo - Java Runtime Environment 17 ^(jre/ folder^) 
echo - Native Libraries ^(pty4j-native/ folder^)
echo.
echo ## 🚀 To Run:
echo **Double-click: BOSS-Standalone.bat**
echo.
echo ## 📦 Package Contents:
echo - `BOSS-Standalone.bat` - Main launcher
echo - `BOSS-8.8.0-all.jar` - Application JAR
echo - `jre/` - Bundled Java Runtime
echo - `pty4j-native/` - Terminal libraries
echo.
echo ## 💻 System Requirements:
echo - Windows 10 or higher
echo - 4GB RAM ^(8GB recommended^)
echo - 200MB disk space
echo - **No Java installation needed!**
echo.
echo ## 🔧 Troubleshooting:
echo - Ensure all folders are present ^(jre/, pty4j-native/^)
echo - Run from a path without spaces or special characters
echo - Add to antivirus exclusions if blocked
echo - Try "Run as Administrator" if startup fails
echo.
echo ## 📋 Version Information:
echo - BOSS Version: 8.8.0
echo - Java Runtime: OpenJDK 17 ^(bundled^)
echo - Platform: Windows x64
) > "%PACKAGE_DIR%\README-Standalone.md"

:: Create final ZIP
echo [INFO] Creating final standalone package...
set FINAL_ZIP=%~dp0BOSS-Standalone-Windows.zip
if exist "%FINAL_ZIP%" del "%FINAL_ZIP%"

echo [INFO] Compressing files (this may take a moment)...
powershell -Command "Compress-Archive -Path '%PACKAGE_DIR%\*' -DestinationPath '%FINAL_ZIP%' -CompressionLevel Optimal"

if exist "%FINAL_ZIP%" (
    echo.
    echo ========================================
    echo       ✅ STANDALONE PACKAGE CREATED!
    echo ========================================
    echo.
    echo 📁 Package: %FINAL_ZIP%
    echo 📏 Size: ~120-150MB ^(includes Java Runtime^)
    echo.
    echo 🎉 Users can now run BOSS WITHOUT installing Java!
    echo 📋 Instructions: Extract and run BOSS-Standalone.bat
    echo.
    echo 📂 Directory: %PACKAGE_DIR%
    echo 🧪 Test: Run BOSS-Standalone.bat to verify it works
    echo.
    echo ========================================
    echo Ready for distribution!
    echo ========================================
) else (
    echo [ERROR] Failed to create ZIP package
    echo Manual files available in: %PACKAGE_DIR%
)

echo.
pause
exit /b 0

:: ---------------------------------------------------------------------------
:fetch_jre
:: Takes no arguments on purpose: CALL re-expands numbered arguments in the
:: subroutine context, so passing a percent-containing value (like the
:: encoded plus sign in JRE_URL) as an argument would corrupt it. Reads
:: globals instead:
::   JRE_SOURCE = immutable https URL, or a local path for a staged jre17.zip
::   JRE_SHA    = expected SHA-256 (hex) recorded for that artifact
:: Returns errorlevel 0 only when the helper promoted a hash-verified file.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Get-VerifiedDownload.ps1" -Uri "%JRE_SOURCE%" -ExpectedSha256 "%JRE_SHA%" -Destination "%JRE_ZIP%"
if not errorlevel 1 if exist "%JRE_ZIP%" exit /b 0
exit /b 1