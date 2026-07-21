@echo off
REM Build BOSS Windows MSI with DigiCert KeyLocker signing

REM Load version from properties file
for /f "tokens=2 delims==" %%a in ('findstr "^app.version=" version.properties') do set APP_VERSION=%%a
if "%APP_VERSION%"=="" set APP_VERSION=8.8.0

echo Building BOSS version: %APP_VERSION%

echo ========================================
echo Building BOSS Windows MSI Distribution
echo ========================================

REM Step 1: Clean build
echo Step 1: Cleaning previous builds
call gradlew.bat clean
rmdir /s /q composeApp\build\compose\binaries 2>nul

REM Step 2: Build the application
echo Step 2: Building application
call gradlew.bat :composeApp:createMsi

if %ERRORLEVEL% neq 0 (
    echo Error: Gradle build failed
    exit /b 1
)

REM Step 3: Find the MSI file
echo Step 3: Locating MSI file
for /r "composeApp\build\compose\binaries\main\msi" %%f in (*.msi) do (
    set MSI_FILE=%%f
    goto :found_msi
)

echo Error: Could not find built MSI file
exit /b 1

:found_msi
echo Found MSI at: %MSI_FILE%

REM Step 4: Sign the MSI with DigiCert KeyLocker
echo Step 4: Signing MSI with DigiCert KeyLocker

if defined DIGICERT_API_KEY (
    echo Signing with DigiCert KeyLocker...
    
    REM Sign using DigiCert KeyLocker
    smctl sign --keypair-alias="boss_console_windows" --input="%MSI_FILE%" --verbose
    
    if %ERRORLEVEL% eq 0 (
        echo MSI signed successfully
    ) else (
        echo Warning: MSI signing failed - continuing with unsigned MSI
    )
) else (
    echo Warning: DigiCert credentials not found - MSI will be unsigned
)

REM Step 5: Create final distribution
echo Step 5: Creating final distribution

if not exist "distribution-final" mkdir "distribution-final"
copy "%MSI_FILE%" "distribution-final\BOSS-%APP_VERSION%-Windows.msi"

echo ========================================
echo Final Distribution Complete!
echo ========================================
echo Location: distribution-final\BOSS-%APP_VERSION%-Windows.msi
echo.
echo Ready for distribution!