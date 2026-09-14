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

## Registration Lifecycle (and why uninstall needs a hook)

Registration is done by the **running app**, not by the MSI. That is a deliberate
limitation, not a preference: the Compose Desktop Gradle plugin exposes no WiX
authoring hook (no `--resource-dir` override, no registry DSL), so the installer
cannot own the `boss:` keys without hand-maintaining a full jpackage `main.wxs`
replacement - which is tied to the JDK version that built it and cannot be verified
outside a Windows + WiX runner.

Consequences and the mitigations in place:

| Situation | Behavior |
|---|---|
| Fresh install, first launch | App registers `HKCU\Software\Classes\boss` (`WindowsProtocolHandler.registerProtocol`) |
| Moved/reinstalled to a new path | Startup re-registers when the stored command points at a missing exe (self-heal) |
| Another BOSS install still present and valid | Left alone - the app never steals a working registration |
| Command present but unreadable here (`REG_EXPAND_SZ`, unquoted, or the registry read failed) | Never *deleted* just because this code cannot read it - but see the caveat below |
| Root key present with no `shell\open\command` value (`reg query` reports it absent) | A partial registration this app produced, and removed |
| **Uninstalled** | The key survives and points at a deleted exe unless cleaned up (below) |

> The "left alone" guarantee is **delete-only**. `registerProtocol` still overwrites a
> command it cannot parse (its validity check fails closed and is evaluated before the
> "different valid install" check), so an installer-authored registration survives
> cleanup but not a re-registration. Worth resolving if/when the installer takes
> ownership of these keys.

### Cleaning up the registration

```powershell
# Preferred: let the app remove its own registration.
# BOSS.exe is a GUI-subsystem binary (windows { console = false }), so a plain
# invocation returns immediately and $LASTEXITCODE is NOT the app's exit code -
# use the waiting form to observe the result:
(Start-Process -FilePath "$env:LOCALAPPDATA\BOSS\BOSS.exe" `
    -ArgumentList '--unregister-protocol' -Wait -PassThru).ExitCode

# Manual equivalent
reg delete "HKEY_CURRENT_USER\Software\Classes\boss" /f
```

Exit codes: `0` nothing left to clean (removed, or nothing was registered, or not a
Windows host), `1` the registration was deliberately left in place (another live install,
or a command that could not be parsed), `2` the `reg delete` itself failed. An MSI custom
action waits for the process, so the contract is observable where it matters. There is no
console output - the BossLogger log file, if one is enabled with `BOSS_LOG_FILE` (see the Logging
section of AGENTS.md), is the only human-readable signal.

Run this **before** uninstalling, or wire it as an uninstall action when installer-owned
registration lands (tracked as follow-up work to issue #38 - the Rust host is expected
to register the protocol from its installer instead).

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