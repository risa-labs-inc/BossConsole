package ai.rever.boss.kernel.ui

import ai.rever.boss.components.plugin.remote.RemotePanelComponent
import ai.rever.boss.components.plugin.remote.RemoteTabComponent
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns an authenticated, accepted remote-UI registration into something the user can actually
 * see, using exactly the infrastructure a real (in-process) plugin uses — [PanelRegistry] /
 * [TabRegistry] to declare the surface, [RemotePanelComponent] / [RemoteTabComponent] (built and
 * tested by #48/#50/#51, made safe by #53) to render it (BossConsole#54).
 *
 * **Why this class exists at all.** [PanelRegistry] and [TabRegistry] are created fresh per
 * window (`BossWindow`/`rememberBossAppState`), so there is no single instance to register a
 * remote surface into — and touching that per-window creation path directly would mean this
 * always-desktop-only class becoming reachable from code that must still compile without it (the
 * `ai.rever.boss.kernel.*` / Windows-ARM64 boundary `WindowsArm64ImportPredicateTest` guards).
 * Resolving everything through the *already-existing* process-wide registries
 * ([PanelComponentStoreRegistry], [SplitViewStateRegistry]) at the moment a surface is placed —
 * rather than caching anything about a window up front — sidesteps that boundary entirely: this
 * class is only ever constructed and called from other desktop-only, kernel-adjacent code
 * ([ai.rever.boss.kernel.services.PluginUIServiceBridge]), never from a file that must build
 * without it.
 *
 * **Scope, deliberately:** one surface is placed into the one window [resolveWindowId] names at
 * the moment placement runs (normally the focused window). Nothing here replicates a surface into
 * every open window the way an installed plugin's panel would — doing that from outside any
 * window's own composition would need the reflective bridge above, for a case (multiple windows
 * open, an out-of-process plugin's surface, at the same moment) narrow enough that the added
 * complexity is not justified by #54's own goal: making an accepted registration reachable at
 * all, which it currently is not, anywhere.
 */
class RemoteUiPlacement(
    private val registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    /** Test seam — production resolves the real focused/actionable window. */
    private val resolveWindowId: () -> String? = WindowFocusManager::resolveActionableWindowId,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private val logger = BossLogger.forComponent("RemoteUiPlacement")

    private data class PlacedPanel(
        val panelId: PanelId,
        val panelRegistry: PanelRegistry,
    )

    private data class PlacedTab(
        val typeId: TabTypeId,
        val tabRegistry: TabRegistry,
    )

    private val placedPanels = ConcurrentHashMap<String, PlacedPanel>()
    private val placedTabs = ConcurrentHashMap<String, PlacedTab>()
    private val placementJobs = ConcurrentHashMap<String, Job>()

    /**
     * Place [surfaceId], once [RemoteUiSurfaceRegistry.register] has accepted it.
     *
     * Fire-and-forget on purpose: `RegisterUI`'s response is "the registration is accepted," not
     * "the UI now shows it," and a caller (`PluginUIServiceBridge`) must not hold that RPC open for
     * however long placement (which can retry — see [placeWithRetry]) takes. Idempotent per
     * [surfaceId] via [placedPanels] / [placedTabs] — a duplicate call (a reclaimed registration
     * racing a prior one) is a no-op past the first.
     */
    fun place(surfaceId: String) {
        val surface = registry.surfaceOf(surfaceId) ?: return
        val descriptor = surface.descriptor
        val job =
            scope.launch {
                placeWithRetry(surfaceId, descriptor, surface.processId)
            }
        placementJobs[surfaceId] = job
        job.invokeOnCompletion { placementJobs.remove(surfaceId, job) }
    }

    /**
     * Remove whatever [place] put on screen for [surfaceId], once
     * [RemoteUiSurfaceRegistry.unregister] has actually removed it.
     *
     * Deliberately not called on a mere disconnect (a dead `StreamUI`): [RemoteUiSurfaceRegistry]'s
     * own contract for that is "stays attached, reads `connected == false`" (see
     * [RemoteUiSurfaceRegistry.closeStream]) — the placed panel/tab is what shows that, so removing
     * it here would fight the very "disconnected, not frozen" behaviour the registry exists to
     * provide, and would drop the panel a respawn is meant to reconnect into. Only a graceful
     * `UnregisterUI` — this surface will never come back under this id — means the placement itself
     * should go too.
     */
    fun remove(surfaceId: String) {
        placementJobs.remove(surfaceId)?.cancel()
        placedPanels.remove(surfaceId)?.let { it.panelRegistry.unregisterPanel(it.panelId) }
        placedTabs.remove(surfaceId)?.let {
            // unregisterTabType fires this window's own unregister-listener, which closes any tab
            // of this type exactly the way a plugin unload does — nothing else to do for the
            // "already open" case.
            it.tabRegistry.unregisterTabType(it.typeId)
        }
    }

    /**
     * Retry a bounded number of times, spaced by [retryDelayMs], so a plugin that registers before
     * any window has opened (a real, if narrow, startup race) is not silently dropped (#54: "do not
     * silently discard a valid accepted registration") — while still bounded, because a window that
     * genuinely never appears is not a transient condition retrying will fix.
     */
    private suspend fun placeWithRetry(
        surfaceId: String,
        descriptor: RemoteUiSurfaceDescriptor,
        processId: String,
    ) {
        repeat(maxAttempts) { attempt ->
            if (tryPlace(surfaceId, descriptor, processId)) return
            if (attempt < maxAttempts - 1) delay(retryDelayMs)
        }
        logger.warn(
            LogCategory.UI,
            "Could not place remote surface - no window became available",
            mapOf("surfaceId" to surfaceId, "attempts" to maxAttempts),
        )
    }

    /** @return true if this attempt is final (placed, or given up on this surface for good). */
    private fun tryPlace(
        surfaceId: String,
        descriptor: RemoteUiSurfaceDescriptor,
        processId: String,
    ): Boolean {
        val windowId = resolveWindowId() ?: return false
        val splitViewState = SplitViewStateRegistry.getState(windowId) ?: return false
        val panelRegistry = PanelComponentStoreRegistry.getStore(windowId)?.registry ?: return false

        return when (descriptor.surfaceType) {
            "panel" -> {
                placePanel(surfaceId, descriptor, processId, panelRegistry)
                true
            }

            "tab" -> {
                placeTab(surfaceId, descriptor, processId, splitViewState)
            }

            else -> {
                logger.warn(
                    LogCategory.UI,
                    "Unknown remote surface_type - not placed",
                    mapOf("surfaceId" to surfaceId, "surfaceType" to descriptor.surfaceType),
                )
                true // not a window-availability problem; retrying changes nothing
            }
        }
    }

    private fun placePanel(
        surfaceId: String,
        descriptor: RemoteUiSurfaceDescriptor,
        processId: String,
        panelRegistry: PanelRegistry,
    ) {
        val id = PanelId(surfaceId, DEFAULT_PANEL_ORDER, REMOTE_PLUGIN_ID)
        if (placedPanels.putIfAbsent(surfaceId, PlacedPanel(id, panelRegistry)) != null) return

        val info =
            RemotePanelInfo(
                id = id,
                displayName = descriptor.displayName.ifBlank { DEFAULT_DISPLAY_NAME },
                icon = REMOTE_ICON,
                // Unknown/invalid default_slot has a deterministic, already-tested fallback here —
                // the same one a corrupted or forward-versioned sidebar setting falls back to.
                defaultSlotPosition = SidebarVisibilitySettings.panelFor(descriptor.defaultSlot),
            )
        panelRegistry.registerPanel(info) { ctx, panelInfo ->
            // Read fresh rather than closing over the descriptor/processId this call started
            // with: the factory runs whenever the user opens the panel, which can be long after
            // placement, and a respawn may have changed processId by then.
            val current = registry.surfaceOf(surfaceId)
            val panel =
                RemotePanelComponent(
                    panelId = surfaceId,
                    displayName = current?.descriptor?.displayName ?: panelInfo.displayName,
                    processId = current?.processId ?: processId,
                    registry = registry,
                )
            RemotePanelHostComponent(panelInfo, ctx, panel)
        }
    }

    /** @return true whether or not this succeeded — the only retryable failure is "no window yet." */
    private fun placeTab(
        surfaceId: String,
        descriptor: RemoteUiSurfaceDescriptor,
        processId: String,
        splitViewState: SplitViewState,
    ): Boolean {
        val tabsComponent = splitViewState.getActiveTabsComponent() ?: return true

        val typeId = TabTypeId(surfaceId, REMOTE_PLUGIN_ID)
        val displayName = descriptor.displayName.ifBlank { DEFAULT_DISPLAY_NAME }
        if (placedTabs.putIfAbsent(surfaceId, PlacedTab(typeId, splitViewState.tabRegistry)) == null) {
            val typeInfo = RemoteTabTypeInfo(typeId, displayName, REMOTE_ICON)
            splitViewState.tabRegistry.registerTabType(typeInfo) { config, ctx ->
                val current = registry.surfaceOf(surfaceId)
                val tab =
                    RemoteTabComponent(
                        tabId = surfaceId,
                        displayName = current?.descriptor?.displayName ?: displayName,
                        processId = current?.processId ?: processId,
                        registry = registry,
                    )
                RemoteTabHostComponent(config, typeInfo, ctx, tab)
            }
            val index = tabsComponent.addTab(RemoteTabInfo(surfaceId, typeId, displayName, REMOTE_ICON))
            if (index >= 0) tabsComponent.selectTab(index)
        }
        return true
    }

    private class RemotePanelInfo(
        override val id: PanelId,
        override val displayName: String,
        override val icon: ImageVector,
        override val defaultSlotPosition: Panel,
    ) : PanelInfo

    private class RemotePanelHostComponent(
        override val panelInfo: PanelInfo,
        ctx: ComponentContext,
        private val panel: RemotePanelComponent,
    ) : PanelComponentWithUI,
        ComponentContext by ctx {
        init {
            panel.attach()
            lifecycle.doOnDestroy { panel.dispose() }
        }

        @Composable
        override fun Content() {
            panel.Content()
        }
    }

    private class RemoteTabTypeInfo(
        override val typeId: TabTypeId,
        override val displayName: String,
        override val icon: ImageVector,
    ) : TabTypeInfo

    private class RemoteTabInfo(
        surfaceId: String,
        override val typeId: TabTypeId,
        override val title: String,
        override val icon: ImageVector,
    ) : TabInfo {
        override val id: String = "remote-tab:$surfaceId"
    }

    private class RemoteTabHostComponent(
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
        ctx: ComponentContext,
        private val tab: RemoteTabComponent,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        init {
            tab.attach()
            lifecycle.doOnDestroy { tab.dispose() }
        }

        @Composable
        override fun Content() {
            tab.Content()
        }
    }

    companion object {
        /** Distinguishes a remote surface's [PanelId]/[TabTypeId] from a real installed plugin's. */
        private const val REMOTE_PLUGIN_ID = "boss.remote"
        private const val DEFAULT_PANEL_ORDER = 0
        private const val DEFAULT_DISPLAY_NAME = "Remote Surface"
        private const val DEFAULT_MAX_ATTEMPTS = 10
        private const val DEFAULT_RETRY_DELAY_MS = 500L
        private val REMOTE_ICON: ImageVector = Icons.Outlined.Extension

        /** The host-wide placer. Tests build their own, the way [RemoteUiSurfaceRegistry] does. */
        val shared = RemoteUiPlacement()
    }
}
