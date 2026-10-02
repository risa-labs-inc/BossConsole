package ai.rever.boss.plugin.browser

import ai.rever.boss.window.MainPanelFocusTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which embedded browser a WINDOW-scoped action should act on.
 *
 * The View menu's Zoom In / Zoom Out / Actual Size / Reload emit only a window id
 * ([ai.rever.boss.window.MenuActionsHandler]), and the host cannot answer "which browser" from the
 * tab tree: the live fluck tab is a DYNAMIC plugin's component, so the `activeTab is
 * FluckTabComponent` test those handlers used to perform was one the host could never satisfy -
 * the built-in `FluckTabComponent` stopped being instantiated when `registerFluck()` was disabled
 * in favour of the plugin, and the plugin's class is unrelated to it. All four menu items were
 * therefore no-ops on every platform.
 *
 * The one place that knows a browser surface is on screen, in which window and in which panel is
 * `BrowserHandleImpl.Content()`, so that is where entries come from. Registering a handle rather
 * than a tab component also keeps this independent of the plugin API: any browser-backed plugin
 * tab is served without the plugin implementing anything.
 *
 * `java.util.concurrent` in a `commonMain` file is deliberate: composeApp declares a single
 * `jvm("desktop")` target, and the same primitives are already used by `AWTKeyboardInterceptor`
 * and `BrowserFindController`. The concurrency is real - see [register].
 */
object ActiveBrowserRegistry {
    /**
     * One composed browser surface.
     *
     * Value-equality is load-bearing: it is what lets [unregister] remove an entry only when it is
     * still the current one.
     */
    internal data class Entry(
        val handleId: String,
        val windowId: String,
        val inMainPanel: Boolean,
        val panelActive: Boolean,
        val sequence: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val handles = ConcurrentHashMap<String, BrowserHandle>()

    /**
     * Handles whose web page holds Chromium's keyboard focus, from JxBrowser's `FocusGained` /
     * `FocusLost` browser events.
     *
     * Separate from [entries] because focus arrives on a JxBrowser thread and on its own schedule:
     * a page can gain focus before its surface registers, and keeps it across a re-registration.
     */
    private val focusedPages = ConcurrentHashMap.newKeySet<String>()
    private val sequencer = AtomicLong(0)

    /**
     * Guards the mutate-then-recompute pair behind [publishWindows].
     *
     * Its own object rather than the map: [entries] is a ConcurrentHashMap read lock-free by
     * [activeIn], so locking on it would put two different jobs under one name.
     */
    private val publishLock = Any()

    private val _windowsWithActiveBrowser = MutableStateFlow<Set<String>>(emptySet())

    private val _activeHandleIdByWindow = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Windows where a browser is the surface the user is actually in.
     *
     * The browser menu items (Back, Forward, Developer Tools, Actual Size, Zoom In, Zoom Out, Reload, Print)
     * must grey out where the chord
     * should not act, and not merely no-op: a Compose MenuBar accelerator fires from anywhere in
     * the window regardless of the binding's ShortcutContext, so an always-enabled item silently
     * swallows its chord for every other tab type. Cmd+[ and Cmd+] are outdent/indent in an
     * editor, which is precisely what that would break.
     *
     * "Has a browser anywhere" is the wrong test for that, and was the first version of this:
     * entries arrive from every composed surface, including a sidebar slot and the other half of
     * a split, so a browser on the left of a split left the items enabled while the user typed in
     * an editor on the right. [activeBrowserWindows] filters on the same pair of flags
     * [selectActiveHandleId] ranks on, and liveness is the same `isValid` check [activeIn]
     * applies, so the menu cannot offer an action that then finds nothing to act on.
     *
     * Still WINDOW-scoped, so it answers "the main panel's visible surface is a browser", not
     * "the keyboard focus is in a browser" - with a browser as the active main-panel tab and
     * focus in a sidebar editor, Cmd+[ / Cmd+] are still taken by the accelerator. The keyboard
     * question has its own answer, [keyboardOwnerIn], which the AWT keymap uses. The menu does
     * not use it, deliberately: when the browser does hold the keyboard the AWT keymap claims
     * the chord before the menu sees it, so the accelerator only acts where that focus signal
     * said "not the browser", and that is the fallback to keep until the page-focus half of
     * [keyboardOwnerIn] has been confirmed on every platform and rendering mode.
     *
     * Recomputed on register and unregister, and via [republish] wherever `handle.isValid` can
     * flip without either - `BrowserHandleImpl` latching `connectionDead` on a transport failure
     * is the one such path, and it is the dangerous direction: a stale entry keeps the menu
     * items ENABLED for a window whose browser is gone, so they swallow Cmd+[ from an editor
     * and then find nothing to act on.
     */
    val windowsWithActiveBrowser: StateFlow<Set<String>> = _windowsWithActiveBrowser.asStateFlow()

    /**
     * The handle a window-scoped action acts on, per window: [windowId] -> handleId of the
     * entry [selectActiveHandleId] ranks first, under the SAME filter [activeBrowserWindows]
     * applies (`inMainPanel && panelActive` and live).
     *
     * The window set above only says a window HAS an active browser, and it cannot tell two
     * browser tabs in one window apart: switching tabs changes the handle while the set
     * stays equal, so a StateFlow on the set never emits. UI that renders PER-HANDLE state
     * (the zoom badge in the top bar) keys on this map instead, and it is published from the
     * same snapshot so the two cannot drift.
     */
    val activeHandleIdByWindow: StateFlow<Map<String, String>> = _activeHandleIdByWindow.asStateFlow()

    /**
     * Recompute [windowsWithActiveBrowser] from [entries].
     *
     * Snapshot and assignment go under one lock, shared with every mutator. Without it two
     * interleaving registrations can have the later assignment carry the earlier snapshot, and
     * nothing recomputes until the NEXT register or unregister - so the failure sticks: the
     * browser menu items stay enabled for a window with no browser and swallow Cmd+[ from an
     * editor, which is the whole thing this flow exists to prevent. [activeIn] does not have
     * the problem because it recomputes per call.
     */
    private fun publishWindows() =
        synchronized(publishLock) {
            val live = entries.values.toList()
            _windowsWithActiveBrowser.value = activeBrowserWindows(live, ::isLive)
            _activeHandleIdByWindow.value = activeHandleIds(live, ::isLive)
        }

    /**
     * The one definition of "this registration still has a browser behind it", so the menu's
     * enabled flag and [activeIn]'s dispatch target cannot drift: [activeIn] filtered on
     * `isValid` while the first version of [publishWindows] did not, which left the menu offering
     * an action that then found nothing.
     */
    private fun isLive(handleId: String): Boolean = handles[handleId]?.isValid == true

    /**
     * Record that [handle]'s surface is composed in [windowId].
     *
     * Written from the Compose UI thread and read from the menu flow collectors, hence the
     * concurrent maps. [sequence] comes from an atomic counter so two panels re-registering within
     * the same frame still get distinct, increasing ranks - though [selectActiveHandleId]
     * deliberately does not depend on which of the two wins that race.
     *
     * @return a token to hand back to [unregister]; see the cross-window note there.
     */
    fun register(
        handle: BrowserHandle,
        windowId: String,
        inMainPanel: Boolean,
        panelActive: Boolean,
    ): Any {
        val entry =
            Entry(
                handleId = handle.id,
                windowId = windowId,
                inMainPanel = inMainPanel,
                panelActive = panelActive,
                sequence = sequencer.incrementAndGet(),
            )
        synchronized(publishLock) {
            handles[handle.id] = handle
            entries[handle.id] = entry
        }
        publishWindows()
        return entry
    }

    /**
     * Remove [handleId]'s registration, but only if [token] is still the current one.
     *
     * A tab moving between windows builds one composition and tears down the other in an order
     * this registry does not control - `BrowserHandleImpl` documents the same hazard for its
     * visibility counter. Both compositions carry the SAME handle id, so an unconditional remove
     * from the outgoing one would delete the incoming one's entry and leave the menu with nothing
     * to act on. The value-equality remove is the same trick `BrowserWindowOwnershipRegistry` uses.
     */
    fun unregister(
        handleId: String,
        token: Any?,
    ) {
        if (token !is Entry) return
        val removed =
            synchronized(publishLock) {
                entries.remove(handleId, token).also { if (it) handles.remove(handleId) }
            }
        if (removed) publishWindows()
    }

    /** Unconditional removal, for handle disposal - the handle is gone, so no successor exists. */
    fun unregister(handleId: String) {
        synchronized(publishLock) {
            entries.remove(handleId)
            handles.remove(handleId)
        }
        focusedPages.remove(handleId)
        publishWindows()
    }

    /**
     * Recompute [windowsWithActiveBrowser] without a registration change.
     *
     * [publishWindows] runs on register and unregister, which is enough only while `isValid`
     * cannot flip on its own. It can: `BrowserHandleImpl` latches `connectionDead` on a
     * transport failure and nothing unregisters at that point, so the window would keep its
     * browser menu items enabled with nothing behind them. That call site invokes this.
     */
    fun republish() = publishWindows()

    /** The browser a window-scoped action should act on in [windowId], or null if there is none. */
    fun activeIn(windowId: String): BrowserHandle? {
        val liveEntries = entries.values.filter { isLive(it.handleId) }
        val handleId = selectActiveHandleId(liveEntries, windowId) ?: return null
        return handles[handleId]?.takeIf { it.isValid }
    }

    /**
     * The handle registered under [handleId], or null if unknown.
     *
     * Lets a UI that already knows the ranked active handle id from
     * [activeHandleIdByWindow] read or act on THAT handle instead of re-asking
     * [activeIn] and meeting a possibly newer ranking between the two reads.
     * Callers that care about liveness still filter on [BrowserHandle.isValid].
     */
    fun handleById(handleId: String): BrowserHandle? = handles[handleId]

    /**
     * Record whether [handleId]'s web page holds the keyboard. Called from the handle's
     * `FocusGained` / `FocusLost` subscriptions, and with false when its renderer dies.
     */
    fun setPageFocused(
        handleId: String,
        focused: Boolean,
    ) {
        if (focused) focusedPages.add(handleId) else focusedPages.remove(handleId)
    }

    /**
     * Whether a browser holds the keyboard in [windowId], and through which part of it.
     *
     * THE predicate for "is the browser focused" - the AWT keymap's BROWSER context is decided
     * here and nowhere else. See [resolveBrowserKeyboardOwner] for the rule and
     * [MainPanelFocusTracker] for the Compose half.
     *
     * Not a question the menu's enabled flags ask; see [windowsWithActiveBrowser] for why.
     *
     * [inWindowItself] is false when the keyboard is in a window OWNED by [windowId] (a dialog
     * window, a Swing dialog) rather than in it. Compose treats focus moving to another window as
     * a temporary loss and reports no focus change, so [MainPanelFocusTracker] still says the main
     * panel has focus; trusting it there would hand the dialog's Cmd+L, Cmd+R or Cmd+[ to the
     * browser behind it. The page half is guarded too: a page should lose focus when its window
     * deactivates, but that is JxBrowser's behaviour to keep, not ours, and with the keyboard in
     * another window no browser in this one can be what it is typing into.
     */
    fun keyboardOwnerIn(
        windowId: String,
        inWindowItself: Boolean,
    ): BrowserKeyboardOwner {
        if (!inWindowItself) return BrowserKeyboardOwner.NONE
        // Runs on the EDT for every modifier chord, so the usual case - no page focused anywhere -
        // allocates nothing. Liveness is isLive, the same test the active handle passes: a handle
        // whose transport died sends no FocusLost, and must not keep vetoing the window's browser.
        val focusedHere =
            if (focusedPages.isEmpty()) {
                emptyList()
            } else {
                entries.values
                    .filter { it.windowId == windowId && it.handleId in focusedPages && isLive(it.handleId) }
                    .map { it.handleId }
            }
        return resolveBrowserKeyboardOwner(
            activeHandleId = _activeHandleIdByWindow.value[windowId],
            focusedPageHandleIds = focusedHere,
            mainPanelHasComposeFocus = MainPanelFocusTracker.hasFocus(windowId),
        )
    }
}

/**
 * Where the keyboard is, relative to a window's browser. See [resolveBrowserKeyboardOwner].
 */
enum class BrowserKeyboardOwner {
    /** No browser holds the keyboard in this window. */
    NONE,

    /** The active browser's web page holds Chromium's keyboard focus. */
    PAGE,

    /** Compose focus is in the main panel whose visible tab is the active browser (its chrome). */
    CHROME,
}

/**
 * Which part of a window's browser, if any, holds the keyboard.
 *
 * Pure, for the same reason as [selectActiveHandleId]. The inputs:
 *  - [activeHandleId] - the window's active browser under [activeHandleIds]' filter
 *    (`inMainPanel && panelActive` and live), or null. Both answers other than NONE need it, so
 *    a BROWSER-context action always has a target, and a sidebar browser or the background half
 *    of a split never takes the keyboard's shortcuts.
 *  - [focusedPageHandleIds] - browsers in this window whose page has Chromium focus.
 *  - [mainPanelHasComposeFocus] - [MainPanelFocusTracker.hasFocus] for this window.
 *
 * Why two focus signals, not one. Under HARDWARE_ACCELERATED (the default) the page is a
 * native Chromium view: clicking it moves no Compose focus and delivers no Compose pointer
 * event, so Compose focus alone would keep reporting wherever it was before, a sidebar editor
 * included. Chromium's own focus events are the truth for the page. They say nothing about the
 * browser's Compose chrome (address bar, find bar), which is what the Compose signal covers.
 *
 * A focused page wins over Compose focus, which can be stale for exactly the reason above. A
 * focused page that is NOT the active browser (a sidebar slot, the other half of a split)
 * answers NONE: the keys are going to a browser this window's shortcuts would not act on.
 *
 * | active browser | a page focused  | Compose focus in main panel | answer |
 * |----------------|-----------------|-----------------------------|--------|
 * | none           | any             | any                         | NONE   |
 * | A              | A               | any                         | PAGE   |
 * | A              | B only          | any                         | NONE   |
 * | A              | none            | yes                         | CHROME |
 * | A              | none            | no (sidebar, dialog, ...)   | NONE   |
 */
internal fun resolveBrowserKeyboardOwner(
    activeHandleId: String?,
    focusedPageHandleIds: Collection<String>,
    mainPanelHasComposeFocus: Boolean,
): BrowserKeyboardOwner =
    when {
        activeHandleId == null -> BrowserKeyboardOwner.NONE
        activeHandleId in focusedPageHandleIds -> BrowserKeyboardOwner.PAGE
        focusedPageHandleIds.isNotEmpty() -> BrowserKeyboardOwner.NONE
        mainPanelHasComposeFocus -> BrowserKeyboardOwner.CHROME
        else -> BrowserKeyboardOwner.NONE
    }

/**
 * Which windows have a browser as the surface the user is actually in.
 *
 * Extracted as a pure function for the same reason as [selectActiveHandleId]: so the rule is
 * unit-testable without a JxBrowser `Browser`, which `BrowserHandle` would otherwise require a
 * ~55-method double to stand in for.
 *
 * Deliberately STRICTER than [selectActiveHandleId], which ranks and always returns a candidate
 * if one exists. This filters: `inMainPanel && panelActive` means the browser is the visible
 * surface of the panel the user is in, so a sidebar-slot browser or the background half of a
 * split answers false. The menu items this gates fire their accelerator window-wide regardless
 * of ShortcutContext, so "a browser exists somewhere" would swallow Cmd+[ and Cmd+] from an
 * editor. The menu gate is deliberately stricter than the dispatch target: a sidebar-slot
 * browser or the background half of a split can no longer be zoomed or reloaded from the View
 * menu - accepted, because the broader "a browser exists somewhere" gate would swallow Ctrl+R
 * (and Cmd+[/Cmd+]) from a terminal or editor tab.
 */
internal fun activeBrowserWindows(
    candidates: Collection<ActiveBrowserRegistry.Entry>,
    isLive: (String) -> Boolean,
): Set<String> =
    candidates
        .filter { it.inMainPanel && it.panelActive && isLive(it.handleId) }
        .map { it.windowId }
        .toSet()

/**
 * The window-scoped dispatch target of every window that has one: [windowId] -> handleId of
 * the entry [selectActiveHandleId] ranks first, under the SAME filter [activeBrowserWindows]
 * applies (`inMainPanel && panelActive` and live).
 *
 * Its keys are therefore exactly [activeBrowserWindows]' set, which keeps the badge's
 * visibility gate and its dispatch target on one definition; the value adds what the set
 * cannot: WHICH handle is active, so a per-handle read is possible.
 */
internal fun activeHandleIds(
    candidates: Collection<ActiveBrowserRegistry.Entry>,
    isLive: (String) -> Boolean,
): Map<String, String> =
    candidates
        .filter { it.inMainPanel && it.panelActive && isLive(it.handleId) }
        .groupBy { it.windowId }
        .mapNotNull { (windowId, windowEntries) ->
            selectActiveHandleId(windowEntries, windowId)?.let { windowId to it }
        }.toMap()

/**
 * Of the live browser surfaces composed in [windowId], which one owns a window-scoped action.
 *
 * Extracted as a pure function so the tie-break is unit-testable without a JxBrowser `Browser`.
 *
 * Ranked, most significant first:
 *  1. `inMainPanel` - `LocalIsPanelActive` DEFAULTS TO TRUE, so a browser rendered in a sidebar
 *     slot reports itself active too; only `LocalInMainWindowPanel` separates the two. This is the
 *     same reasoning `BossMainWindowPanel` gives where it provides both locals.
 *  2. `panelActive` - within the main content area, the split panel the user is in wins.
 *  3. `sequence` - otherwise the most recently shown surface wins.
 */
internal fun selectActiveHandleId(
    candidates: Collection<ActiveBrowserRegistry.Entry>,
    windowId: String,
): String? =
    candidates
        .filter { it.windowId == windowId }
        .maxWithOrNull(
            compareBy<ActiveBrowserRegistry.Entry>(
                { it.inMainPanel },
                { it.panelActive },
                { it.sequence },
            ),
        )?.handleId
