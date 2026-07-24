package ai.rever.boss.plugin.tab.codeeditor

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab info for Code Editor tabs.
 *
 * Contains configuration for a code editor tab instance including:
 * - Standard tab properties (id, title, icon)
 * - Editor-specific properties (filePath)
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title for the tab (defaults to file name or "Untitled")
 * @param icon Tab icon vector (defaults to Code icon)
 * @param tabIcon Tab icon wrapper
 * @param filePath Path to the file being edited
 */
data class EditorTabInfo(
    override val id: String,
    override val typeId: TabTypeId = CodeEditorTabType.typeId,
    override val title: String = "Untitled",
    override val icon: ImageVector = Icons.Outlined.Code,
    override val tabIcon: TabIcon? = null,
    val filePath: String = "",
) : TabInfo {
    companion object {
        /** Maximum length for editor tab titles */
        const val MAX_TITLE_LENGTH = 64
    }

    /**
     * Returns a copy of this tab info with an updated title.
     * Title is truncated to [MAX_TITLE_LENGTH] characters.
     */
    fun updateTitle(newTitle: String): EditorTabInfo {
        val truncatedTitle =
            if (newTitle.length > MAX_TITLE_LENGTH) {
                newTitle.take(MAX_TITLE_LENGTH)
            } else {
                newTitle
            }
        return copy(title = truncatedTitle)
    }
}
