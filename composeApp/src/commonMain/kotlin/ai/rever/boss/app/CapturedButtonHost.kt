package ai.rever.boss.app

/**
 * Which piece of chrome draws the captured-full-screen button.
 *
 * The button has to sit beside the macOS traffic lights, and *which* chrome the lights are drawn
 * over changes with what the user has switched on - `macTrafficLightInset` already answers that for
 * the clearance. This answers the same question for the button, and exists because the first
 * version did not ask it: the button was handed to the title row when that was drawn and to the top
 * bar otherwise, which renders it **nowhere** in the configuration where neither is on and the
 * clearance is carried by the left-hand columns. That is not a rare setup - it is what you get by
 * switching the top bar off with a sidebar open.
 */
internal enum class CapturedButtonHost {
    /** The full-width title row, which is where the lights are when it is drawn. */
    TITLE_ROW,

    /** The top bar's leading edge, after the traffic-light indent it already carries. */
    TOP_BAR,

    /**
     * Nothing suitable is on screen, so it is drawn as an overlay in the clearance band itself.
     *
     * The band is real estate that already exists and is already empty - the columns beneath it are
     * inset by `TRAFFIC_LIGHT_HEIGHT` precisely so the buttons have somewhere to be - so this
     * overlaps nothing. It is the fallback rather than the default because a button inside a bar
     * participates in that bar's layout, hit testing and context menu, and an overlay does not.
     */
    OVERLAY,

    /** Not drawn: either a session is running, or this platform has no traffic lights. */
    NONE,
}

/**
 * Where the captured-full-screen button goes.
 *
 * @param isMacOs the button is only ever drawn beside real traffic lights, so it is macOS-only in
 *   the title row / overlay sense. Windows and Linux have no cluster to join and get it at the
 *   start of the top bar instead, which is handled by [topBarDrawn] with a zero indent.
 * @param captured while a session runs every bar is gone by design and the exits are the two
 *   shortcuts, the hold and the HUD. Drawing a lone button over the content would undo the point
 *   of the mode.
 */
internal fun capturedButtonHost(
    titleRowDrawn: Boolean,
    topBarDrawn: Boolean,
    captured: Boolean,
    isMacOs: Boolean,
    /**
     * `WindowAppearanceSettings.capturedFullScreenEnabled`, which is **off by default**.
     *
     * Asked before everything else, including [captured]: a window cannot be capturing while the
     * feature is off, because switching it off ends the session. Answering NONE first is what makes
     * the button absent rather than present-and-inert for the default install.
     */
    enabled: Boolean,
): CapturedButtonHost =
    when {
        !enabled -> CapturedButtonHost.NONE

        captured -> CapturedButtonHost.NONE

        titleRowDrawn -> CapturedButtonHost.TITLE_ROW

        topBarDrawn -> CapturedButtonHost.TOP_BAR

        // Only macOS has a clearance band to draw into. Off it, with no title row and no top bar,
        // there is no honest place for the button and the View menu carries the feature instead.
        isMacOs -> CapturedButtonHost.OVERLAY

        else -> CapturedButtonHost.NONE
    }
