# Windows Standalone Distribution Build Guide

## Quick Build
```cmd
# Run this script:
build-windows-standalone.bat
```

## Output
- **BOSS-Standalone-Windows.zip** (~150MB) - Complete portable package
- **BOSS-Standalone-Windows/** - Extracted package directory

## Package Contents
- `BOSS-Standalone.bat` - Main launcher (uses bundled Java)
- `BOSS-8.8.0-all.jar` - Main application (194MB)
- `jre/` - Bundled Java Runtime Environment 17
- `pty4j-native/` - Terminal native libraries
- `README-Standalone.md` - User documentation

## Build Requirements
- Internet connection for JRE download
- 2GB free disk space

## Distribution
✅ **No Java Installation Required by Users!**
- Send users: `BOSS-Standalone-Windows.zip`
- Users extract and run: `BOSS-Standalone.bat`
- Completely portable - works on any Windows 10+ system