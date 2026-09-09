package ai.rever.boss.plugin.tab.diff

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Difference

/**
 * Git Diff tab type (host-provided).
 *
 * The diff tab is opened by the host itself: the git data provider's
 * `openDiff` (which plugins call) emits an event the host consumes, and
 * workspace restore rebuilds it. Plugins never construct a [DiffTabInfo]
 * directly, so this type stays host-internal - the plugin-facing surface is
 * `GitDataProvider.openDiff`.
 */
object DiffTabType : TabTypeInfo {
    override val typeId = TabTypeId("diff")
    override val displayName = "Diff"
    override val icon = Icons.Outlined.Difference
}
