package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.components.plugin.registries.owningPluginId
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

private val logger = BossLogger.forComponent("HomeTools")

/**
 * Reads the live registries and the plugin store, and builds the tool grid.
 *
 * Both registries are backed by `SnapshotStateMap`, so reading them here recomposes on plugin load
 * and unload with no listener plumbing - the same property `NewTabDialog` relies on. That is what
 * makes the grid current: install a plugin and its tile appears without a relaunch.
 */
@Composable
internal fun rememberHomeTools(installedVersionOf: (String) -> String? = { null }): List<HomeTool> {
    val tabRegistry = LocalTabRegistry.current
    val panelRegistry = LocalPanelRegistry.current
    val pluginStates by LocalPluginStates.current?.collectAsState()
        ?: remember { mutableStateOf(emptyMap()) }
    val access = LocalRegistryAccess.current

    // Observed, not read once: this screen is what an empty panel renders, so on a cold start it
    // composes before the store provider exists. Keying the fetch on the provider means the
    // discovery half fills in when the store comes up instead of staying empty until something
    // happens to remount the screen.
    val catalogProvider by HomeCatalogAccess.provider.collectAsState()
    var discoverable by remember { mutableStateOf<List<HomeStorePluginInput>>(emptyList()) }
    LaunchedEffect(catalogProvider) {
        // The provider caches its result for the session, so a remount does not re-fetch. A
        // failure yields an empty list rather than an error - the grid is still everything that is
        // installed, which is already more than the screen this replaces could show.
        discoverable = catalogProvider?.discoverable().orEmpty()
    }

    val tabTypes =
        tabRegistry
            ?.getAllTabTypes()
            ?.map { info ->
                HomeTabTypeInput(
                    typeId = info.typeId.typeId,
                    // Both halves of the registry key, so the handler can match without
                    // reconstructing a TabTypeId - see HomeToolLaunch.OpenTab.
                    typePluginId = info.typeId.pluginId,
                    displayName = info.displayName,
                    icon = info.icon,
                    offeredInNewTab = info.newTabSpec != null,
                    needsInput = info.newTabSpec?.let { !it.opensInstantly() } ?: false,
                    // Recovered from the defining classloader rather than by widening the
                    // registration API - see owningPluginId. Null for host-defined types, which then
                    // group as OTHER rather than being dropped.
                    ownerPluginId = owningPluginId(info),
                )
            }.orEmpty()

    val panels =
        panelRegistry
            ?.getAllPanels()
            ?.map { info ->
                // The sidebar item's icon and label, not PanelInfo's own, so a panel's tile
                // matches the icon the user already recognises from the sidebar rail. The rail is
                // built from `getDefaultSidebarMap()` and renders `SidebarItem.icon` / `.label`.
                //
                // `PanelInfo.sidebarItem` is a *default* interface method returning
                // `SidebarItem(id, icon, displayName)`, so for a plugin that does not override it
                // this is the same two values. For one that does, PanelInfo.icon is the wrong
                // answer and only this is right.
                val sidebarItem = info.sidebarItemOrNull()
                HomePanelInput(
                    panelId = info.id,
                    label = sidebarItem?.label?.takeIf { it.isNotBlank() } ?: info.displayName,
                    icon = sidebarItem?.icon ?: info.icon,
                    ownerPluginId = owningPluginId(info) ?: info.id.pluginId.takeIf { it.isNotBlank() },
                )
            }.orEmpty()

    val installedPluginIds =
        PluginDependencyResolution.installedAndOnDisk(
            states = pluginStates,
            // Both predicates supplied, identical to `MissingDependencyReporter.forManager`, so
            // the grid and the dependency prompt share one definition of "installed" - which
            // AGENTS.md records as having broken that feature once when two callers disagreed.
            //
            // Passing `exists = { true }` and omitting `isIncompatible` reduced this to
            // `LOADED || true`, i.e. every entry in `pluginStates` regardless of state. A plugin
            // whose install failed as binary-incompatible keeps a DISABLED entry while the
            // installer deletes its jar, so it counted as installed, its store row was filtered
            // out, and - having registered no tab type or panel - it vanished from the grid with
            // no way to retry the install from the surface that exists to offer it.
            exists = { File(it).isFile },
            isIncompatible = { PluginCrashRegistry.isIncompatible(it) },
        )

    return remember(tabTypes, panels, discoverable, installedPluginIds, access) {
        HomeToolCatalog.build(
            tabTypes = tabTypes,
            panels = panels,
            storeCatalogue = discoverable,
            installedPluginIds = installedPluginIds,
            access = access,
            installedVersionOf = installedVersionOf,
        )
    }
}

/**
 * A panel's [SidebarItem], or null if reading it fails.
 *
 * `sidebarItem` is a default interface method a plugin may override, so this is plugin code and
 * gets the same treatment `PanelMenuRegistryImpl` gives `items()`: a throwing implementation logs
 * and falls back to `PanelInfo`'s own icon and name rather than taking down the home screen.
 */
private fun PanelInfo.sidebarItemOrNull(): SidebarItem? =
    runCatching { sidebarItem }
        .onFailure { error ->
            logger.warn(
                LogCategory.UI,
                "Reading a panel's sidebar item failed; falling back to its PanelInfo icon",
                mapOf("panelId" to id.panelId),
                error = error,
            )
        }.getOrNull()

/**
 * Mirrors `NewTabDialog.needsNoInput()`: blank label, blank placeholder and optional input means
 * the type opens with no input step.
 *
 * A one-line private copy rather than widening the dialog's `internal` extension, because both
 * answer the same question about the same api type and that api ships in an external artifact. See
 * the dialog's KDoc for why the heuristic is acceptable and what would retire it.
 */
private fun NewTabSpec.opensInstantly(): Boolean = inputOptional && inputLabel.isBlank() && inputPlaceholder.isBlank()
