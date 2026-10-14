package ai.rever.boss.search

import ai.rever.boss.keymap.model.KeymapActions

/**
 * Catalog ids [KeymapActions.getAllActionIds] returns with no safe Spotlight-reachable
 * dispatch route (BossConsole#700) - see [ai.rever.boss.app.dispatchSpotlightTabBrowserCommand]'s
 * KDoc for why: these belong to the editor-tab plugin or are a debug-only entry, and there is
 * no route to them from the host that does not either synthesize keyboard events or duplicate
 * the plugin's own implementation.
 *
 * In `search`, not `app`: [GlobalSearchService.searchCommands] excludes these from its results
 * entirely, so they are never OFFERED as selectable commands in the first place - the issue's
 * acceptance criteria asks for that, not merely reporting "unavailable" after one is picked.
 * `app` already depends on `search` (for [ai.rever.boss.search.SearchSources] and friends), so
 * this lives on the side of that dependency both callers can reach without inverting it.
 */
internal val SPOTLIGHT_UNSUPPORTED_COMMAND_IDS: Set<String> =
    setOf(
        KeymapActions.EDITOR_SAVE,
        KeymapActions.EDITOR_SAVE_ALL,
        KeymapActions.EDITOR_FIND,
        KeymapActions.EDITOR_REPLACE,
        KeymapActions.EDITOR_FIND_NEXT,
        KeymapActions.EDITOR_FIND_PREVIOUS,
        KeymapActions.EDITOR_GO_TO_LINE,
        KeymapActions.TEST_EXTERNAL_LINK,
    )
