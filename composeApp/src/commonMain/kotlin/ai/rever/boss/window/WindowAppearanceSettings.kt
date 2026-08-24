package ai.rever.boss.window

import kotlinx.serialization.Serializable

/**
 * Settings for window appearance customization
 */
@Serializable
data class WindowAppearanceSettings(
    /**
     * Whether to show the Boss Console title bar
     * Default: true on macOS, false on Linux/Windows
     */
    val showTitleBar: Boolean = true,
    /**
     * Whether the action bar at the top of the window is on screen. Its height comes from
     * `ChromeDimens.topBarHeight`, so quoting a dp figure here would go stale.
     *
     * This and the three below are a *permanent* preference, and deliberately separate from focus
     * mode's per-edge `hide*` flags. Focus mode is a transient posture with hover-reveal strips to
     * get a bar back; these say "I never want this bar", and the only way back is the View menu.
     * The scaffold requires both to agree, so a bar shows when this is true and focus mode is not
     * currently clearing it.
     *
     * The other three default to `true` on every platform, which is what made them safe to add to
     * an existing settings file: the manager decodes with `ignoreUnknownKeys`, so an absent key
     * reads back as "shown" and nobody's chrome disappeared on upgrade.
     *
     * THIS one no longer does. The top bar is off by default now, so a file written before the
     * key existed would decode as hidden and lose a bar its owner never asked to lose. That is
     * what [settingsVersion] and [WindowAppearanceMigrations] are for: the migration decides
     * deliberately, once, instead of a decode default deciding silently.
     */
    val showTopBar: Boolean = false,
    /** Whether the status bar at the bottom of the window is on screen. See [showTopBar]. */
    val showBottomBar: Boolean = true,
    /** Whether the left icon strip is on screen. See [showTopBar]. */
    val showLeftStrip: Boolean = true,
    /** Whether the right icon strip is on screen. See [showTopBar]. */
    val showRightStrip: Boolean = true,
    /**
     * How tabs in the main (top) tab bar are sized.
     * Default: SHRINK_TO_FIT (Safari behaviour)
     *
     * Applies to [TabBarPosition.TOP] only. A vertical tab is the bar's width by
     * definition, so there is no width to budget and nothing to shrink.
     */
    val tabWidthMode: TabWidthMode = TabWidthMode.SHRINK_TO_FIT,
    /**
     * Which edge of each main window panel carries its tab bar.
     *
     * Global rather than per-panel, like every other field here, so a split's panels all agree.
     *
     * The two positions differ in more than the edge. TOP renders one horizontal strip per
     * panel, as it always has. LEFT renders ONE vertical bar for the whole window, with each
     * pane spliced into it as a group - a horizontal strip has no room for groups, which is why
     * only one of the two was changed.
     *
     * Default LEFT, with [WindowAppearanceMigrations] moving installs already sitting on the
     * TOP-and-visible-top-bar combination this shipped with. Every other saved choice is left
     * alone.
     */
    val tabBarPosition: TabBarPosition = TabBarPosition.LEFT,
    /**
     * Schema version of this file, used to apply one-time migrations to installs that already
     * have a settings file written by an older build.
     *
     * Deliberately 0, not [CURRENT_SETTINGS_VERSION]: a missing key decodes to the default, and
     * every file written before this field existed is missing it. The manager stamps the current
     * version when it writes.
     */
    val settingsVersion: Int = 0,
    /**
     * Width in dp of the vertical (left) tab bar, clamped to [TabBarVerticalWidthRange].
     * Ignored when [tabBarPosition] is TOP.
     *
     * A Float rather than a Dp because Dp is not serializable and the settings slider works
     * in Float anyway; the single conversion happens at the one point of use.
     */
    val tabBarVerticalWidth: Float = 200f,
    /**
     * Vertical bar collapsed to its slim icon rail, toggled by the bar's own chevron.
     * Ignored when [tabBarPosition] is TOP, and overridden while a panel is narrow enough
     * to force the rail (see `TAB_BAR_AUTO_COLLAPSE_WIDTH`).
     *
     * Persisted because the chevron is a deliberate posture, not a transient one: someone who
     * collapsed the bar to reclaim width wants it collapsed again next launch.
     */
    val tabBarCollapsed: Boolean = false,
    /**
     * Reveal the full vertical bar as an overlay drawer while the pointer rests on the
     * collapsed rail - whether the rail was forced by a narrow panel or collapsed with the
     * chevron. The panel content is never resized; the bar floats over it and retracts when
     * the pointer leaves. Off = the chevron is the only way back.
     *
     * Ignored when [tabBarPosition] is TOP.
     */
    val tabBarHoverExpand: Boolean = true,
    /**
     * Show each pane's own tabs as a narrow favicon strip across the top of that pane.
     *
     * Only ever drawn in LEFT position, and only once the window is SPLIT - both conditions
     * survive this setting rather than being replaced by it. In TOP position each pane already
     * draws its own full strip; with one pane the vertical bar lists every tab with its name, so
     * the strip would be the same information twice. This subtracts from those cases, it does not
     * add to them.
     *
     * It exists because the window bar collapses panes the user is not working in, so without it
     * switching a background pane's tab means going to the sidebar, opening that pane's group and
     * reading names. Off for anyone who would rather have the pixels and make that trip.
     *
     * Default on: that is what the split window has done since the strip existed, and turning it
     * off on upgrade would take away a control people are already using.
     */
    val showPaneTabStrip: Boolean = true,
    /**
     * Whether right-click menus are the operating system's own rather than BOSS-drawn.
     *
     * On macOS this renders a real NSMenu: system appearance and metrics, native keyboard
     * navigation and accessibility, and correct behaviour over the browser's native surface,
     * which a Compose popup is painted behind. Native menus cannot be themed, so turning this
     * off restores the BOSS-styled menus.
     *
     * Currently macOS-only and ignored elsewhere - see `shouldUseNativeMenus` for why Windows
     * and Linux stay on the drawn menus.
     */
    val useNativeContextMenus: Boolean = true,
) {
    companion object {
        /** Bump when a step is added to [WindowAppearanceMigrations.migrate]. */
        const val CURRENT_SETTINGS_VERSION = 1
    }
}

/**
 * One-time migrations for [WindowAppearanceSettings] files written by older builds.
 *
 * Kept in commonMain and pure, so it is directly testable - the same shape, and for the same
 * reason, as `WorkspaceSettingsMigrations`.
 */
object WindowAppearanceMigrations {
    /**
     * Returns the settings to use, or null when the file is already current.
     *
     * **0 -> 1: the left tab bar and the hidden top bar become the defaults.** A file sitting on
     * what every build before this one shipped - top bar shown, tabs across the top - is moved to
     * them. Any other combination is left exactly as it is.
     *
     * It shares the limitation `WorkspaceSettingsMigrations` documents: a file records *what* the
     * value is, never *who* set it, so an install sitting on the old shipped default cannot be
     * told apart from someone who chose that same value deliberately. Both are moved once, on
     * purpose, and everything else is preserved. The step runs at most once, because the migrated
     * file records the new version.
     *
     * Changing the DEFAULT alone would not have done this. The manager writes the whole object on
     * every save, so an existing file names both values explicitly and would keep them for ever -
     * the new defaults would reach new installs only, which is not what "by default" means to
     * somebody who already has BOSS open.
     *
     * One thing this reasons over that it cannot actually see: it tests decoded VALUES, not what
     * the file said. A key that is absent decodes to the field's default, so a file written
     * before [WindowAppearanceSettings.showTopBar] existed is indistinguishable here from one
     * where somebody hid the bar deliberately. That is harmless for this step, because both
     * belong on the new defaults anyway - but a future step that needs "absent" to differ from
     * "false" cannot get it from this signature, and will need the field to be nullable.
     */
    fun migrate(loaded: WindowAppearanceSettings): WindowAppearanceSettings? {
        if (loaded.settingsVersion >= WindowAppearanceSettings.CURRENT_SETTINGS_VERSION) return null

        val onOldShippedDefaults = loaded.showTopBar && loaded.tabBarPosition == TabBarPosition.TOP
        return loaded.copy(
            showTopBar = if (onOldShippedDefaults) false else loaded.showTopBar,
            tabBarPosition = if (onOldShippedDefaults) TabBarPosition.LEFT else loaded.tabBarPosition,
            settingsVersion = WindowAppearanceSettings.CURRENT_SETTINGS_VERSION,
        )
    }
}

/**
 * Sizing behaviour for top tabs in the main tab bar.
 */
@Serializable
enum class TabWidthMode {
    /**
     * Tabs shrink uniformly to fit the available bar width (Safari behaviour).
     * The row only scrolls once each tab has hit its favicon-sized floor.
     */
    SHRINK_TO_FIT,

    /**
     * Tabs take their content-driven width (clamped to 180–450 dp) and the
     * row scrolls as soon as they overflow the bar.
     */
    FIXED,
}

/**
 * Bounds the vertical tab bar's width setting is clamped to.
 *
 * Lives beside the setting rather than beside the bar that renders it, so the settings slider
 * and the bar cannot disagree about what a valid width is. The floor is roughly two favicons
 * plus a short title; the ceiling is where a bar stops being chrome and starts being a panel.
 */
val TabBarVerticalWidthRange: ClosedFloatingPointRange<Float> = 120f..320f

/**
 * Which edge of a main window panel its tab bar sits on.
 *
 * The two are not symmetric and are not meant to be. TOP is a fixed-height strip whose tabs
 * share a width budget ([TabWidthMode]); LEFT is a fixed-width column whose tabs are uniform
 * rows and simply scroll, and which additionally has a collapsed icon rail, a hover-reveal
 * drawer and an auto-collapse below a narrow-panel threshold. Ported from BossTerm's
 * `TabBarOrientation`.
 */
@Serializable
enum class TabBarPosition {
    /** A horizontal strip above the panel content. The layout the app has always had. */
    TOP,

    /** A vertical column to the left of the panel content. */
    LEFT,
}

/**
 * How this position is worded to the user.
 *
 * On the enum rather than private to the Settings screen, because three surfaces now name it -
 * Settings, the View menu, and the tab bar's own right-click submenu - and three copies of a
 * label is three chances for them to drift apart.
 */
val TabBarPosition.displayName: String
    get() =
        when (this) {
            TabBarPosition.TOP -> "Top"
            TabBarPosition.LEFT -> "Left"
        }
