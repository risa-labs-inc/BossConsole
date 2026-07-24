package ai.rever.boss.app

import ai.rever.boss.components.events.stripFilePrefix
import ai.rever.boss.components.events.validateFilePath
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.registery.TabInfo
import ai.rever.boss.components.registery.TabTypeId
import ai.rever.boss.dashboard.SplitTemplatesManager
import ai.rever.boss.dashboard.TemplatePanelConfig
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.utils.extractFileName
import kotlin.random.Random

/**
 * Creates a browser tab for the given URL.
 * Extracted to reduce duplication in openTerminalLink.
 */
internal fun createBrowserTab(url: String): FluckTabInfo =
    FluckTabInfo(
        id = "browser-${Random.nextLong()}",
        typeId = TabTypeId("fluck"),
        _title = "Loading...",
        url = url,
    )

/**
 * Creates an editor tab for the given file path.
 * Used in openTerminalLink when handling file: URLs.
 *
 * Note: This function assumes the path has already been validated by the caller.
 * Use [validateFilePath] before calling this function.
 *
 * @param filePath The validated file path (may include "file:" prefix, which will be stripped)
 */
internal fun createEditorTab(filePath: String): EditorTabInfo {
    val cleanPath = stripFilePrefix(filePath)
    val fileName = cleanPath.extractFileName().ifEmpty { "untitled" }
    val fileIconInfo = FileIcons.forFile(fileName)
    return EditorTabInfo(
        id = "editor-${Random.nextLong()}",
        typeId = TabTypeId("editor"),
        title = fileName,
        icon = fileIconInfo.icon,
        tabIcon =
            ai.rever.boss.plugin.api.TabIcon
                .Vector(fileIconInfo.icon, fileIconInfo.color),
        filePath = cleanPath,
    )
}

/**
 * Checks if a URL is a file URL (starts with "file:").
 */
internal fun isFileUrl(url: String): Boolean = url.startsWith("file:")

/**
 * Create a tab from template panel configuration.
 * Used by DashboardEventBus handlers for split template events from Fluck Dashboard.
 */
internal fun createTabFromTemplateConfig(
    panelConfig: TemplatePanelConfig,
    projectPath: String,
): TabInfo? {
    val timestamp = System.currentTimeMillis()

    return when (panelConfig.type) {
        "terminal" -> {
            val command =
                panelConfig.content.command?.let {
                    // shell command → quote {projectPath} so paths with spaces/quotes survive
                    SplitTemplatesManager.processPlaceholders(it, projectPath, null, quoteProjectPath = true)
                }
            TerminalTabInfo(
                id = "terminal-$timestamp",
                typeId = TerminalTabType.typeId,
                title = command?.substringBefore(" ")?.extractFileName() ?: "Terminal",
                icon = TerminalTabType.icon,
                workingDirectory = projectPath,
                initialCommand = command,
            )
        }

        "browser" -> {
            val url = panelConfig.content.url ?: ""
            FluckTabInfo(
                id = "fluck-$timestamp",
                typeId = FluckTabType.typeId,
                _title = "Loading...",
                url = url,
            )
        }

        "editor" -> {
            val filePath =
                panelConfig.content.filePath?.let {
                    SplitTemplatesManager.processPlaceholders(it, projectPath, null)
                } ?: return null
            EditorTabInfo(
                id = "editor-$timestamp",
                typeId = CodeEditorTabType.typeId,
                title = filePath.extractFileName(),
                icon = CodeEditorTabType.icon,
                filePath = filePath,
            )
        }

        else -> {
            null
        }
    }
}
