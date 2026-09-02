package ai.rever.boss.window

import ai.rever.boss.layout.ChromeDensity
import ai.rever.boss.utils.SystemUtils
import kotlinx.serialization.Serializable

/**
 * Settings for window appearance customization
 */
@Serializable
data class WindowAppearanceSettings(
    /**
     * Whether to show the Boss Console title bar.
     *
     * **On by default on macOS, off elsewhere.** On Windows and Linux it is a plain bar above the
     * content and the OS draws its own frame. On macOS it is something else: the window sets
     * `apple.awt.fullWindowContent`, so the close / minimise / zoom buttons are drawn OVER the
     * content, and this row is what holds them.
     *
     * It was briefly off there too, with the clearance moved onto whichever column is leftmost
     * (see `macTrafficLightInset`). That works only while a column is wide enough to hold a 78dp
     * box: a collapsed tab bar is one 40dp rail, and the fallback for everything narrower was to
     * draw this row anyway - so "off" was not a state a macOS window could reliably be in.
     *
     * The class default stays false, and macOS is switched on by `getDefaultSettings` for a fresh
     * install and by the 1 -> 2 migration for an existing one. Flipping the class default instead
     * would turn the row on for Windows and Linux too, whose files do not mention it either.
     */
    val showTitleBar: Boolean = false,
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
     * **The top bar and both icon strips are off by default; the status bar stays on.** A window
     * opens as its content, the vertical tab bar, and the status line along the bottom.
     *
     * Hiding the other three is only sane because there is now somewhere for what they carried to
     * go: the tab bar's foot holds Sign Out, Settings, Tools and Search (see
     * `focusQuickActionsPlacement`), and the Tools launcher reaches every plugin panel the strips
     * used to hold (see `toolLauncherPlacement`). Before those existed, hiding both strips made
     * plugins unreachable and hiding the top bar took Sign Out with it.
     *
     * The status bar is the exception because nothing replaces it. It is the only always-on
     * readout of what the app is doing - the current URL, memory, transient status messages - and
     * none of that is reachable from a menu or a launcher. It also costs one 30dp row, where a
     * strip costs 41dp of width and the top bar a whole row of chrome.
     *
     * The **decode default is what moves an existing install**, and that works here because the
     * settings file is written without defaults: a value equal to the default is never stored, so
     * a file that does not mention a bar picks the new default up. [WindowAppearanceMigrations]
     * exists for the case a decode default cannot express, not for this one.
     *
     * Known limit of that, stated because it is invisible: a user who deliberately turned a strip
     * *on* while `true` was the default has nothing written for it either, so they are
     * indistinguishable from someone who never touched it and their strip goes away too. No
     * migration can tell those apart - the information was never recorded.
     */
    val showTopBar: Boolean = false,
    /** Whether the status bar at the bottom of the window is on screen. See [showTopBar]. */
    val showBottomBar: Boolean = true,
    /** Whether the left icon strip is on screen. See [showTopBar]. */
    val showLeftStrip: Boolean = false,
    /** Whether the right icon strip is on screen. See [showTopBar]. */
    val showRightStrip: Boolean = false,
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
     * Only ever drawn in LEFT position, which survives this setting rather than being replaced
     * by it: in TOP position each pane already draws its own full strip. This subtracts from that
     * case, it does not add to it.
     *
     * It exists because the vertical bar hides tab names in two ways - it collapses panes the
     * user is not working in, and it collapses itself to the rail when a panel is narrow or the
     * chevron says so. Either way the strip is where a pane's own tabs stay reachable. Off for
     * anyone who would rather have the pixels.
     *
     * Default on: that is what the split window has done since the strip existed, and turning it
     * off on upgrade would take away a control people are already using.
     */
    val showPaneTabStrip: Boolean = true,
    /**
     * Restrict [showPaneTabStrip] to windows that actually have a split.
     *
     * The strip was split-only when it was written, on the reasoning that a single pane's tabs
     * are already listed BY NAME in the sidebar, so a row of favicons says the same thing with
     * less in it. That reasoning holds only while the sidebar is expanded - it collapses to the
     * rail on its own when a panel is narrow, the chevron collapses it deliberately, and the top
     * bar is hidden by default now. A one-pane window could therefore reach a state with no tab
     * titles anywhere on screen, which is the state the strip exists to prevent.
     *
     * So the pane count is a preference rather than a rule, and it is OFF by default: the strip
     * shows in a one-pane window too, because that is the window most likely to have no tab titles
     * anywhere else on screen. Anyone who wants the old split-only behaviour switches it on.
     *
     * This DOES change what an existing install does, and reaches one without a migration step: a
     * file written before this field existed does not name it, so it decodes to whatever the
     * default is now. That is the mechanism `WindowAppearanceEncodeDefaultsTest` pins - and its
     * limit applies here too, since a file that omits the field cannot say whether that is a
     * preference or simply an older build.
     */
    val paneTabStripOnlyWhenSplit: Boolean = false,
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
    /**
     * How tightly [showTopBar] and its neighbours are drawn - see [ChromeDensity].
     *
     * Orthogonal to *which* bars are on screen (the `show*` flags above): this answers "how much
     * room may the bars that are on screen take", not "which ones are". `ai.rever.boss.layout.
     * BossChrome` resolves it via `ChromeDimens.of(density)` and the host provides the result as
     * `LocalChromeDimens` at the app root, so every bar file reads a height from that one place
     * rather than carrying its own literal.
     *
     * Defaults to [ChromeDensity.COMFORTABLE] - today's shipped sizes - so an existing install's
     * file, which does not mention this field, decodes to exactly the chrome it already had. A
     * fresh install's default is decided by screen size instead, in
     * `WindowAppearanceSettingsManager.getDefaultSettings` - see issue #239: a 13" laptop's screen
     * is the case this field exists for, and an install-time default is the only way to make it
     * apply without the user finding a switch first.
     */
    val density: ChromeDensity = ChromeDensity.COMFORTABLE,
) {
    companion object {
        /** Bump when a step is added to [WindowAppearanceMigrations.migrate]. */
        const val CURRENT_SETTINGS_VERSION = 2
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
     * Changing the DEFAULT alone would not reliably have done this, which is why this step exists
     * even though a later one relies on exactly that. The manager omits default-valued fields when
     * it writes (`encodeDefaults` is false), so a changed default DOES reach a file that never
     * mentioned the field - but this pair is not in that position: an install on the old shipped
     * defaults had `showTopBar = true` and `tabBarPosition = TOP`, both differing from the class
     * defaults of the day and therefore both written out explicitly. A default flip would have
     * left those files untouched for ever.
     *
     * The flags flipped later - the title row and the two icon strips - are the other case: their
     * old shipped values equalled the class defaults, so they were never written, and changing the
     * default is enough. That rests entirely on `encodeDefaults` staying false, which
     * `WindowAppearanceEncodeDefaultsTest` pins. It also inherits the same blindness: someone who
     * deliberately chose the value that used to be the default is indistinguishable from someone
     * who never touched it, and moves with everyone else.
     *
     * **1 -> 2: the title bar comes back on macOS.** Turning it off there left the window with no
     * reliable place for the traffic lights: the clearance moved onto the leftmost column, and a
     * collapsed tab bar is a 40dp rail against a 78dp light box, so narrow configurations fell back
     * to drawing the row regardless. A row that appears and disappears with the window's width is
     * worse than one that is simply on. Windows and Linux are untouched - the row is an ordinary
     * bar there and the OS draws its own frame.
     *
     * This step, like 0 -> 1, cannot tell somebody who deliberately switched the row off from
     * somebody who never touched it: `encodeDefaults` is false, so the value that equals the class
     * default is never written. Both move.
     *
     * One thing this reasons over that it cannot actually see: it tests decoded VALUES, not what
     * the file said. A key that is absent decodes to the field's default, so a file written
     * before [WindowAppearanceSettings.showTopBar] existed is indistinguishable here from one
     * where somebody hid the bar deliberately. That is harmless for this step, because both
     * belong on the new defaults anyway - but a future step that needs "absent" to differ from
     * "false" cannot get it from this signature, and will need the field to be nullable.
     */
    fun migrate(
        loaded: WindowAppearanceSettings,
        isMacOs: Boolean = SystemUtils.isMacOS,
    ): WindowAppearanceSettings? {
        if (loaded.settingsVersion >= WindowAppearanceSettings.CURRENT_SETTINGS_VERSION) return null

        val onOldShippedDefaults =
            loaded.settingsVersion < 1 && loaded.showTopBar && loaded.tabBarPosition == TabBarPosition.TOP
        return loaded.copy(
            showTopBar = if (onOldShippedDefaults) false else loaded.showTopBar,
            tabBarPosition = if (onOldShippedDefaults) TabBarPosition.LEFT else loaded.tabBarPosition,
            // 1 -> 2, macOS only. A default flip cannot do this one: the class default has to stay
            // false for Windows and Linux, whose files do not mention the field either.
            showTitleBar = if (isMacOs) true else loaded.showTitleBar,
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
