package ai.rever.boss.performance

import androidx.compose.runtime.Composable

/**
 * Platform-specific performance state access.
 * Desktop implementation uses PerformanceMonitor, other platforms return null/defaults.
 */
expect object PerformanceState {
    /**
     * Get current performance snapshot as a composable state.
     * Returns null on platforms without performance monitoring.
     */
    @Composable
    fun currentSnapshot(): PerformanceSnapshot?

    /**
     * Get current health status as a composable state.
     */
    @Composable
    fun currentHealth(): PerformanceHealth

    /**
     * Check if performance indicator should be shown.
     */
    @Composable
    fun shouldShowIndicator(): Boolean

    /**
     * Report the status-bar indicator entering or leaving the composition.
     *
     * Lets the platform skip work nothing can display. Whole-process memory sampling is the only
     * consumer of this, and it is not free, so a session whose bottom bar is hidden should not pay
     * for a number it never draws. Driven from the composable's own lifecycle rather than from the
     * three settings that decide visibility, so it cannot drift from what is actually on screen.
     */
    fun setIndicatorMounted(mounted: Boolean)

    /**
     * Open the performance panel.
     */
    fun openPerformancePanel()

    /**
     * Toggle the performance panel (open if closed, close if open).
     */
    fun togglePerformancePanel()

    /**
     * Register resource count providers.
     * Should be called once from BossApp with functions that return current counts.
     */
    fun registerResourceProviders(
        browserTabs: () -> Int,
        terminals: () -> Int,
        editorTabs: () -> Int,
        panels: () -> Int,
        windows: () -> Int,
    )

    /**
     * Register detailed resource providers for the Resources tab.
     * Provides detailed information about each resource (titles, URLs, paths, etc.)
     */
    fun registerDetailedResourceProviders(
        browserTabs: () -> List<BrowserTabInfo>,
        terminals: () -> List<TerminalInfo>,
        editorTabs: () -> List<EditorTabResourceInfo>,
    )

    /**
     * Clear resource providers to prevent memory leaks.
     * Should be called when BossApp is disposed.
     */
    fun clearResourceProviders()
}
