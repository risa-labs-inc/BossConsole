package ai.rever.boss.topofmind

import ai.rever.boss.plugin.api.TabInfo

/**
 * Data class representing an active tab in the Top of Mind panel.
 * This is a host-app type that wraps TabInfo from the plugin API.
 */
data class ActiveTab(
    val tabInfo: TabInfo,
    val workspaceId: String,
    val workspaceName: String,
    val panelId: String,
    val windowId: String,
    val splitPosition: String? = null // "Left", "Right", "Top", "Bottom", or null for single panel
)

/**
 * Hierarchical structure for workspace tab sections
 */
sealed class WorkspaceTabStructure {
    data class TabItem(
        val activeTab: ActiveTab
    ) : WorkspaceTabStructure()

    data class SplitSection(
        val sectionName: String,  // "Left", "Right", "Top", "Bottom"
        val children: List<WorkspaceTabStructure>,
        val level: Int = 0
    ) : WorkspaceTabStructure()
}

/**
 * Simplified tree structure for organizing tabs (workspace level only)
 */
sealed class TabTreeNode {
    abstract val id: String
    abstract val name: String
    abstract val level: Int

    data class WorkspaceNode(
        override val id: String,
        override val name: String,
        override val level: Int = 0,
        val workspaceId: String,
        var isExpanded: Boolean = true,
        val tabStructure: List<WorkspaceTabStructure> = emptyList()
    ) : TabTreeNode()

    data class TabNode(
        override val id: String,
        override val name: String,
        override val level: Int,
        val activeTab: ActiveTab
    ) : TabTreeNode()
}

/**
 * Breadcrumb item types
 */
enum class BreadcrumbType {
    WORKSPACE,
    PANEL,
    TAB,
    SEPARATOR
}

/**
 * Data class for breadcrumb navigation
 */
data class BreadcrumbItem(
    val text: String,
    val type: BreadcrumbType,
    val clickable: Boolean = true,
    val onClick: (() -> Unit)? = null
)
