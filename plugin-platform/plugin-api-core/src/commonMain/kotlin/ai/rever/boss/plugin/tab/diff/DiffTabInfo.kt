package ai.rever.boss.plugin.tab.diff

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.random.Random

/**
 * Tab info for a git diff tab.
 *
 * Exactly one scope applies, mirroring `GitDataProvider`'s diff methods:
 * - working/staged file diff: [filePath] set, refs null
 * - single commit diff: [fromRef] set ([filePath] optionally restricts it)
 * - ref range diff: [fromRef] and [toRef] set ([filePath] optional)
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title (file name, short hash, or a ref range)
 * @param icon Tab icon vector
 * @param tabIcon Tab icon wrapper
 * @param filePath File path relative to the project root ("" for commit/range diffs)
 * @param staged true = staged (index vs HEAD), false = working tree vs index
 * @param fromRef Base ref for commit/range diffs
 * @param toRef Target ref for range diffs
 */
data class DiffTabInfo(
    override val id: String,
    override val typeId: TabTypeId = DiffTabType.typeId,
    override val title: String,
    override val icon: ImageVector = Icons.Outlined.Difference,
    override val tabIcon: TabIcon? = null,
    override val filePath: String = "",
    override val staged: Boolean = false,
    override val fromRef: String? = null,
    override val toRef: String? = null,
    // DiffTabConfig is the read side of this config, on the plugin api surface:
    // it is what lets the renderer live in the editor-tab plugin (where the
    // lexer, language servers and overview ruler are) instead of the host.
) : TabInfo,
    ai.rever.boss.plugin.api.DiffTabConfig {
    companion object {
        fun newId(): String = "diff-${Random.nextLong()}"

        /**
         * Build a diff tab for the given scope. The title is derived from the
         * scope: file name for file diffs, short hash for commit diffs,
         * "from...to" (truncated) for range diffs.
         */
        fun create(
            filePath: String,
            staged: Boolean = false,
            fromRef: String? = null,
            toRef: String? = null,
        ): DiffTabInfo {
            val title =
                when {
                    fromRef != null && toRef != null -> {
                        rangeTitle(fromRef, toRef)
                    }

                    fromRef != null -> {
                        shortRef(fromRef)
                    }

                    filePath.isNotBlank() -> {
                        filePath.substringAfterLast('/').ifEmpty { "Diff" }
                    }

                    else -> {
                        "Diff"
                    }
                }
            return DiffTabInfo(
                id = newId(),
                title = title,
                filePath = filePath,
                staged = staged,
                fromRef = fromRef,
                toRef = toRef,
            )
        }

        private fun shortRef(ref: String): String {
            val trimmed = ref.trim()
            return if (trimmed.length > 12) trimmed.take(8) + "…" else trimmed
        }

        private fun rangeTitle(
            from: String,
            to: String,
        ): String {
            val t = "${shortRef(from)}...${shortRef(to)}"
            return if (t.length > 40) t.take(39) + "…" else t
        }
    }
}
