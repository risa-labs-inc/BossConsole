package ai.rever.boss.components.settings.search

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.home.LocalPanelRegistry
import ai.rever.boss.components.plugin.resolveRegisteredPanelId
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("SettingsSearchNavigation")

/**
 * Drop the signposts whose panel no window can currently open.
 *
 * `SettingsContent` already states this invariant for plugin pages - "a page the user cannot see is
 * not in `pluginPages`, so it is not searchable either" - and a signpost is the one entry type that
 * could quietly break it, because it lives in the hand-declared index rather than being merged in
 * from a registry at query time.
 *
 * **The global search enforces the same rule by a different route**, because it cannot reach a
 * `PanelRegistry` from commonMain: `GlobalSearchService.searchSettings` drops a signpost whose
 * panel id is not among the searching window's registered tools. That is not a second opinion but
 * a closer one - `activatePlugin` matches the very same list - so both surfaces agree that a
 * signpost is offered only when picking it does something. This used to be the one place they
 * disagreed, and the PR that added the global search argued the opposite in its own description.
 *
 * **A panel registration answers every reason at once.** A plugin that was never installed, that
 * the user switched off, that was rejected as binary-incompatible, or that is hidden because the
 * user lacks its permission all register no panel - so none of those users is offered a row whose
 * click could do nothing but raise an empty window at them. That is what the removed
 * `Settings > AI Providers` section used `PluginSettingsUnavailableNotice` to tell apart; with no
 * section left to render a notice into, not offering the row is the honest replacement.
 *
 * The cost is deliberate and worth stating: someone who has never installed Secret Manager now
 * finds nothing for "anthropic" rather than an offer to install it. Search follows the same rule as
 * the rest of the window, and the install offer still lives in the Toolbox.
 *
 * Pure, taking a predicate rather than the registry, so the rule for "is this panel reachable"
 * stays in [resolveRegisteredPanelId] alone - the helper that exists precisely so `defaultOrder`
 * mismatches are not re-derived per caller.
 */
internal fun List<SettingsSearchEntry>.withoutUnreachableSignposts(isReachable: (PanelId) -> Boolean) =
    filter { entry -> entry.panel?.let(isReachable) ?: true }

/**
 * [withoutUnreachableSignposts] against the window's live panel registry.
 *
 * Reading `getAllPanels()` in the composable body rather than inside the `remember` is the point:
 * it is a `SnapshotStateMap` read, so this re-derives when a plugin registers or unregisters a
 * panel with no listener plumbing - the same property `rememberHomeTools` relies on. Plugin startup
 * is asynchronous, so without it a signpost would stay hidden until Settings was reopened.
 *
 * [LocalPanelRegistry] is null only outside a window's composition (previews, test scenes); there,
 * every signpost is dropped, which is the safe direction.
 */
@Composable
internal fun List<SettingsSearchEntry>.withReachableSignposts(): List<SettingsSearchEntry> {
    val registry: PanelRegistry? = LocalPanelRegistry.current
    val registeredIds =
        registry
            ?.getAllPanels()
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    return remember(this, registeredIds) {
        withoutUnreachableSignposts { panel ->
            registry?.resolveRegisteredPanelId(panel) != null
        }
    }
}

/**
 * Open a sidebar panel in the main window, for a search hit whose target is not in this window.
 *
 * The one navigation Settings search performs that Settings cannot show the result of. It exists
 * because removing `Settings > AI Providers` left the words a user types for it - "api key",
 * "anthropic", "claude" - matching nothing at all; see `panelSignpost` for what builds the entry.
 *
 * **Resolved before anything moves.** [PanelEventBus] drops an open event for a panel that never
 * registers, after a bounded wait, and logs it - so firing first and raising second would drop the
 * Settings window behind a main window that then shows nothing, which is worse than the click doing
 * nothing. [withReachableSignposts] means a user should never get here, but the two reads are a
 * frame apart and a plugin can unload in between, so this is the fence rather than the filter.
 *
 * The resolved id is emitted rather than the requested one: `PanelIds.SECRET_MANAGER` says
 * `defaultOrder = 2` and the plugin registers 24. That field is not matched (the event handler and
 * [resolveRegisteredPanelId] both compare `panelId` and `pluginId` only), but everything keyed on
 * the whole data class downstream wants the registered value. `pluginId` **is** matched and does
 * agree here - secret-manager leaves it at the `"ai.rever.boss"` default, unlike docker and
 * kubernetes, which set their own.
 *
 * [WindowFocusManager] registers main windows only (`BossWindow`), so `resolveActionableWindowId`
 * cannot hand back the Settings window itself. Null means no BOSS window is registered at all,
 * which is not reachable from a click inside one - logged rather than ignored, because if it ever
 * is, the click is a silent no-op and nothing else would say so.
 */
internal fun revealPanel(
    panel: PanelId,
    label: String,
    registry: PanelRegistry?,
    scope: CoroutineScope,
) {
    val resolved = registry?.resolveRegisteredPanelId(panel)
    if (resolved == null) {
        // Deliberately no focusWindow: the failure stays where the click happened.
        logger.warn(
            LogCategory.UI,
            "Settings search hit a signpost whose panel is not registered; leaving the window alone",
            mapOf("panelId" to panel.panelId, "label" to label),
        )
        return
    }
    val windowId = WindowFocusManager.resolveActionableWindowId()
    if (windowId == null) {
        logger.warn(
            LogCategory.UI,
            "Settings search could not reveal a panel: no window is registered",
            mapOf("panelId" to resolved.panelId, "label" to label),
        )
        return
    }
    scope.launch {
        PanelEventBus.openPanel(resolved, sourceWindowId = windowId)
        WindowFocusManager.focusWindow(windowId)
    }
}

/**
 * The row to point at for [entry], stamped with [nonce].
 *
 * Null when the entry can only reach its page - a delegated section has no host control to point
 * at, and pointing at nothing is the honest outcome there, better than leaving the previous pick's
 * highlight armed on a page it does not belong to.
 *
 * One caller, not two, despite the shape being shared: `SettingsWindowState.reveal` makes the same
 * decision and keeps its own copy, because it is commonMain and cannot see [SettingsSearchEntry].
 * Extracting it still earned its keep - it took `SettingsContent` back under detekt's complexity
 * ceiling - but the duplication with the holder is real and deliberate, so do not read this as the
 * single definition of the rule.
 */
internal fun highlightFor(
    entry: SettingsSearchEntry,
    nonce: Int,
): SettingsHighlight? =
    if (entry.highlightable) {
        SettingsHighlight(group = entry.group, label = entry.label, nonce = nonce)
    } else {
        null
    }
