package ai.rever.boss.focusmode

import kotlinx.serialization.Serializable

/**
 * Configuration for Focus Mode feature.
 * Focus Mode minimizes distractions by hiding UI chrome (top bar, sidebars, bottom bar)
 * while keeping tabs and main content visible.
 *
 * @property enabled Whether focus mode is currently active
 * @property autoRevealEnabled Whether to auto-reveal hidden bars on mouse hover at edges
 * @property revealOffsetPx Distance in pixels from window edge to trigger auto-reveal
 * @property revealDelayMs Delay in milliseconds before reveal triggers after hovering at edge
 */
@Serializable
data class FocusModeSettings(
    val enabled: Boolean = false,
    val autoRevealEnabled: Boolean = true,
    val revealOffsetPx: Float = 30f,
    val revealDelayMs: Long = 500L,
) {
    companion object {
        /**
         * Whether edge hover-to-reveal should be on out of the box, for [osName].
         *
         * **Off on Windows.** Reveal is driven by Compose `onPointerEvent(Enter/Exit)` on edge
         * strips, and Windows runs the browser in HARDWARE mode, where Chromium owns a foreign
         * native window that composites over the Compose scene rather than inside it. The OS
         * delivers pointer events to that window, so Compose never sees the pointer cross an edge
         * strip that sits under the browser. A Windows user in focus mode with a browser tab open
         * would sweep the edge and get nothing back - the bars simply would not return, with no
         * indication why.
         *
         * The setting itself still works, and anyone who wants it can turn it on. This only
         * changes what a fresh install starts with, and only where the mechanism cannot deliver.
         *
         * `startsWith`, not `contains`: `"darwin"` contains `"win"`, and handing macOS the
         * Windows branch here would silently disable a feature that works perfectly well there.
         * The same trap is pinned in `ResourceModeTest` and `JxBrowserRenderingModeTest`.
         */
        fun defaultAutoReveal(osName: String): Boolean = !osName.lowercase().startsWith("win")

        /** Fresh settings for [osName], used on first run and by "Reset to defaults". */
        fun defaultsFor(osName: String) = FocusModeSettings(autoRevealEnabled = defaultAutoReveal(osName))
    }
}
