package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.mcp.EvolverContract
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelMenuContribution
import ai.rever.boss.plugin.api.PanelMenuItem
import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Upgrade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything the panel menu can do to one plugin panel, resolved from the host's registries.
 *
 * A null callback means "not offered here": the panel has no resolvable window, the plugin exposes
 * no evolver tools to this user, and so on. Every consumer treats null as "leave the row out", so a
 * row named as an imperative never appears while silently doing nothing.
 */
data class PanelMenuActions(
    val pluginId: String? = null,
    val buildInfo: PluginBuildInfo? = null,
    val updateAvailable: AvailablePluginUpdate? = null,
    val installStoreVersion: (() -> Unit)? = null,
    val reloadPanel: (() -> Unit)? = null,
    val checkForUpdates: (() -> Unit)? = null,
    val openEvolver: (() -> Unit)? = null,
    val reportIssue: (() -> Unit)? = null,
    val uninstallPlugin: (() -> Unit)? = null,
    val uninstallEnabled: Boolean = false,
)

/**
 * The actions available for [panelId], derived from the plugin registries and the current user's
 * RBAC snapshot.
 *
 * Not a `remember`, deliberately and by name: the callbacks close over this composition's panel and
 * window, so they are rebuilt each pass and nothing downstream should key on their identity.
 *
 * Lives here rather than in the panel header because the header is no longer the only place that
 * offers them: the sidebar rail's icon is the same plugin, and right-clicking it has to reach the
 * same menu whether or not the panel is currently showing. Resolving it in one composable is what
 * keeps the two from drifting - each registry is collected rather than read once, so a hot reload,
 * an update landing or a role change re-derives both at the same moment.
 *
 * Everything is left out when there is no panel to act on, or no window to route through:
 * `LocalWindowId` is null outside a tracked window, and every one of these actions is addressed to
 * one.
 */
@Composable
fun panelMenuActions(panelId: PanelId?): PanelMenuActions {
    val windowId = LocalWindowId.current
    val pluginId = panelId?.let { LocalPanelPluginIdResolver.current(it) }

    // Plugin update availability for this panel's owning plugin (host-compatible updates only), and
    // which build it is running. Collected, so the rows track a hot reload or an update landing.
    val availableUpdates by PluginUpdateRegistry.updates.collectAsState()
    val pluginBuilds by PluginBuildRegistry.builds.collectAsState()

    // Uninstall is offered for every plugin panel and disabled for the ones the manager refuses to
    // unload, so a system plugin shows why the action is unavailable instead of hiding it.
    val uninstallable = LocalPluginUninstallable.current
    val evolver = evolverActions(pluginId)
    val buildInfo = pluginId?.let { pluginBuilds[it] }

    if (panelId == null || windowId == null) {
        return PanelMenuActions(pluginId = pluginId, buildInfo = buildInfo)
    }

    return PanelMenuActions(
        pluginId = pluginId,
        buildInfo = buildInfo,
        updateAvailable = pluginId?.let { availableUpdates[it] },
        installStoreVersion =
            if (buildInfo?.isTagged == true) {
                { MenuActionsHandler.triggerInstallStoreVersion(windowId, panelId) }
            } else {
                null
            },
        reloadPanel = {
            // Clear the sandbox's consecutive-error count first: a reload replaces the code that was
            // failing, so carrying its error tally over would leave a freshly loaded plugin one
            // fault away from being quarantined.
            PanelSandboxRegistry.getSandbox(panelId)?.resetHealth()
            MenuActionsHandler.triggerReloadPlugin(windowId, panelId)
        },
        checkForUpdates = { MenuActionsHandler.triggerCheckPluginUpdates(windowId, panelId) },
        openEvolver = evolver.openEvolver,
        reportIssue = evolver.reportIssue,
        uninstallPlugin =
            if (pluginId != null) {
                { MenuActionsHandler.triggerUninstallPlugin(windowId, panelId) }
            } else {
                null
            },
        uninstallEnabled = pluginId != null && uninstallable(pluginId),
    )
}

/** The two Tool Evolver rows, which are gated differently from everything else. */
private data class EvolverActions(
    val openEvolver: (() -> Unit)? = null,
    val reportIssue: (() -> Unit)? = null,
)

/**
 * The evolver rows for [pluginId], gated via the (RBAC-filtered) MCP registry, with no compile-time
 * coupling to the plugin.
 *
 * "Report Issue" shows whenever the plugin is active (evolver_open is ungated). "Open Evolver" shows
 * only when the permission-gated evolver_evolve tool is exposed, i.e. the current user may evolve
 * (holds the permission, or is admin). Both dispatch evolver_open with the right section.
 */
@Composable
private fun evolverActions(pluginId: String?): EvolverActions {
    val registeredMcpTools by McpToolRegistryImpl.tools.collectAsState()
    val menuScope = rememberCoroutineScope()
    val logger = remember { BossLogger.forComponent("PanelMenu") }

    if (pluginId == null) return EvolverActions()

    val dispatch: (String?, String) -> Unit = { section, failLabel ->
        menuScope.launch {
            val args =
                buildJsonObject {
                    put(EvolverContract.ARG_PLUGIN_ID, pluginId)
                    if (section != null) put(EvolverContract.ARG_SECTION, section)
                }.toString()
            val result = McpToolRegistryImpl.invoke(EvolverContract.OPEN_TOOL, args)
            if (result.isError) {
                logger.warn(
                    LogCategory.UI,
                    "$failLabel failed",
                    mapOf("pluginId" to pluginId, "error" to result.text),
                )
                StatusMessageManager.showMessage("$failLabel failed: ${result.text}", durationMs = 5000)
            }
        }
    }

    val exposes = { tool: String -> registeredMcpTools.any { it.definition.name == tool } }
    return EvolverActions(
        openEvolver = if (exposes(EvolverContract.EVOLVE_TOOL)) ({ dispatch(null, "Open Evolver") }) else null,
        reportIssue =
            if (exposes(EvolverContract.OPEN_TOOL)) {
                { dispatch(EvolverContract.SECTION_ISSUE, "Report Issue") }
            } else {
                null
            },
    )
}

/**
 * One menu definition for a plugin panel, shared by the header's "…" kebab, the header's right-click
 * menu and the sidebar rail icon's right-click menu, so none of the three can offer a different set.
 *
 * Plugin-contributed items (PanelMenuRegistry) render between the built-ins and [onMinimize]; the
 * registry map and the RBAC snapshot trigger a re-query, so they track plugin lifecycle and role
 * changes. Contributions change their item set by re-registering (items() must stay cheap - see
 * PanelMenuContribution).
 *
 * [onOpenAsTab] and [onMinimize] are the two rows that depend on where the menu was opened rather
 * than on the plugin, so they are passed in: the rail has no panel on screen to minimize.
 * [trailingItems] is for rows that belong to the surface rather than to the panel, e.g. the rail's
 * "Sidebar settings".
 */
@Composable
fun panelMenuItems(
    panelId: PanelId?,
    actions: PanelMenuActions,
    onOpenAsTab: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    trailingItems: List<ContextMenuItem> = emptyList(),
): List<ContextMenuItem> {
    // The window is read here rather than taken as a parameter: [panelMenuActions] reads the same
    // local for the built-in rows, and two sources for one window is a way for the built-ins and the
    // contributed rows to end up addressed at different windows with nothing to catch it.
    val windowId = LocalWindowId.current
    val contributions by PanelMenuRegistryImpl.contributions.collectAsState()
    val access by PanelMenuRegistryImpl.access.collectAsState()
    val pluginEntries =
        if (panelId != null) {
            remember(panelId, contributions, access) {
                PanelMenuRegistryImpl.itemsFor(panelId)
            }
        } else {
            emptyList()
        }

    return buildList {
        addBuildRows(actions)
        // "Reload Panel" is the user-facing name for what is really a reload of the owning plugin:
        // the jar is unloaded and re-read, and every window's slots for it are reset. Named for the
        // thing the user is pointing at, since this menu belongs to one panel.
        actions.reloadPanel?.let { add(row("Reload Panel", Icons.Outlined.Refresh, it)) }
        actions.checkForUpdates?.let { add(row("Check for Updates", Icons.Outlined.Upgrade, it)) }
        actions.openEvolver?.let { add(row("Open Evolver", Icons.Outlined.MonitorHeart, it)) }
        actions.reportIssue?.let { add(row("Report Issue", Icons.Outlined.BugReport, it)) }
        onOpenAsTab?.let { add(row("Open as Tab", Icons.Outlined.Tab, it)) }
        // Shown for every plugin panel, disabled for the ones the manager refuses to unload (system
        // plugins), so the action's absence is never mistaken for the feature missing.
        actions.uninstallPlugin?.let {
            add(row("Uninstall Plugin", Icons.Outlined.DeleteOutline, it).copy(enabled = actions.uninstallEnabled))
        }
        addContributedRows(pluginEntries, panelId, windowId)
        onMinimize?.let { add(row("Minimize", Icons.Outlined.Remove, it)) }
        if (trailingItems.isNotEmpty()) {
            // Only a separator when there is something to separate, and never a second one: a panel
            // that offers no actions of its own would otherwise open its menu with a divider as the
            // first row, and one that ends in a divider would show two rules in a row.
            if (isNotEmpty() && !last().isDivider) add(ContextMenuItem(isDivider = true))
            addAll(trailingItems)
        }
    }
}

private fun row(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
) = ContextMenuItem(text = text, icon = icon, onClick = onClick)

/**
 * Which build is running, first and clickable, for a plugin that is not on the released version.
 *
 * The version has to be the item's TEXT rather than a badge widget: with no trailing icon this menu
 * is native-representable, so on macOS it renders as a real NSMenu, whose items carry a label, an
 * enabled flag and a rasterised leading icon - but nothing that is a widget, which is what a badge
 * would need.
 *
 * Both rows are gated on the action existing, not merely on the build being tagged:
 * [PanelMenuActions.installStoreVersion] is null whenever the panel has no resolvable window, and a
 * row named as an imperative that silently does nothing is a worse failure than no row at all. The
 * tag itself is inert in exactly that case.
 */
private fun MutableList<ContextMenuItem>.addBuildRows(actions: PanelMenuActions) {
    val installStoreVersion = actions.installStoreVersion ?: return
    val taggedBuild = actions.buildInfo?.takeIf { it.isTagged } ?: return

    add(row("Version ${taggedBuild.displayVersion}", Icons.Outlined.Info, installStoreVersion))
    // The way back to the released build, named as the action it is. The version row above already
    // carries it, but that row reads as a statement of fact, so the only discoverable route was
    // clicking the tag - and the tag is a 9sp pill that is the first thing to run out of room once
    // the panel narrows.
    add(row("Install Store Version", Icons.Outlined.CloudDownload, installStoreVersion))
    add(ContextMenuItem(isDivider = true))
}

/**
 * The plugin's own contributed rows, after the built-ins and behind a divider.
 *
 * The disabled ones are dropped BEFORE the divider is decided. Deciding on `entries` and skipping
 * inside the loop leaves a contribution whose items are all disabled adding a divider with nothing
 * under it - and on the rail that divider then satisfies the "is there anything to separate" guard
 * for the trailing rows, so the menu shows two rules in a row and then "Sidebar settings".
 */
private fun MutableList<ContextMenuItem>.addContributedRows(
    entries: List<Pair<PanelMenuContribution, PanelMenuItem>>,
    panelId: PanelId?,
    windowId: String?,
) {
    if (panelId == null) return
    val visible = entries.filter { (_, item) -> item.enabled }
    if (visible.isEmpty()) return
    add(ContextMenuItem(isDivider = true))
    for ((contribution, item) in visible) {
        add(
            row(item.label, item.icon, {
                PanelMenuRegistryImpl.onItemClick(contribution, panelId, item.id, windowId)
            }),
        )
    }
}
