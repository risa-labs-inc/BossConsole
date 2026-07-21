@echo off
:: BOSS Application Launcher Script for Windows
:: This script launches the BOSS application from the JAR file

:: Get the directory where this script is located
set SCRIPT_DIR=%~dp0

:: Set Java options
set JAVA_OPTS=-Xmx2g -Xms512m

:: Set native library paths
set JAVA_OPTS=%JAVA_OPTS% -Djava.library.path=%SCRIPT_DIR%jcef-natives;%SCRIPT_DIR%pty4j-native
set JAVA_OPTS=%JAVA_OPTS% -Dpty4j.preferred.native.folder=%SCRIPT_DIR%pty4j-native
set JAVA_OPTS=%JAVA_OPTS% -Djcef.path=%SCRIPT_DIR%jcef-natives

:: Launch the application
java %JAVA_OPTS% -jar "%SCRIPT_DIR%BOSS-8.8.0-all.jar" %*