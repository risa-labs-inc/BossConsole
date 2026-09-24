package ai.rever.boss.mcp.context

import ai.rever.boss.git.GitService
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder

object WorkspaceSnapshotCollector {
    fun collect(
        activeTabsSupplier: () -> List<ActiveTab> = { TopOfMindStateHolder.activeTabs.value },
        projectPathSupplier: () -> String? = { GitService.getCurrentProjectPath() },
    ): WorkspaceSnapshot {
        val activeProjectPath = projectPathSupplier()
        val rawTabs = activeTabsSupplier()

        var editorCount = 0
        var terminalCount = 0
        var browserCount = 0
        var otherCount = 0

        val openTabs =
            rawTabs.map { tab ->
                val category = categorizeTab(tab.tabInfo.typeId.typeId)
                when (category) {
                    "editor" -> editorCount++
                    "terminal" -> terminalCount++
                    "browser" -> browserCount++
                    else -> otherCount++
                }

                val filePath = extractFilePath(category, tab)
                val relPath =
                    if (filePath != null && activeProjectPath != null) {
                        computeSafeRelativePath(filePath, activeProjectPath)
                    } else {
                        null
                    }

                TabSnapshot(
                    tabId = tab.tabInfo.id,
                    windowId = tab.windowId,
                    panelId = tab.panelId,
                    workspaceId = tab.workspaceId,
                    workspaceName = tab.workspaceName,
                    title = tab.tabInfo.title,
                    type = category,
                    filePath = filePath,
                    relativePath = relPath,
                    browserUrl = extractBrowserUrl(category, tab),
                )
            }

        return WorkspaceSnapshot(
            activeProjectPath = activeProjectPath,
            openTabs = openTabs,
            tabCounts =
                TabCounts(
                    total = openTabs.size,
                    editor = editorCount,
                    terminal = terminalCount,
                    browser = browserCount,
                    other = otherCount,
                ),
            activeEditorFile = buildActiveEditorFile(openTabs, activeProjectPath),
        )
    }

    private fun categorizeTab(rawTypeId: String): String {
        val typeId = rawTypeId.lowercase()
        return when {
            typeId == "editor" || typeId.contains("codeeditor") -> "editor"
            typeId == "terminal" || typeId.contains("term") -> "terminal"
            typeId == "fluck" || typeId == "browser" -> "browser"
            else -> "other"
        }
    }

    private fun extractFilePath(
        category: String,
        tab: ActiveTab,
    ): String? {
        if (category != "editor") return null
        return runCatching {
            val getter = tab.tabInfo.javaClass.getMethod("getFilePath")
            getter.invoke(tab.tabInfo) as? String
        }.getOrNull()?.ifBlank { null }
    }

    private fun extractBrowserUrl(
        category: String,
        tab: ActiveTab,
    ): String? {
        if (category != "browser") return null
        return runCatching {
            val getter = tab.tabInfo.javaClass.getMethod("getCurrentUrl")
            getter.invoke(tab.tabInfo) as? String
        }.getOrNull()?.ifBlank { null }
    }

    private fun buildActiveEditorFile(
        openTabs: List<TabSnapshot>,
        activeProjectPath: String?,
    ): ActiveEditorFileSnapshot? {
        val firstEditorTab = openTabs.firstOrNull { it.type == "editor" && it.filePath != null }
        return firstEditorTab?.filePath?.let { path ->
            val name = path.replace('\\', '/').substringAfterLast('/')
            val rel = if (activeProjectPath != null) computeSafeRelativePath(path, activeProjectPath) else null
            ActiveEditorFileSnapshot(
                absolutePath = path,
                relativePath = rel,
                fileName = name,
            )
        }
    }

    internal fun computeSafeRelativePath(
        filePath: String,
        projectPath: String,
    ): String? {
        val normFile = filePath.replace('\\', '/').trimEnd('/')
        val normProj = projectPath.replace('\\', '/').trimEnd('/')

        val fileDrive = if (normFile.length >= 2 && normFile[1] == ':') normFile.substring(0, 2).lowercase() else null
        val projDrive = if (normProj.length >= 2 && normProj[1] == ':') normProj.substring(0, 2).lowercase() else null

        if (fileDrive != null && projDrive != null && fileDrive != projDrive) {
            return null
        }

        val prefix = "$normProj/"
        return if (normFile.startsWith(prefix, ignoreCase = true)) {
            normFile.substring(prefix.length)
        } else if (normFile.equals(normProj, ignoreCase = true)) {
            ""
        } else {
            null
        }
    }
}
