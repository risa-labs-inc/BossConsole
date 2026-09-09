package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.components.plugin.RetiredPluginIds
import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.api.PanelId
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A tab type the [ai.rever.boss.plugin.api.TabRegistry] currently holds, flattened to plain data.
 *
 * Flattened rather than passing `TabTypeInfo` through, so [HomeToolCatalog.build] is a pure
 * function over values and can be tested without a plugin classloader, a `ComponentContext` or a
 * `NewTabContext`. The adapter that reads the live registry lives in the composable.
 */
data class HomeTabTypeInput(
    val typeId: String,
    val displayName: String,
    /** The type's own icon, which is what the tab bar draws. */
    val icon: ImageVector,
    /** `newTabSpec != null`: the plugin opted this type into being offered. */
    val offeredInNewTab: Boolean,
    /** The `pluginId` half of the registry's `TabTypeId`; see [HomeToolLaunch.OpenTab]. */
    val typePluginId: String,
    /** The inverse of `NewTabSpec.needsNoInput()`. */
    val needsInput: Boolean,
    val ownerPluginId: String?,
)

/**
 * A panel the panel registry currently holds.
 *
 * [icon] and [label] come from the panel's `SidebarItem`, not from `PanelInfo` directly, so a tile
 * shows the icon the user already recognises from the sidebar rail - which is built from
 * `SidebarItem` too. They coincide unless a plugin overrides `PanelInfo.sidebarItem`, and then
 * only the sidebar values are right. See `sidebarItemOrNull`.
 *
 * No RBAC flags, deliberately: `PanelInfo` declares none, and the gate that matters happens a
 * level up - `DynamicPluginManager` hides a whole plugin the user cannot access, so it never
 * registers its panels in the first place. A per-panel filter here could never fire and would only
 * suggest a protection that does not exist at this level.
 */
data class HomePanelInput(
    val panelId: PanelId,
    val label: String,
    val icon: ImageVector,
    val ownerPluginId: String?,
)

/** A store row, reduced to the questions the grid actually asks of it. */
data class HomeStorePluginInput(
    val pluginId: String,
    val displayName: String,
    /**
     * `plugins.icon_url`, passed through verbatim.
     *
     * The only icon source for a plugin that has registered nothing. Commonly blank, in which case
     * the tile falls back to initials derived from [displayName] - see [HomeToolIcon.FromStore].
     */
    val iconUrl: String,
    val requiresAdmin: Boolean,
    /**
     * Whether this row can run on this build, per the updater's own gate over `minBossVersion` /
     * `minApiVersion` / `minIpcVersion`.
     *
     * Decided by the caller rather than here, because the comparison needs the host's versions and
     * the existing gate already owns that logic. Offering an install that is certain to fail is
     * worse than not mentioning the plugin.
     */
    val isCompatible: Boolean,
    /**
     * `PluginType.SERVICE`, which is not user-installable for the same reason the wizard drops it:
     * loading one as a regular plugin fails `BinaryCompatibilityValidator` with a cross-classloader
     * `IllegalAccessError`.
     */
    val isService: Boolean,
)

/**
 * Builds the home screen's tool grid from what is registered, what is installed and what the store
 * offers.
 *
 * **Why this is a pure function.** The screen it feeds used to hold a hardcoded list of twelve
 * cards, which is why nothing shipped after it was written ever appeared - Arcade, Flow, Jupyter,
 * Kubernetes, Docker and twenty more were invisible. Deriving the list is the fix, and deriving it
 * *here*, over plain values, is what lets the rules below be tested: every one of them is a case
 * where getting it wrong is silent rather than loud.
 *
 * Nothing here consults a table keyed by plugin id. Every label and icon comes from what the
 * plugin registered or from its store row, so a plugin published after this build is presented
 * from its own data rather than falling into an "unknown" bucket.
 */
object HomeToolCatalog {
    /**
     * Host actions, in the order they appear. Deliberately short: these are the things that are
     * always available and have nowhere else to live, not a second copy of the menu bar. The old
     * screen listed twelve, four of which were duplicates of its own Developer Tools row.
     */
    private val HOST_ACTIONS =
        listOf(
            HomeHostAction.NEW_TERMINAL,
            HomeHostAction.OPEN_FILE,
            HomeHostAction.OPEN_PROJECT,
            HomeHostAction.NEW_PROJECT,
            HomeHostAction.NEW_TAB,
            HomeHostAction.NEW_WINDOW,
            HomeHostAction.SEARCH,
            HomeHostAction.SETTINGS,
        )

    fun build(
        tabTypes: List<HomeTabTypeInput>,
        panels: List<HomePanelInput>,
        storeCatalogue: List<HomeStorePluginInput>,
        installedPluginIds: Set<String>,
        access: RegistryAccess,
        installedVersionOf: (String) -> String? = { null },
    ): List<HomeTool> {
        val hostTools = HOST_ACTIONS.map(::hostTool)

        // A tab type present in the registry belongs to a plugin that is loaded: registration
        // happens on load and is undone on unload. RBAC needs no second check either, because
        // DynamicPluginManager hides a whole plugin the user cannot access, so it never registers
        // anything. Presence is therefore sufficient for "ready".
        val tabTools =
            tabTypes
                .filter { it.offeredInNewTab }
                .map { type ->
                    HomeTool(
                        // Both halves of the registry key in the id, so two plugins registering
                        // the same typeId string keep two tiles. The same collision this PR fixed
                        // on the event side by carrying the pair; deduping on typeId alone would
                        // silently drop one of them here.
                        id = "tab:${type.typePluginId}:${type.typeId}",
                        label = type.displayName,
                        icon = HomeToolIcon.Vector(type.icon),
                        launch = HomeToolLaunch.OpenTab(type.typeId, type.typePluginId, type.needsInput),
                        pluginId = type.ownerPluginId,
                    )
                }

        // Panels need no RBAC filter here; see HomePanelInput for why the gate is a level up.
        val panelTools =
            panels.map { panel ->
                HomeTool(
                    // Both halves, for the same reason as the tab ids above: `PanelId` is also a
                    // pair, and the panel registry is keyed on the whole of it.
                    id = "panel:${panel.panelId.pluginId}:${panel.panelId.panelId}",
                    label = panel.label,
                    icon = HomeToolIcon.Vector(panel.icon),
                    launch = HomeToolLaunch.OpenPanel(panel.panelId),
                    pluginId = panel.ownerPluginId,
                )
            }

        val ready = (tabTools + panelTools).distinctBy { it.id }
        // Dedupe the discovery half against the ready half by PLUGIN, not by tool id: a plugin
        // already contributing a tab or panel must not also appear as something to install, which
        // is what a naive id comparison would allow (its tool id is "tab:arcade", its store row's
        // is its plugin id).
        val representedPlugins = ready.mapNotNull { it.pluginId }.toSet() + installedPluginIds

        val installTools =
            storeCatalogue
                .asSequence()
                .filterNot { it.pluginId in representedPlugins }
                // Never offer the api plugin or the microkernel runtime. The api plugin's install
                // is an unload-all / swap / reload-all hot swap, which must not start from a tile
                // in a grid; the runtime is refused by loadPlugin outright.
                .filterNot { it.pluginId in PluginDependencyResolution.NOT_USER_INSTALLABLE }
                // Nor a plugin another plugin has taken over. Unlisting the store row is a manual
                // action outside CI, so until it happens the store still returns it - and a user
                // could install it, have it swept away at the next launch, and install it again.
                // The floor is the sweep's own: a fresh install whose replacement predates the
                // absorbing release would otherwise be offered neither half.
                .filterNot { RetiredPluginIds.hiddenFromOffers(it.pluginId, installedVersionOf) }
                .filterNot { it.isService }
                .filter { it.isCompatible }
                // A plugin the user has no access to is hidden, not greyed. The host already hides
                // whole plugins for lack of access, and advertising admin-only tooling to a
                // non-admin would leak that it exists.
                .filter { access.permits(it.requiresAdmin, emptySet()) }
                .filter { it.pluginId.isNotBlank() }
                .distinctBy { it.pluginId }
                .map { row ->
                    val label = row.displayName.ifBlank { row.pluginId }
                    HomeTool(
                        id = row.pluginId,
                        label = label,
                        // Straight from the store row. Blank resolves to initials at render time,
                        // so a populated icon_url starts appearing with no client change.
                        icon = HomeToolIcon.FromStore(row.iconUrl, initialsFor(label)),
                        launch = HomeToolLaunch.Install(row.pluginId),
                        pluginId = row.pluginId,
                    )
                }.toList()

        // Host actions lead: they are the ones that work with no plugins at all, which is exactly
        // the state a first-run user is in. Then what is installed, then what could be, each
        // alphabetically - there is no category data to order by.
        return hostTools +
            ready.sortedBy { it.label.lowercase() } +
            installTools.sortedBy { it.label.lowercase() }
    }

    private fun hostTool(action: HomeHostAction): HomeTool =
        HomeTool(
            id = "host:${action.name}",
            label =
                when (action) {
                    HomeHostAction.NEW_TAB -> "New Tab"
                    HomeHostAction.NEW_TERMINAL -> "New Terminal"
                    HomeHostAction.NEW_WINDOW -> "New Window"
                    HomeHostAction.OPEN_FILE -> "Open File"
                    HomeHostAction.OPEN_PROJECT -> "Open Project"
                    HomeHostAction.NEW_PROJECT -> "New Project"
                    HomeHostAction.SETTINGS -> "Settings"
                    HomeHostAction.SEARCH -> "Search"
                },
            icon = HomeToolIcon.Vector(HomeHostActionIcons.iconFor(action)),
            launch = HomeToolLaunch.HostAction(action),
        )
}
