package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkOpenResult
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One capability-aware opening route. It never replaces tabs or consumes a pending split. */
internal class BookmarkOpeningService(
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val resolveDefaultDirectory: suspend (SplitViewState) -> String = ::bookmarkDefaultDirectory,
    private val resolveProjectDirectory: (SplitViewState) -> String? = ::bookmarkProjectDirectory,
    private val checkPath: suspend (String, Boolean) -> Boolean = { path, directory ->
        withContext(Dispatchers.IO) {
            val file = File(path)
            file.isAbsolute && (if (directory) file.isDirectory else file.isFile) && file.canRead()
        }
    },
) {
    companion object {
        val shared = BookmarkOpeningService()
    }

    private data class OpeningKey(
        val window: SplitViewState,
        val panelId: String,
        val bookmarkId: String,
        val config: TabConfig,
        val forceNewTab: Boolean,
    )

    private val inFlight = mutableMapOf<OpeningKey, CompletableDeferred<BookmarkOpenResult>>()
    private val lock = Any()

    suspend fun open(
        splitView: SplitViewState,
        bookmark: Bookmark,
        panelId: String? = null,
        forceNewTab: Boolean = false,
        targetStillAvailable: () -> Boolean = { true },
    ): BookmarkOpenResult =
        withContext(uiDispatcher) {
            val target = panelId ?: splitView.activePanelId
            val panel = splitView.getPanel(target)
            if (panel == null || !targetStillAvailable()) {
                return@withContext failure("The target pane closed. Open the bookmark again in an existing pane.")
            }
            val key = OpeningKey(splitView, target, bookmark.id, bookmark.tabConfig, forceNewTab)
            val completion = CompletableDeferred<BookmarkOpenResult>()
            val existing = synchronized(lock) { inFlight.putIfAbsent(key, completion) }
            if (existing != null) return@withContext existing.await()
            try {
                val result =
                    openOnce(splitView, target, bookmark.tabConfig, forceNewTab) {
                        targetStillAvailable() && splitView.getPanel(target) === panel
                    }
                linkTerminal(bookmark, result)
                completion.complete(result)
                result
            } catch (cancelled: CancellationException) {
                completion.completeExceptionally(cancelled)
                throw cancelled
            } catch (_: LinkageError) {
                failure("The bookmark tool was unloaded. Enable it in Tools and retry.").also(completion::complete)
            } catch (_: Exception) {
                failure("Unable to open bookmark. Check its saved target and try again.").also(completion::complete)
            } finally {
                synchronized(lock) { inFlight.remove(key, completion) }
            }
        }

    private fun linkTerminal(
        bookmark: Bookmark,
        result: BookmarkOpenResult,
    ) {
        val tabId = result.tabId
        if (bookmark.tabConfig.type == "terminal" && result.success && tabId != null) {
            TerminalBookmarkLinks.bind(tabId, bookmark.id)
        }
    }

    private suspend fun openOnce(
        splitView: SplitViewState,
        panelId: String,
        config: TabConfig,
        forceNewTab: Boolean,
        targetStillAvailable: () -> Boolean,
    ): BookmarkOpenResult {
        val prepared =
            if (config.type == "terminal" && DefaultWorkingDirectory.restored(config.workingDirectory) == null) {
                config.copy(workingDirectory = resolveDefaultDirectory(splitView))
            } else {
                config
            }
        val type = bookmarkTabType(prepared.type)
        val problem =
            bookmarkTargetProblem(prepared) ?: if (type == null || !splitView.tabRegistry.isRegistered(type)) {
                missingTool(config.type).message
            } else {
                diffProjectProblem(prepared, splitView) ?: validate(prepared)
            }
        return when {
            problem != null -> failure(problem)
            !targetStillAvailable() -> failure("The target pane was closed or changed. Open the bookmark again.")
            type == null || !splitView.tabRegistry.isRegistered(type) -> missingTool(config.type)
            else -> openValidated(splitView, panelId, prepared, forceNewTab)
        }
    }

    private fun diffProjectProblem(
        config: TabConfig,
        splitView: SplitViewState,
    ): String? {
        if (config.type != "diff") return null
        val current = resolveProjectDirectory(splitView)?.let { File(it).absoluteFile.normalize().path }
        val saved = config.workingDirectory?.let { File(it).absoluteFile.normalize().path }
        return if (current != saved) "Open this bookmark in its saved project: $saved" else null
    }

    private fun openValidated(
        splitView: SplitViewState,
        panelId: String,
        config: TabConfig,
        forceNewTab: Boolean,
    ): BookmarkOpenResult {
        val component = requireNotNull(splitView.getPanel(panelId)).tabsComponent
        val existing =
            if (forceNewTab && config.type != "composer") {
                -1
            } else {
                component.tabsState.value.tabs
                    .indexOfFirst { matchesResource(config, it) }
            }
        if (existing >= 0 && forceNewTab && config.type == "composer") {
            return failure(
                "This Composer session already has a tab in this pane. " +
                    "Use Open to return to it; a second tab for the same session is not supported.",
            )
        }
        return if (existing >= 0) {
            val tabId =
                component.tabsState.value.tabs[existing]
                    .id
            component.selectTab(existing)
            splitView.setActivePanel(panelId)
            BookmarkOpenResult(success = true, tabId = tabId, reused = true)
        } else {
            val tab = createTab(config)
            val index = component.addTab(tab)
            if (index < 0) {
                missingTool(config.type)
            } else {
                splitView.setActivePanel(panelId)
                BookmarkOpenResult(success = true, tabId = tab.id)
            }
        }
    }

    private suspend fun validate(config: TabConfig): String? =
        when (config.type) {
            "browser" -> {
                if (config.url.isNullOrBlank()) "No saved web address. Edit this bookmark before opening." else null
            }

            "editor", "jupyter" -> {
                when {
                    config.filePath.isNullOrBlank() -> "No saved file path. Save the file and update this bookmark."

                    !checkPath(
                        requireNotNull(config.filePath),
                        false,
                    ) -> "The saved file is missing or inaccessible. Edit the bookmark to select an existing file."

                    else -> null
                }
            }

            "terminal" -> {
                val directory = config.workingDirectory
                if (!directory.isNullOrBlank() && !checkPath(directory, true)) {
                    "The terminal's saved startup folder is missing or inaccessible. Edit the bookmark before opening."
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private fun createTab(config: TabConfig): TabInfo {
        val id = "bookmark-${UUID.randomUUID()}"
        return when (config.type) {
            "browser" -> {
                FluckTabInfo(
                    id = id,
                    typeId = FluckTabType.typeId,
                    _title = config.title,
                    url = requireNotNull(config.url),
                    faviconCacheKey = config.faviconCacheKey,
                )
            }

            "editor" -> {
                EditorTabInfo(id = id, title = config.title, filePath = requireNotNull(config.filePath))
            }

            "terminal" -> {
                TerminalTabInfo(
                    id = id,
                    title = config.title,
                    initialCommand = config.initialCommand,
                    workingDirectory = config.workingDirectory,
                )
            }

            "diff" -> {
                DiffTabInfo.create(requireNotNull(config.filePath)).copy(title = config.title)
            }

            "composer" -> {
                ComposerTabInfo.create(requireNotNull(config.filePath), config.title)
            }

            "jupyter" -> {
                JupyterTabInfo.create(requireNotNull(config.filePath), config.title)
            }

            else -> {
                error("Unsupported bookmark type")
            }
        }
    }

    private fun missingTool(type: String): BookmarkOpenResult {
        val name =
            when (type) {
                "browser" -> "Fluck Browser"
                "editor" -> "Editor"
                "terminal" -> "Terminal"
                "diff" -> "Diff viewer"
                "composer" -> "Composer"
                else -> "Jupyter Notebook"
            }
        return failure("$name is unavailable. Enable or install it in Tools, then open the bookmark again.")
    }

    private fun failure(message: String) = BookmarkOpenResult(success = false, message = message)
}

internal fun bookmarkTabType(type: String): TabTypeId? =
    when (type) {
        "browser" -> FluckTabType.typeId
        "editor" -> CodeEditorTabType.typeId
        "terminal" -> TerminalTabType.typeId
        "jupyter" -> JupyterTabInfo.TYPE_ID
        "diff" -> DiffTabType.typeId
        "composer" -> ComposerTabType.typeId
        else -> null
    }

/** Terminal startup configuration is not the identity of a live process; never reuse it. */
internal fun matchesResource(
    config: TabConfig,
    tab: TabInfo,
): Boolean =
    when (config.type) {
        "browser" -> tab is FluckTabInfo && tab.currentUrl == config.url
        "editor" -> tab is EditorTabInfo && tab.filePath == config.filePath
        "jupyter" -> tab is JupyterTabInfo && tab.filePath == config.filePath
        "diff" -> matchesWorkingTreeDiff(config, tab)
        "composer" -> matchesComposerSession(config, tab)
        else -> false
    }

private fun matchesWorkingTreeDiff(
    config: TabConfig,
    tab: TabInfo,
): Boolean {
    if (tab !is DiffTabInfo || tab.staged) return false
    return tab.fromRef == null && tab.toRef == null && tab.filePath == config.filePath
}

private fun matchesComposerSession(
    config: TabConfig,
    tab: TabInfo,
): Boolean {
    if (tab.typeId != ComposerTabType.typeId) return false
    val session = if (tab is ComposerTabInfo) tab.sessionId else tab.id
    return session == config.filePath
}

/** Synchronous save eligibility; opening additionally checks current provider and path availability. */
internal fun bookmarkTargetProblem(config: TabConfig): String? =
    when (config.type) {
        "browser" -> if (config.url.isNullOrBlank()) "Save a web address before bookmarking this tab." else null
        "editor", "jupyter" -> savedFileProblem(config.filePath)
        "diff" -> diffBookmarkPathProblem(config)
        "composer" -> if (config.filePath.isNullOrBlank()) "Save a Composer session before bookmarking it." else null
        "terminal" -> terminalFolderProblem(config.workingDirectory)
        else -> "This tab type cannot be restored from a bookmark."
    }

private fun savedFileProblem(path: String?): String? =
    when {
        path.isNullOrBlank() -> "Save this file before creating a bookmark."
        !File(path).isAbsolute -> "Choose an absolute saved file path for this bookmark."
        else -> null
    }

private fun terminalFolderProblem(path: String?): String? =
    if (!path.isNullOrBlank() && !File(path).isAbsolute) {
        "Choose an absolute startup folder for this terminal bookmark."
    } else {
        null
    }

private suspend fun bookmarkDefaultDirectory(state: SplitViewState): String {
    val windowId =
        SplitViewStateRegistry.states.value.entries
            .firstOrNull { it.value === state }
            ?.key
    val projectPath =
        windowId?.let {
            WindowProjectStateRegistry
                .get(it)
                ?.selectedProject
                ?.value
                ?.path
        }
    return withContext(Dispatchers.IO) { DefaultWorkingDirectory.resolve(projectPath) }
}

private fun bookmarkProjectDirectory(state: SplitViewState): String? =
    SplitViewStateRegistry.states.value.entries.firstOrNull { it.value === state }?.key?.let {
        WindowProjectStateRegistry
            .get(it)
            ?.selectedProject
            ?.value
            ?.path
    }

private fun diffBookmarkPathProblem(config: TabConfig): String? {
    if (config.workingDirectory.isNullOrBlank()) {
        return "This diff has no saved project. Save it again from its original project."
    }
    return runCatching {
        val project = File(config.workingDirectory!!).toPath().normalize()
        val file = config.filePath?.takeIf { it.isNotBlank() }?.let { project.resolve(it).normalize() }
        if (!project.isAbsolute || file == null) {
            "Choose a file within the saved diff project."
        } else if (file == project || !file.startsWith(project)) {
            "Choose a file within the saved diff project."
        } else {
            null
        }
    }.getOrElse { "The saved diff file path is invalid." }
}
