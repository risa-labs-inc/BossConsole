# Windows Deep Link Setup for BOSS

This guide explains how the Windows deep link support works for the BOSS desktop application.

## How It Works

1. **Automatic Registration**: When BOSS launches on Windows, it automatically registers the `boss://` protocol in the Windows Registry.

2. **Registry Keys**: The following registry keys are created under `HKEY_CURRENT_USER\Software\Classes\boss`:
   - Default value: "URL:BOSS Protocol"
   - URL Protocol: (empty string)
   - DefaultIcon: Path to BOSS.exe
   - shell\open\command: Path to BOSS.exe with "%1" parameter

3. **Command Line Handling**: When a `boss://` link is clicked:
   - If BOSS is not running, Windows launches it with the URL as a command line argument
   - If BOSS is already running, the behavior depends on Windows version (may launch a new instance)

## Email Verification Flow

1. User clicks the verification link in their email
2. Browser opens and processes the verification with Supabase
3. Supabase redirects to `boss://auth/verify?token=xxx`
4. Windows intercepts this and either:
   - Launches BOSS with the URL as an argument (if not running)
   - Attempts to pass the URL to the running instance

## Testing

### Test if Protocol is Registered
```powershell
# Check if the protocol is registered
reg query "HKEY_CURRENT_USER\Software\Classes\boss"
```

### Test Deep Link
```powershell
# Open a test deep link
start boss://auth/verify?token=test123
```

### Manual Registration (if needed)
If automatic registration fails, you can manually register the protocol:

```batch
@echo off
set APP_PATH=%LOCALAPPDATA%\BOSS\BOSS.exe

reg add "HKEY_CURRENT_USER\Software\Classes\boss" /ve /d "URL:BOSS Protocol" /f
reg add "HKEY_CURRENT_USER\Software\Classes\boss" /v "URL Protocol" /d "" /f
reg add "HKEY_CURRENT_USER\Software\Classes\boss\DefaultIcon" /ve /d "%APP_PATH%,0" /f
reg add "HKEY_CURRENT_USER\Software\Classes\boss\shell\open\command" /ve /d "\"%APP_PATH%\" \"%%1\"" /f

echo BOSS protocol registered successfully!
pause
```

## Troubleshooting

### Protocol Not Working
1. Make sure BOSS is installed (not just running from IDE)
2. Check that the registry keys exist using `regedit`
3. Try running BOSS as administrator once to ensure registration
4. Check Windows Event Viewer for any errors

### Multiple Instances
If clicking a deep link opens a new instance instead of using the existing one:
- This is a Windows limitation
- The app should handle this gracefully by checking for existing instances
- Consider implementing single-instance logic if needed

### Antivirus Blocking
Some antivirus software may block registry modifications:
- Add BOSS to antivirus exceptions
- Temporarily disable antivirus during first launch
- Run the manual registration script as administrator

## Development vs Production

- **Development**: Deep links won't work when running from IDE
- **Production**: Works after installing from MSI/EXE installer
- **Testing**: Use the manual registration script for testing during development