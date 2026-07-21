@echo off
echo ========================================
echo    BOSS Standalone Windows Creator
echo    (No Java Installation Required)
echo ========================================
echo.

set PACKAGE_DIR=%~dp0BOSS-Standalone-Windows
set JRE_DIR=%PACKAGE_DIR%\jre

echo [INFO] Creating standalone package with bundled JRE...

:: Clean and create package directory
if exist "%PACKAGE_DIR%" rmdir /s /q "%PACKAGE_DIR%"
mkdir "%PACKAGE_DIR%"

:: Copy our existing application
echo [INFO] Copying BOSS application...
copy "BOSS-Windows-Package\BOSS-8.8.0-all.jar" "%PACKAGE_DIR%\"
copy "BOSS-Windows-Package\README.md" "%PACKAGE_DIR%\"
xcopy "BOSS-Windows-Package\pty4j-native" "%PACKAGE_DIR%\pty4j-native" /E /I /Q

:: Try multiple JRE download sources
echo [INFO] Downloading portable Java Runtime (JRE 17)...
echo This may take a few minutes...

:: Method 1: Try latest release from Adoptium API
echo [INFO] Attempting to download from Adoptium API...
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { $apiUrl = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse'; Write-Host 'Downloading JRE from API...'; Invoke-WebRequest -Uri $apiUrl -OutFile '%PACKAGE_DIR%\jre17.zip' -UserAgent 'Mozilla/5.0' -MaximumRedirection 5; Write-Host 'Download successful from API'; } catch { Write-Host 'API download failed, will try alternative'; exit 1; } }"

if exist "%PACKAGE_DIR%\jre17.zip" goto :extract

:: Method 2: Try specific known release
echo [INFO] Trying specific release URL...
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { $url = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jre_x64_windows_hotspot_17.0.11_9.zip'; Write-Host 'Downloading from GitHub release...'; Invoke-WebRequest -Uri $url -OutFile '%PACKAGE_DIR%\jre17.zip' -UserAgent 'Mozilla/5.0'; Write-Host 'Download successful from GitHub'; } catch { Write-Host 'GitHub download failed'; exit 1; } }"

if exist "%PACKAGE_DIR%\jre17.zip" goto :extract

:: Method 3: Try another recent release
echo [INFO] Trying alternative release...
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { $url = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jre_x64_windows_hotspot_17.0.12_7.zip'; Write-Host 'Downloading alternative release...'; Invoke-WebRequest -Uri $url -OutFile '%PACKAGE_DIR%\jre17.zip' -UserAgent 'Mozilla/5.0'; Write-Host 'Download successful'; } catch { Write-Host 'Alternative download failed'; exit 1; } }"

if exist "%PACKAGE_DIR%\jre17.zip" goto :extract

:: If all downloads failed
echo [ERROR] All download attempts failed
echo.
echo Please manually download JRE 17 for Windows x64:
echo 1. Go to: https://adoptium.net/temurin/releases/
echo 2. Select: JRE, Version 17, Windows x64
echo 3. Download the ZIP file (not MSI)
echo 4. Save as: %PACKAGE_DIR%\jre17.zip
echo 5. Re-run this script
echo.
pause
exit /b 1

:extract
:: Extract JRE
echo [INFO] Extracting JRE...
powershell -Command "Expand-Archive -Path '%PACKAGE_DIR%\jre17.zip' -DestinationPath '%PACKAGE_DIR%\temp' -Force"

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
del "%PACKAGE_DIR%\jre17.zip"

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