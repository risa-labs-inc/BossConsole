package ai.rever.boss.window

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The stack Cmd+Shift+T pops: recently closed tabs, newest first, per WINDOW.
 *
 * Window-scoped rather than panel-scoped on purpose. A panel can be closed by the same gesture
 * that closes its last tab (see the close-tab handler in BossAppMenuActionEffects), so a stack
 * owned by the panel would be collected exactly when the user wants to undo - and browsers scope
 * this to the window anyway. Reopening therefore lands in whichever panel is active now, not
 * necessarily the one the tab was closed from; that is the same compromise Chrome makes when the
 * originating window is gone.
 *
 * Holds up to [MAX_ENTRIES] TabInfos per window until that window closes, which for browser
 * tabs means the URL and title of CLOSED tabs living in memory for the window's lifetime.
 * Nothing is persisted and there is no private-browsing mode today; if one arrives, or a "clear
 * history" action, this stack has to be part of it (see [clearAll]) and such tabs should skip
 * [record] the way runner terminals do.
 *
 * What is recorded is the panel's CURRENT [TabInfo] - the live navigation state, so reopening a
 * browser tab returns to the page it was showing, not the URL it was opened with. For browser
 * tabs the entry is a sanitized copy holding only what reopen reads; the deep back/forward list
 * is dropped at record time - see [retainedSnapshot]. The tab's component is NOT retained:
 * `removeTab` destroys it (and with it any Chromium process) before this ever sees the entry, so
 * a deep history costs a handful of config objects, not browsers.
 *
 * Every operation holds ONE monitor rather than a lock per window. Two rounds of review found
 * races in the finer-grained version, both of the same shape: the deque a caller is holding is
 * no longer the map's, so a depth gets published for a window that has been closed, or a fresh
 * deque outlives the window that owned it. Neither had a cheap fix that stayed local. Nothing
 * here contends - callers are on the Compose UI thread and the menu collectors only read a
 * StateFlow - so the coarse lock costs nothing and removes the whole class.
 */
object ClosedTabHistory {
    /**
     * How many closures a window remembers. Chrome keeps 25; the cost here is one [TabInfo] each,
     * and the bound matters mainly so "Close Other Tabs" on a huge window cannot grow without end.
     */
    const val MAX_ENTRIES = 25

    private val byWindow = mutableMapOf<String, ArrayDeque<TabInfo>>()

    private val _depths = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * How many reopenable closures each window holds.
     *
     * Exposed as state, not just queried, because the File menu's "Reopen Closed Tab" item has
     * to grey itself out the moment the stack empties - the same reason MenuActionsHandler
     * publishes splitEnabledState rather than letting the menu ask.
     */
    val depths: StateFlow<Map<String, Int>> = _depths.asStateFlow()

    /**
     * Record [tab] as the most recently closed tab in [windowId].
     *
     * Callers pass only USER-visible closures. Closing a tab because its plugin was disabled, or
     * tearing a window's tabs down to swap workspaces, must not land here: the first cannot be
     * recreated (its factory is gone) and the second would bury the user's real closures under a
     * layer of bookkeeping.
     */
    fun record(
        windowId: String,
        tab: TabInfo,
    ) = synchronized(byWindow) {
        val retained = retainedSnapshot(tab)
        val stack = byWindow.getOrPut(windowId) { ArrayDeque() }
        // Re-closing a reopened tab should move it to the top, not add a second copy.
        stack.removeAll { it.id == retained.id }
        stack.addFirst(retained)
        while (stack.size > MAX_ENTRIES) stack.removeLast()
        // Published under the lock: computing the depth here and publishing outside would let a
        // concurrent record and pop publish their depths in the opposite order.
        publishDepth(windowId, stack.size)
    }

    /**
     * What the stack actually keeps of [tab]: a copy stripped to what reopen reads.
     *
     * A live browser tab ([FluckTabInfo]) carries its whole back/forward list - every (title,
     * URL) pair the user visited, plus [FluckTabInfo.historyIndex] - but nothing that reopens a
     * tab ever consults that list: the tab-type factory rebuilds from `currentUrl.ifEmpty { url }`
     * (FluckTabComponent), and in-session back/forward belongs to the engine's Navigation, not
     * to this list. Retaining it meant the URLs of every page a user visited, on up to
     * [MAX_ENTRIES] dead tabs, living in memory for the window's lifetime - over-retention, and
     * precisely the data a private-browsing mode or a "clear history" action would have to scrub
     * (#330 tracks adding one). So the retained entry keeps the reopen essentials - id, typeId,
     * title, currentUrl - and drops the derived deep history.
     *
     * Non-browser tabs are kept exactly as handed in: a third-party plugin's [TabInfo] is one
     * of its own classes, and copying it here could hand its rebuild factory an instance from
     * the wrong classloader (the same crash [dropEntriesFor] guards against).
     */
    private fun retainedSnapshot(tab: TabInfo): TabInfo =
        when (tab) {
            is FluckTabInfo -> tab.copy(navigationHistory = mutableListOf(), historyIndex = -1)
            else -> tab
        }

    /** Remove and return the most recently closed tab in [windowId], or null if there is none. */
    fun pop(windowId: String): TabInfo? =
        synchronized(byWindow) {
            val stack = byWindow[windowId] ?: return null
            stack.removeFirstOrNull()?.also { publishDepth(windowId, stack.size) }
        }

    /**
     * Whether [windowId] has anything to reopen.
     *
     * Read off [depths] rather than the deque, so the interceptor's Cmd+Shift+T gate and the
     * File menu item's enabled flag are the same answer from the same surface instead of two
     * readings of one piece of state.
     */
    fun hasEntries(windowId: String): Boolean = (_depths.value[windowId] ?: 0) > 0

    /**
     * Drop every recorded entry whose tab type belongs to [pluginId], across all windows.
     *
     * The teardown loop passes `recordForReopen = false` for the tabs IT closes, but entries the
     * user closed before the unload are already on the stack, and a third-party plugin's own
     * `TabInfo` implementation is one of its classes: holding 25 of them for a window's lifetime
     * pins the plugin's classloader, which is the leak shape this repo takes seriously.
     *
     * It also removes a crash. An update is uninstall then reinstall, so a surviving entry hands
     * the NEW classloader's factory an instance of the OLD class; `TabInfo` is parent-first so
     * the interface matches, but a factory doing `config as MyTabInfo` throws. [pop] answering
     * with such an entry is the only way that reaches `addTab`.
     */
    fun dropEntriesFor(pluginId: String) =
        synchronized(byWindow) {
            byWindow.keys.toList().forEach { windowId ->
                val stack = byWindow.getValue(windowId)
                if (stack.removeAll { it.typeId.pluginId == pluginId }) {
                    publishDepth(windowId, stack.size)
                }
            }
        }

    /**
     * Drop a closed window's history.
     *
     * Both the map entry and the depth go under one lock, so a closure landing as the window
     * closes cannot leave a depth for a window with no deque, nor a deque for a window that is
     * gone - each holding up to 25 TabInfos for the life of the process.
     */
    fun clear(windowId: String) =
        synchronized(byWindow) {
            byWindow.remove(windowId)
            _depths.update { it - windowId }
        }

    /**
     * Drop every window's history at once.
     *
     * The hook a future "clear browsing history" action or private-browsing exit calls: a user
     * asking to clear history means closed-tab entries too, since the stack holds page URLs and
     * titles they asked to forget. Per-window [clear] covers window teardown and [dropEntriesFor]
     * covers plugin unload; neither reaches into the OTHER windows' stacks the way a global
     * action must.
     *
     * Not wired into any menu today - no such action exists yet, and #330 tracks adding one - so
     * the eventual action lands as a one-line call rather than a change to this class's locking.
     * Idempotent: sweeping an already-empty stack is a no-op.
     */
    fun clearAll() =
        synchronized(byWindow) {
            byWindow.clear()
            _depths.value = emptyMap()
        }

    private fun publishDepth(
        windowId: String,
        depth: Int,
    ) {
        _depths.update { if (depth == 0) it - windowId else it + (windowId to depth) }
    }
}
