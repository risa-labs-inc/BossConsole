package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.workspace.TabConfig

/** Home already has permanent navigation. Legacy Home bookmarks remain openable. */
internal fun bookmarkSaveProblem(config: TabConfig): String? =
    if (config.type == "browser" && FluckTabInfo.isHomeUrl(config.url.orEmpty())) {
        "Use the Home button to return to Home"
    } else {
        bookmarkTargetProblem(config)
    }
