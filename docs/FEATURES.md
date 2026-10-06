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

This subsystem measures **the BOSS process itself** for the operator. For measuring **other**
processes on an agent's request, see the `telemetry_*` MCP tools in
[docs/TELEMETRY.md](TELEMETRY.md). The two share a subject and nothing else: different audience,
different surface, different trigger.

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
- `WorkspaceCard.kt` - Workspace layouts, read from the same `WorkspaceManager` list the top bar and the app menu use

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

## Browser Engine Flags

Every Chromium engine tunable is configurable from **Settings > Browser Engine**. They were
environment variables first, and the variables all still work: settings are published as system
properties at startup, which slots them into `ConfigLoader`'s existing chain (env > system
property > local.properties > embedded), so **an environment variable still wins** and a row the
environment owns says so instead of appearing to do nothing.

Nothing applies to a running engine - Chromium's options are fixed when the engine is built, once
per process - so the screen offers a restart rather than pretending to be live.

**Capability-granting keys deliberately bypass `ConfigLoader`.** The sandbox opt-out, the
free-form extra switches and the DevTools port read the environment or a system property only,
never `local.properties` or the embedded build config. A line in a checkout's `local.properties`
would otherwise disable the Chromium sandbox for every future run of that checkout, and invisibly:
the opt-out is applied through `EngineOptions.disableSandbox()` rather than a switch, so no UI
surface could report it.

### Behaviour change in 9.4.x: gated switches in BOSS_CHROMIUM_EXTRA_SWITCHES

`--no-sandbox`, `--disable-setuid-sandbox`, `--disable-gpu-sandbox`, `--remote-debugging-port`,
`--remote-debugging-pipe` and `--remote-allow-origins` are **refused** from
`BOSS_CHROMIUM_EXTRA_SWITCHES` (and from the equivalent Settings field). Each has its own Settings
row behind a confirmation that spells out the exposure, and a confirmation that can be sidestepped
by typing into a free-form box is not a confirmation.

If you relied on passing those through that variable, use the Danger zone controls in
Settings > Browser Engine instead. Refused entries are listed in the UI and logged at startup,
under their own wording rather than as malformed input.

This is narrow on purpose and is **not** general switch sanitisation, which is not winnable:
`--disable-web-security`, `--proxy-server` and `--load-extension` all still pass. It closes only
the paths around a gate the app itself put up.

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
