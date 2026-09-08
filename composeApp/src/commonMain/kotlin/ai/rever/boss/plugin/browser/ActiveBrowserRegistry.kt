package ai.rever.boss.plugin.browser

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
    private val sequencer = AtomicLong(0)

    /**
     * Guards the mutate-then-recompute pair behind [publishWindows].
     *
     * Its own object rather than the map: [entries] is a ConcurrentHashMap read lock-free by
     * [activeIn], so locking on it would put two different jobs under one name.
     */
    private val publishLock = Any()

    private val _windowsWithActiveBrowser = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Windows where a browser is the surface the user is actually in.
     *
     * The browser menu items (Back, Forward, Developer Tools) must grey out where the chord
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
     * focus in a sidebar editor, Cmd+[ / Cmd+] are still taken by the accelerator. Narrowing
     * further needs a focus signal the menu layer does not have; see the dead
     * `AWTKeyboardInterceptor.updateWindowContext` for why the keyboard path cannot supply one
     * either.
     *
     * Recomputed on register and unregister, and via [republish] wherever `handle.isValid` can
     * flip without either - `BrowserHandleImpl` latching `connectionDead` on a transport failure
     * is the one such path, and it is the dangerous direction: a stale entry keeps the menu
     * items ENABLED for a window whose browser is gone, so they swallow Cmd+[ from an editor
     * and then find nothing to act on.
     */
    val windowsWithActiveBrowser: StateFlow<Set<String>> = _windowsWithActiveBrowser.asStateFlow()

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
            _windowsWithActiveBrowser.value = activeBrowserWindows(entries.values.toList(), ::isLive)
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
 * editor. Zoom and Reload stay ungated and keep acting on [selectActiveHandleId]'s broader
 * answer, which predates this and is out of scope here.
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
