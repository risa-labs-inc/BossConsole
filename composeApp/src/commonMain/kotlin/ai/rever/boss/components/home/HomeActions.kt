package ai.rever.boss.components.home

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.WindowOperations
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("HomeActions")

/**
 * Everything the home screen can do, bound to one window.
 *
 * **The whole point of this class is that it is not a set of callbacks.** The screen it replaces
 * took twelve lambdas from its caller, so each of its two mount points had to supply all twelve -
 * and the browser's about:blank mount supplied eleven of them empty, leaving most of the screen
 * clickable and inert. Here the screen constructs this itself from `LocalWindowId`, so there is no
 * caller to get it wrong and nothing to pass.
 *
 * Every method ends on [DashboardEventBus] (window-scoped, handled in `BossAppEventBusEffects`,
 * bridged to other processes in kernel mode) or, for the two things that are genuinely not
 * window-scoped, on [PanelEventBus] and [WindowOperations].
 *
 * A null [windowId] reports rather than vanishing. The old screen wrapped eleven cards in
 * `windowId?.let { }`, so in that state every one of them did nothing and said nothing.
 */
// One method per action the home screen offers, which is what this class is for. The count tracks
// how much the screen can do; collapsing them behind a single `perform(action)` would put a `when`
// between every caller and its effect for no gain.
@Suppress("TooManyFunctions")
internal class HomeActions(
    private val windowId: String?,
    private val scope: CoroutineScope,
) {
    fun newTab() = emit { DashboardEventBus.newTab(it) }

    fun newTerminal() = emit { DashboardEventBus.newTerminal(it) }

    fun openFileDialog() = emit { DashboardEventBus.showFileDialog(it) }

    fun openProjectDialog() = emit { DashboardEventBus.showProjectDialog(it) }

    fun newProject() = emit { DashboardEventBus.showNewProject(it) }

    fun showSettings() = emit { DashboardEventBus.showSettings(it) }

    fun openSearch() = emit { DashboardEventBus.openSearch(it) }

    fun openFile(path: String) = emit { DashboardEventBus.openFile(path, it) }

    fun openUrl(url: String) = emit { DashboardEventBus.openUrlInNewTab(url, it) }

    fun applyWorkspace(workspaceId: String) = emit { DashboardEventBus.applyWorkspace(workspaceId, it) }

    fun openTabType(
        typeId: String,
        typePluginId: String,
    ) = emit { DashboardEventBus.openTabType(typeId, typePluginId, "", it) }

    fun togglePanel(panelId: PanelId) = emit { PanelEventBus.togglePanel(panelId, sourceWindowId = it) }

    /** Not window-scoped: creating a window has no window to route to. */
    fun newWindow() = WindowOperations.createNewWindow()

    /**
     * Install a plugin from the store and report the outcome.
     *
     * [installing] is mutated so the tile can show progress. The install itself is detached and
     * coalesced per id inside the installer, so this scope ending - the window closing mid
     * download - does not abort it; only the progress indicator goes away.
     */
    fun install(
        tool: HomeTool,
        pluginId: String,
        installing: SnapshotStateMap<String, Unit>,
    ) {
        val provider = HomeCatalogAccess.current()
        if (provider == null) {
            StatusMessageManager.showMessage("The plugin store is not available")
            return
        }
        if (installing.containsKey(pluginId)) return
        installing[pluginId] = Unit
        scope.launch {
            val result = provider.install(pluginId)
            installing.remove(pluginId)
            result.fold(
                onSuccess = { StatusMessageManager.showMessage("Installed ${tool.label}") },
                onFailure = { error ->
                    logger.error(
                        LogCategory.SYSTEM,
                        "Installing a plugin from the home screen failed",
                        mapOf("pluginId" to pluginId),
                        error = error,
                    )
                    StatusMessageManager.showMessage(
                        error.message ?: "Could not install ${tool.label}",
                        durationMs = INSTALL_FAILURE_MESSAGE_MS,
                    )
                },
            )
        }
    }

    /** Route one tile's click. Every branch is one of the methods above. */
    fun launch(
        tool: HomeTool,
        installing: SnapshotStateMap<String, Unit>,
    ) {
        when (val launch = tool.launch) {
            is HomeToolLaunch.OpenTab -> {
                if (launch.needsInput) {
                    // The plugin wants input this tile has nowhere to collect, so hand off to the
                    // dialog that does rather than send an empty string it will reject.
                    newTab()
                } else {
                    openTabType(launch.typeId, launch.typePluginId)
                }
            }

            is HomeToolLaunch.OpenPanel -> {
                togglePanel(launch.panelId)
            }

            is HomeToolLaunch.Recover -> {
                emit { id ->
                    ai.rever.boss.window.MenuActionsHandler.triggerPluginRecovery(
                        id,
                        ai.rever.boss.components.plugin
                            .PluginRecoveryTarget(launch.pluginId),
                    )
                }
            }

            is HomeToolLaunch.Install -> {
                install(tool, launch.pluginId, installing)
            }

            is HomeToolLaunch.HostAction -> {
                launchHostAction(launch.action)
            }
        }
    }

    private fun launchHostAction(action: HomeHostAction) {
        when (action) {
            HomeHostAction.NEW_TAB -> newTab()
            HomeHostAction.NEW_TERMINAL -> newTerminal()
            HomeHostAction.NEW_WINDOW -> newWindow()
            HomeHostAction.OPEN_FILE -> openFileDialog()
            HomeHostAction.OPEN_PROJECT -> openProjectDialog()
            HomeHostAction.NEW_PROJECT -> newProject()
            HomeHostAction.SETTINGS -> showSettings()
            HomeHostAction.SEARCH -> openSearch()
        }
    }

    private fun emit(block: suspend (windowId: String) -> Unit) {
        val id = windowId
        if (id == null) {
            logger.warn(LogCategory.UI, "Home screen action with no window id")
            StatusMessageManager.showMessage("Could not tell which window to use")
            return
        }
        scope.launch { block(id) }
    }

    private companion object {
        /** Longer than the default toast: an install failure says something worth reading. */
        const val INSTALL_FAILURE_MESSAGE_MS = 5000L
    }
}

/** The actions for the window this composable is in. */
@Composable
internal fun rememberHomeActions(): HomeActions {
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()
    return remember(windowId, scope) { HomeActions(windowId, scope) }
}
