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
     * Resident memory of the renderer behind the browser on screen in [windowId], or 0.
     *
     * Resolved per window rather than sampled into the snapshot, for two reasons. The value is
     * only ever drawn live in a status bar, so putting it in `MemoryMetrics` would persist a
     * live-only figure into every one of the 10,000 retained history entries and into exported
     * snapshots, where it means nothing. And it differs per window: a single sampled value would
     * show one window's tab in the other window's strip.
     *
     * 0 means unknown - no browser on screen, a tab just switched, a renderer that has not
     * committed a document yet - and must be rendered as absent, never as a zero.
     */
    fun activeBrowserBytes(windowId: String): Long

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
     * Clear resource providers to prevent memory leaks.
     * Should be called when BossApp is disposed.
     */
    fun clearResourceProviders()
}
