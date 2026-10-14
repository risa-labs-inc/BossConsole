@file:Suppress("MatchingDeclarationName")

package ai.rever.boss.app

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.search.SPOTLIGHT_UNSUPPORTED_COMMAND_IDS
import ai.rever.boss.window.ClosedTabHistory
import ai.rever.boss.window.MenuActionsHandler

/**
 * BossConsole#700: [KeymapActions.getAllActionIds] advertises 48 command ids to Spotlight's
 * search catalog, but [ai.rever.boss.app.BossAppDialogs]'s `onCommandSelect` `when` only ever
 * handled 22 of them - the other 26 fell through a bare `else -> {}` and silently did nothing
 * when selected.
 *
 * Eighteen of those 26 already have a `MenuActionsHandler` route (every tab-navigation and
 * browser-history command); nothing was wrong with the host wiring, `onCommandSelect` simply
 * never called it. [dispatchSpotlightTabBrowserCommand] is that missing call, extracted into a
 * pure function (rather than inlined into the `when`) so the id-to-trigger mapping is
 * unit-testable without mounting the dialog.
 *
 * The remaining eight - the editor verbs and the debug external-link entry - have no safe host
 * route: the editor lives in a plugin the host cannot reach synchronously from here without
 * either synthesizing keyboard events or duplicating the plugin's own save/find implementation,
 * both of which are worse than reporting "not available yet". Those are named in
 * [SPOTLIGHT_UNSUPPORTED_COMMAND_IDS] (in `search`, not here - see its own KDoc), which
 * [ai.rever.boss.search.GlobalSearchService] excludes from Spotlight's results entirely so they
 * are never offered as selectable in the first place, and which `onCommandSelect` also checks as
 * a last-resort classification for a selection that somehow still arrives. `SpotlightCommandCoverageTest`
 * pins that every id [KeymapActions.getAllActionIds] returns is classified as exactly one of:
 * already handled by `onCommandSelect`'s own pre-existing branches, dispatched here, or named as
 * unsupported - so a future catalog addition nobody wires up fails a test instead of silently
 * doing nothing.
 */
internal sealed interface SpotlightDispatchOutcome {
    /** The command was recognized and its trigger fired. */
    data object Dispatched : SpotlightDispatchOutcome

    /** [dispatchSpotlightTabBrowserCommand] does not recognize this id at all. */
    data object NotRecognized : SpotlightDispatchOutcome

    /**
     * Recognized, but the state it needs is not there right now (no other tab to switch to, no
     * recently closed tab, no tab at that position). Broadcasting the trigger anyway would be
     * claimed by nothing and close Spotlight with no visible effect - the same silent-failure
     * shape #700 exists to remove, just one level deeper: recognized this time, but still
     * unable to act. [reason] is shown to the user.
     */
    data class Unavailable(
        val reason: String,
    ) : SpotlightDispatchOutcome
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun dispatchSpotlightTabBrowserCommand(
    actionId: String,
    windowId: String,
): SpotlightDispatchOutcome {
    when (actionId) {
        // Gated the same way the keyboard interceptor gates Ctrl+Tab/Cmd+Opt+Arrow
        // (MenuActionsHandler.canStepTabs, AWTKeyboardInterceptor.dispatchIfCanStepTabs): with
        // one tab in the active panel there is nowhere to step, and broadcasting the trigger
        // would be silently claimed by nothing.
        //
        // Spotlight's selection is one discrete action with no modifier-release gesture to
        // commit an MRU cycle on - unlike Ctrl+Tab, which commits when the held modifier is
        // released - so the step and its commit fire back to back. triggerCommitTabCycle is a
        // documented no-op outside an in-progress cycle, so this is safe even if the window was
        // not mid-cycle.
        KeymapActions.TAB_NEXT -> {
            if (!MenuActionsHandler.canStepTabs(windowId)) return unavailableNoOtherTab
            MenuActionsHandler.triggerNextTab(windowId)
            MenuActionsHandler.triggerCommitTabCycle(windowId)
        }

        KeymapActions.TAB_PREVIOUS -> {
            if (!MenuActionsHandler.canStepTabs(windowId)) return unavailableNoOtherTab
            MenuActionsHandler.triggerPreviousTab(windowId)
            MenuActionsHandler.triggerCommitTabCycle(windowId)
        }

        // Positional stepping starts no MRU cycle (see TabSwitchAction.NEXT_POSITIONAL's own
        // KDoc), so it needs no matching commit.
        KeymapActions.TAB_NEXT_POSITIONAL -> {
            if (!MenuActionsHandler.canStepTabs(windowId)) return unavailableNoOtherTab
            MenuActionsHandler.triggerNextTabPositional(windowId)
        }

        KeymapActions.TAB_PREVIOUS_POSITIONAL -> {
            if (!MenuActionsHandler.canStepTabs(windowId)) return unavailableNoOtherTab
            MenuActionsHandler.triggerPreviousTabPositional(windowId)
        }

        // Same gate the keyboard interceptor uses (ClosedTabHistory.hasEntries) - an empty
        // stack means nothing would happen.
        KeymapActions.TAB_REOPEN_CLOSED -> {
            if (!ClosedTabHistory.hasEntries(windowId)) {
                return SpotlightDispatchOutcome.Unavailable("no recently closed tab to reopen")
            }
            MenuActionsHandler.triggerReopenClosedTab(windowId)
        }

        // The eight Cmd+1..Cmd+8 ids resolve through one lookup (KeymapActions.TAB_SELECT_BY_INDEX),
        // not eight near-identical branches each re-typing its own index literal - the same
        // idiom AWTKeyboardInterceptor already uses for this exact mapping, and for the same
        // reason: a hand-typed index is a class of typo ("select 5" wired to index 3) that a
        // lookup makes unrepresentable. Gated on dispatchIfTabExistsAt's own check
        // (MenuActionsHandler.activePanelTabCount(windowId) > index): selectTabByPosition
        // silently ignores an out-of-range position, so an ungated broadcast for a tab that
        // is not there would close Spotlight with no visible effect.
        in KeymapActions.TAB_SELECT_BY_INDEX -> {
            val index = KeymapActions.TAB_SELECT_BY_INDEX.indexOf(actionId)
            if (MenuActionsHandler.activePanelTabCount(windowId) <= index) {
                return SpotlightDispatchOutcome.Unavailable("no tab at that position")
            }
            MenuActionsHandler.triggerSelectTabByIndex(windowId, index)
        }

        // Index 0, not 8: Cmd+9 means "the last tab", so any non-empty panel serves it - the
        // same reasoning and the same gate AWTKeyboardInterceptor uses for TAB_SELECT_LAST.
        KeymapActions.TAB_SELECT_LAST -> {
            if (MenuActionsHandler.activePanelTabCount(windowId) <= 0) {
                return SpotlightDispatchOutcome.Unavailable("no tabs open")
            }
            MenuActionsHandler.triggerSelectLastTab(windowId)
        }

        // Not gated on whether a browser tab is active in this window: the answer lives in
        // ActiveBrowserRegistry, which resolves asynchronously relative to this emit, so there
        // is no synchronous check to gate on the way the tab-stepping commands have one. Best
        // effort, same as the keyboard shortcut path for these same four chords.
        KeymapActions.BROWSER_FIND -> {
            MenuActionsHandler.triggerBrowserFind(windowId)
        }

        KeymapActions.BROWSER_BACK -> {
            MenuActionsHandler.triggerBrowserBack(windowId)
        }

        KeymapActions.BROWSER_FORWARD -> {
            MenuActionsHandler.triggerBrowserForward(windowId)
        }

        KeymapActions.BROWSER_DEVTOOLS -> {
            MenuActionsHandler.triggerBrowserDevTools(windowId)
        }

        else -> {
            return SpotlightDispatchOutcome.NotRecognized
        }
    }
    return SpotlightDispatchOutcome.Dispatched
}

private val unavailableNoOtherTab = SpotlightDispatchOutcome.Unavailable("no other tab to switch to")
