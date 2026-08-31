package ai.rever.boss.app

import ai.rever.boss.window.WindowAppearanceSettings

/**
 * Which pieces of window chrome are on screen right now.
 *
 * @property topBar The action bar.
 * @property leftStrip The left icon rail.
 * @property rightStrip The right icon rail.
 * @property bottomBar The status bar.
 * @property titleRow The full-width row that holds the macOS traffic lights.
 */
internal data class ChromeVisibility(
    val topBar: Boolean,
    val leftStrip: Boolean,
    val rightStrip: Boolean,
    val bottomBar: Boolean,
    val titleRow: Boolean,
)

/**
 * Fold the three independent reasons a bar may be missing into one answer per bar.
 *
 * There are **three**, they are not interchangeable, and the whole value of this function is that
 * they stay three separate conjuncts written down once instead of being re-derived at five call
 * sites:
 *
 * - **The standing preference** (`WindowAppearanceSettings.showX`) - "I never want this bar". There
 *   is no hover that brings it back; the only way back is the View menu.
 * - **Focus mode's transient clearance** (`FocusModeRevealState.showX`) - the bar is cleared but
 *   hover-revealable, so this flag flips back and forth while the mode stays on.
 * - **Captured full screen** - the display belongs to the content, so this outranks both: a bar the
 *   user has switched on is still not drawn while a session runs.
 *
 * `docs/release-notes/v9.4.13.md:47` records what folding the first two into one readable predicate
 * cost: `topBarGone && !showTopBar` reads correctly and is wrong, because `showTopBar` is
 * `FocusModeEdgeRevealState.shown`, which is permanently true whenever focus mode is off. Sign Out
 * is raised only from the top bar and the quick-actions cluster, so the bar vanished and nothing
 * replaced it. Adding a third reason to that arithmetic in five inlined places is how it happens
 * again, which is why this is a function with a test rather than a conjunction in a scaffold.
 *
 * @param titleRowWanted whether the traffic-light rule has asked for the title row - see
 *   `macTrafficLightInset` and `TrafficLightInset.needsTitleRow`. Passed in rather than computed
 *   here because it depends on the update banner and the measured rail width, neither of which is
 *   a visibility question.
 */
internal fun chromeVisibility(
    appearance: WindowAppearanceSettings,
    reveal: FocusModeRevealState,
    capturedFullScreen: Boolean,
    titleRowWanted: Boolean,
): ChromeVisibility {
    if (capturedFullScreen) {
        return ChromeVisibility(
            topBar = false,
            leftStrip = false,
            rightStrip = false,
            bottomBar = false,
            titleRow = false,
        )
    }
    return ChromeVisibility(
        topBar = appearance.showTopBar && reveal.showTopBar,
        leftStrip = appearance.showLeftStrip && reveal.showLeftSidebar,
        rightStrip = appearance.showRightStrip && reveal.showRightSidebar,
        bottomBar = appearance.showBottomBar && reveal.showBottomBar,
        titleRow = titleRowWanted,
    )
}
