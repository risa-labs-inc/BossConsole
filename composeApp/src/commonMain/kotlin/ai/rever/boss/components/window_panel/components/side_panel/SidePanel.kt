package ai.rever.boss.components.window_panel.components.side_panel

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.PluginUpdateRegistry
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.window_panel.components.BossPanelTopBar
import ai.rever.boss.mcp.EvolverContract
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun BossDraggableComponent.SidePanel(
    panel: Panel,
    panelComponentStore: PanelComponentStore,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val pluginContentId = getPanelContentId(panel)

    // While this plugin is hosted in a main tab, don't also render it in the sidebar
    // (a panel must compose in exactly one place — see panelsHostedAsTab).
    if (isHostedAsTab(pluginContentId)) return

    val component = pluginContentId?.let { panelComponentStore.getOrCreateComponent(it) }

    // For dragging the panel by its header: the sidebar item shown here + its source slot.
    val draggedItem = getSidebarItemForPanel(panel)
    val sourceSlot = draggedItem?.let { slotForItem(it) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BossTheme.colors.panel)
                .hoverable(interactionSource),
    ) {
        val title = component?.panelInfo?.displayName ?: "Default title" // getPanelTitle(panel)
        val windowId = LocalWindowId.current

        // Plugin update availability for this panel's owning plugin (host-compatible updates only).
        val pluginId = pluginContentId?.let { LocalPanelPluginIdResolver.current(it) }
        val availableUpdates by PluginUpdateRegistry.updates.collectAsState()
        val updateForPlugin = pluginId?.let { availableUpdates[it] }
        val checkForUpdates: (() -> Unit)? =
            if (pluginContentId != null && windowId != null) {
                { MenuActionsHandler.triggerCheckPluginUpdates(windowId, pluginContentId) }
            } else {
                null
            }

        // Tool Evolver menu items, gated via the (RBAC-filtered) MCP registry —
        // no compile-time coupling to the plugin. "Report Issue" shows whenever the
        // plugin is active (evolver_open is ungated). "Open Evolver" shows only when
        // the permission-gated evolver_evolve tool is exposed — i.e. the current
        // user may evolve (holds the permission, or is admin). Both dispatch
        // evolver_open with the right section.
        val registeredMcpTools by McpToolRegistryImpl.tools.collectAsState()
        val menuScope = rememberCoroutineScope()
        val evolverLogger = remember { BossLogger.forComponent("SidePanel") }
        val evolverOpenAvailable =
            pluginId != null &&
                registeredMcpTools.any { it.definition.name == EvolverContract.OPEN_TOOL }
        val evolveAvailable =
            pluginId != null &&
                registeredMcpTools.any { it.definition.name == EvolverContract.EVOLVE_TOOL }
        val dispatchEvolverOpen: (String?, String) -> Unit = { section, failLabel ->
            val id = pluginId
            if (id != null) {
                menuScope.launch {
                    val args =
                        buildJsonObject {
                            put(EvolverContract.ARG_PLUGIN_ID, id)
                            if (section != null) put(EvolverContract.ARG_SECTION, section)
                        }.toString()
                    val result = McpToolRegistryImpl.invoke(EvolverContract.OPEN_TOOL, args)
                    if (result.isError) {
                        evolverLogger.warn(
                            LogCategory.UI,
                            "$failLabel failed",
                            mapOf("pluginId" to id, "error" to result.text),
                        )
                        StatusMessageManager.showMessage("$failLabel failed: ${result.text}", durationMs = 5000)
                    }
                }
                Unit
            }
        }
        val openEvolver: (() -> Unit)? =
            if (evolveAvailable) {
                { dispatchEvolverOpen(null, "Open Evolver") }
            } else {
                null
            }
        val reportIssue: (() -> Unit)? =
            if (evolverOpenAvailable) {
                { dispatchEvolverOpen(EvolverContract.SECTION_ISSUE, "Report Issue") }
            } else {
                null
            }

        // Drag the panel by its header: reorder/move between sidebar slots, or drop on
        // the central area to open it as a main tab. Reuses the sidebar drag system.
        var headerWindowPos by remember { mutableStateOf(Offset.Zero) }
        val headerDragModifier =
            if (draggedItem != null && sourceSlot != null) {
                Modifier
                    .onGloballyPositioned { headerWindowPos = it.positionInWindow() }
                    .pointerInput(draggedItem.id, sourceSlot) {
                        detectDragGestures(
                            onDragStart = { offset -> startDragging(draggedItem, sourceSlot, headerWindowPos + offset) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                updateDragDelta(dragAmount)
                            },
                            onDragEnd = { stopDragging() },
                            onDragCancel = { stopDragging() },
                        )
                    }
            } else {
                Modifier
            }

        BossPanelTopBar(
            title = title,
            isHovered = isHovered,
            onReset =
                pluginContentId?.let { panelId ->
                    {
                        // Reset sandbox health if this panel has a sandbox
                        PanelSandboxRegistry.getSandbox(panelId)?.resetHealth()
                        // Trigger component reset via PanelComponentStore
                        panelComponentStore.resetComponent(panelId)
                    }
                },
            onReloadPlugin =
                pluginContentId?.let { panelId ->
                    windowId?.let { wId ->
                        {
                            MenuActionsHandler.triggerReloadPlugin(wId, panelId)
                        }
                    }
                },
            onOpenAsTab =
                pluginContentId?.let { panelId ->
                    { requestPromoteToTab(panelId) }
                },
            onCheckForUpdates = checkForUpdates,
            onOpenEvolver = openEvolver,
            onReportIssue = reportIssue,
            onMinimize = {
                setPanelVisible(panel, false)
            },
            updateAvailable = updateForPlugin,
            onUpdateClick = checkForUpdates,
            panelId = pluginContentId,
            windowId = windowId,
            dragModifier = headerDragModifier,
        )
        Divider(color = BossTheme.colors.line)

        Box(modifier = Modifier.fillMaxSize()) {
            // Read crash state so a plugin crash triggers full subtree teardown/rebuild.
            // Without this, a composition crash (e.g. NoSuchMethodError) corrupts the
            // subtree and the PluginErrorBoundary can't recompose to show the error UI.
            val crashState =
                pluginContentId
                    ?.let {
                        PanelSandboxRegistry.getSandbox(it)?.pluginId
                    }?.let { PluginCrashRegistry.crashedPlugins[it] }

            // Force recomposition when component instance changes (e.g., after reset)
            // or when crash state changes (allows error boundary to show crash UI)
            key(component, crashState) {
                // Render panel content with optional error boundary wrapping
                RenderPanelContent(
                    component = component,
                    panelId = pluginContentId,
                )
            }
        }
    }
}

/**
 * Renders panel content with optional error boundary wrapping.
 * If the panel has an associated sandbox, wraps content with PluginErrorBoundary
 * to catch errors and show a restart button.
 *
 * `internal` so the panel-host tab type can render the same panel inside a main tab.
 */
@Composable
internal fun RenderPanelContent(
    component: ai.rever.boss.plugin.api.PanelComponentWithUI?,
    panelId: ai.rever.boss.plugin.api.PanelId?,
) {
    if (component == null || panelId == null) return

    // Check if this panel has a sandbox for error boundary wrapping
    val sandbox = PanelSandboxRegistry.getSandbox(panelId)
    if (sandbox != null) {
        val scope = rememberCoroutineScope()
        val logger = remember { BossLogger.forComponent("SidePanel") }
        PluginErrorBoundary(
            pluginId = sandbox.pluginId,
            sandbox = sandbox,
            onRestart = {
                // Restart the sandbox when user clicks restart
                scope.launch {
                    val result = sandbox.restart()
                    if (result.isFailure) {
                        logger.error(
                            LogCategory.UI,
                            "Failed to restart plugin",
                            mapOf(
                                "pluginId" to sandbox.pluginId,
                                "error" to (result.exceptionOrNull()?.message ?: "unknown"),
                            ),
                        )
                        // Show status message to user about failure
                        StatusMessageManager.showMessage(
                            "Failed to restart plugin: ${sandbox.pluginId}",
                            durationMs = 5000,
                        )
                    }
                }
            },
        ) {
            component.Content()
        }
    } else {
        // No sandbox - render directly (backwards compatibility)
        component.Content()
    }
}
