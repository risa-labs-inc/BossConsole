# Application Features

This document covers major BOSS Console features.

## Performance Monitoring

Real-time system metrics monitoring with configurable thresholds and visualization.

**Key Files**:
- `PerformanceMonitor.kt` - Singleton collecting CPU, memory, GC, and resource metrics
- `PerformanceMetrics.kt` - Data classes for metrics snapshots
- `PerformanceSettings.kt` - Configurable warning/critical thresholds
- `PerformanceSettingsManager.kt` - Settings persistence (`~/.boss/performance-settings.json`)
- `PerformanceView.kt` - Bottom panel visualization with charts

**Features**:
- Real-time CPU and memory usage tracking
- GC collection monitoring
- Resource counting (browser tabs, terminals, editor tabs, panels, windows)
- History retention (configurable, up to 180 minutes)
- Performance indicator in status bar
- Exportable metrics history

## Dashboard System

Start screen with cards for quick access to recent items and actions.

**Key Files**:
- `Dashboard.kt` - Main dashboard composable
- `DashboardEventBus.kt` - Event distribution for dashboard updates
- `RecentBrowserPagesManager.kt` - Browser history tracking
- `DashboardStatsManager.kt` - Statistics and metrics

**Card Types**:
- `BrowserPageCard.kt` - Recent browser pages
- `FileCard.kt` - Recent files
- `ProjectCard.kt` - Project suggestions
- `ActionCard.kt` - Quick actions
- `SplitTemplateCard.kt` - Layout templates

## Download Manager

Browser download tracking integrated with Fluck browser.

**Key Files**:
- `DownloadManager.kt` - Core download state management with thread-safe updates
- `DownloadState.kt` - Download status tracking (queued, downloading, paused, completed, failed)
- `DownloadPanel.kt` - Desktop download panel UI
- `Downloads.kt` - Downloads sidebar panel

**Features**:
- Progress tracking with speed calculation
- Pause/resume support
- Download history
- File open/reveal actions

## Chromium Branding

BOSS uses custom-branded Chromium builds for JxBrowser integration.

**Key Files**:
- `ChromiumDownloader.kt` - CI utility for downloading and branding Chromium
- `FluckEngine.kt` - Browser engine with branded Chromium detection

**Build Process**:
- Chromium binaries are pre-downloaded during CI
- Custom branding applied via TeamDev's Chromium-Branding tool
- Branded Chromium stored in `~/.boss/boss-chromium/` or app bundle
- No fallback to standard JxBrowser Chromium (BOSS-branded required)

**CI Workflow**:
1. Download Chromium binaries for target platform
2. Apply BOSS branding (user-agent, window titles, etc.)
3. Bundle with application or cache in user directory
4. FluckEngine detects and uses branded binaries at runtime
