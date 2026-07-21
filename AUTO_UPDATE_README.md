# BOSS Auto-Update System

## 🚀 Overview

The BOSS application now includes a comprehensive auto-update system that automatically checks for new releases and allows users to update their application seamlessly.

## ✨ Features

- **Automatic Update Checks**: Checks for updates every 6 hours in the background
- **Multiple Distribution Support**: Handles DMG (macOS), MSI (Windows), and JAR (cross-platform)
- **GitHub Integration**: Uses GitHub Releases API from `risa-labs-inc/BossConsole-Releases`
- **Progress Tracking**: Shows download progress with visual indicators
- **Non-Intrusive UI**: Update banners appear only when needed
- **Settings Integration**: Update controls in Settings → Updates section

## 🔧 How It Works

### 1. **Version Management**
- Current version: `Version.CURRENT = Version(8, 8, 0)` in `Version.kt`
- Semantic versioning with pre-release support
- Version comparison logic handles `major.minor.patch[-prerelease]` format

### 2. **Update Checking**
- Fetches latest release from GitHub API: `https://api.github.com/repos/risa-labs-inc/BossConsole-Releases/releases`
- Compares current version with latest release
- Filters out drafts and pre-releases for stable updates
- Background checks every 6 hours, manual checks available

### 3. **Platform Detection**
- **macOS**: Downloads `BOSS-{version}-Universal.dmg`
- **Windows**: Downloads `BOSS-{version}.msi`  
- **Other/JAR**: Downloads `BOSS-{version}-all.jar`

### 4. **Installation Process**
- **macOS**: Opens DMG for user installation
- **Windows**: Launches MSI installer
- **JAR**: Replaces current JAR file with backup

## 📱 User Interface

### Update Banner
- Appears at top of application when updates are available
- Shows current version, available version, and release notes
- Download button with progress indicator
- Install button when download completes

### Settings Panel
- **Settings → Updates** section
- Current version information
- Last check timestamp
- Manual "Check for Updates" button
- Update status indicators

## 🛠️ Technical Architecture

```
UpdateManager (Singleton)
├── UpdateService (Platform-specific)
│   ├── checkForUpdates()
│   ├── downloadUpdate()
│   └── installUpdate()
├── Background checking (6-hour interval)
├── State management (UpdateState flow)
└── UI integration (UpdateBanner, UpdateSettingsSection)
```

### Key Files
- `Version.kt` - Version management and comparison
- `UpdateService.kt` - Common update interface
- `DesktopUpdateService.kt` - Desktop implementation
- `UpdateManager.kt` - Central update coordination
- `UpdateUI.kt` - UI components

## ⚙️ Configuration

### Updating Version
When releasing a new version, update:
```kotlin
// In Version.kt
val CURRENT = Version(8, 9, 0) // Example: next version
```

### GitHub Repository
- Source: `risa-labs-inc/BossConsole-Releases`
- Expected assets:
  - `BOSS-{version}-Universal.dmg` (macOS)
  - `BOSS-{version}.msi` (Windows)
  - `BOSS-{version}-all.jar` (fallback)

### Update Frequency
```kotlin
// In UpdateManager.kt
private const val CHECK_INTERVAL_HOURS = 6L // Adjust as needed
```

## 🔒 Security

- Code-signed DMG and MSI installers supported
- HTTPS-only downloads from GitHub
- Backup creation before JAR updates
- Error handling prevents application crashes

## 📊 Update States

- `Idle` - No update activity
- `CheckingForUpdates` - Fetching latest version info
- `UpToDate` - Running latest version
- `UpdateAvailable` - New version found
- `Downloading` - Update download in progress
- `ReadyToInstall` - Download complete, ready to install
- `Installing` - Installation in progress
- `RestartRequired` - Installation complete, restart needed
- `Error` - Update process failed

## 🚦 Usage

1. **Automatic**: Updates check automatically in background
2. **Manual**: Go to Settings → Updates → "Check for Updates"
3. **Banner**: Click "Download" when update banner appears
4. **Install**: Click "Install Now" when download completes
5. **Restart**: Restart application when prompted

## 🏗️ Future Enhancements

- Automatic restart after installation
- Delta updates for smaller downloads  
- Update rollback functionality
- Custom update channels (stable, beta, alpha)
- Offline update support

## 🐛 Troubleshooting

- Check network connectivity if updates fail to check
- Ensure sufficient disk space for downloads
- DMG/MSI installation may require administrator privileges
- JAR updates create `.backup.jar` files for recovery

---

**The auto-update system ensures BOSS users always have access to the latest features and security updates!** 🎉