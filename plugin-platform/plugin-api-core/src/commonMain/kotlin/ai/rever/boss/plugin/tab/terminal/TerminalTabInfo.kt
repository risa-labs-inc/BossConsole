package ai.rever.boss.plugin.tab.terminal

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab info for Terminal tabs.
 *
 * Contains configuration for a terminal tab instance including:
 * - Standard tab properties (id, title, icon)
 * - Terminal-specific properties (initialCommand, workingDirectory)
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title for the tab
 * @param icon Tab icon vector (defaults to Terminal icon)
 * @param tabIcon Tab icon wrapper
 * @param initialCommand Optional command to run when terminal starts
 * @param workingDirectory Optional working directory for the terminal
 */
data class TerminalTabInfo(
    override val id: String,
    override val typeId: TabTypeId = TerminalTabType.typeId,
    override val title: String = "Terminal",
    override val icon: ImageVector = Icons.Outlined.Terminal,
    override val tabIcon: TabIcon? = null,
    override val initialCommand: String? = null,
    override val workingDirectory: String? = null
) : ai.rever.boss.plugin.api.TerminalTabInfoInterface {
    /**
     * Returns a copy of this tab info with an updated title.
     */
    fun updateTitle(newTitle: String): TerminalTabInfo {
        return copy(title = newTitle)
    }
}
