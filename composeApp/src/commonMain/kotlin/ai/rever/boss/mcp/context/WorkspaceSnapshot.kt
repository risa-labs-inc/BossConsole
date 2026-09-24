package ai.rever.boss.mcp.context

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSnapshot(
    val activeProjectPath: String?,
    val openTabs: List<TabSnapshot>,
    val tabCounts: TabCounts,
    val activeEditorFile: ActiveEditorFileSnapshot?,
)

@Serializable
data class TabSnapshot(
    val tabId: String,
    val windowId: String,
    val panelId: String,
    val workspaceId: String,
    val workspaceName: String,
    val title: String,
    val type: String,
    val filePath: String? = null,
    val relativePath: String? = null,
    val browserUrl: String? = null,
)

@Serializable
data class TabCounts(
    val total: Int,
    val editor: Int,
    val terminal: Int,
    val browser: Int,
    val other: Int,
)

@Serializable
data class ActiveEditorFileSnapshot(
    val absolutePath: String,
    val relativePath: String?,
    val fileName: String,
)
