package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelMenuContribution
import ai.rever.boss.plugin.api.PanelMenuItem
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of plugin-contributed panel top-bar menu items.
 *
 * Modeled on [ai.rever.boss.mcp.McpToolRegistryImpl]. Plugins register a
 * [PanelMenuContribution] through `PluginContext.registerPanelMenuContribution()`;
 * the host unregisters automatically on disable/unload (TrackingPluginContext).
 * BossPanelTopBar reads [contributions] + [access] reactively and calls
 * [itemsFor] when a menu opens.
 *
 * Plugin code ([PanelMenuContribution.items]/[PanelMenuContribution.onItemClick])
 * never runs under a lock and is wrapped in try/catch — a throwing contribution
 * can log-warn but never crash the panel chrome.
 */
object PanelMenuRegistryImpl : AccessGatedRegistry {
    private val logger = BossLogger.forComponent("PanelMenuRegistry")

    private val _contributions = MutableStateFlow<Map<String, PanelMenuContribution>>(emptyMap())

    /** contributionId -> contribution. UI observes this to re-query menus. */
    val contributions: StateFlow<Map<String, PanelMenuContribution>> = _contributions.asStateFlow()

    private val _access = MutableStateFlow(RegistryAccess())

    /** RBAC snapshot; UI observes so gated items appear/disappear live. */
    val access: StateFlow<RegistryAccess> = _access.asStateFlow()

    fun register(contribution: PanelMenuContribution) {
        _contributions.update { existing ->
            if (existing.containsKey(contribution.contributionId)) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Panel menu contribution re-registered (replacing previous)",
                    mapOf(
                        "contributionId" to contribution.contributionId,
                    ),
                )
            }
            existing + (contribution.contributionId to contribution)
        }
        logger.info(
            LogCategory.SYSTEM,
            "Panel menu contribution registered",
            mapOf(
                "contributionId" to contribution.contributionId,
                "targetPanels" to (contribution.targetPanels?.joinToString(",") ?: "ALL"),
            ),
        )
    }

    fun unregister(contributionId: String) {
        _contributions.update { it - contributionId }
    }

    override fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    ) {
        _access.value = RegistryAccess(isAdmin, permissions)
    }

    /** Contributions targeting [panelId] (targetPanels == null means ALL panels). */
    fun contributionsFor(panelId: PanelId): List<PanelMenuContribution> =
        _contributions.value.values.filter { contribution ->
            contribution.targetPanels?.contains(panelId.panelId) != false
        }

    /**
     * Resolve the visible menu items for [panelId]: query each targeting
     * contribution (outside any lock, crash-isolated), RBAC-filter, sort by
     * [PanelMenuItem.order]. Called when a menu opens — contributions must
     * keep `items()` cheap.
     */
    fun itemsFor(panelId: PanelId): List<Pair<PanelMenuContribution, PanelMenuItem>> {
        val access = _access.value
        return contributionsFor(panelId)
            .flatMap { contribution ->
                val items =
                    try {
                        contribution.items(panelId)
                    } catch (t: Throwable) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Panel menu contribution items() failed",
                            mapOf(
                                "contributionId" to contribution.contributionId,
                                "error" to (t.message ?: t::class.simpleName),
                            ),
                        )
                        emptyList()
                    }
                items
                    .filter { access.permits(it.requiresAdmin, it.requiredPermissions) }
                    .map { contribution to it }
            }.sortedBy { it.second.order }
    }

    /** Route a click to its contribution, crash-isolated. */
    fun onItemClick(
        contribution: PanelMenuContribution,
        panelId: PanelId,
        itemId: String,
        windowId: String?,
    ) {
        try {
            contribution.onItemClick(panelId, itemId, windowId)
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Panel menu item click handler failed",
                mapOf(
                    "contributionId" to contribution.contributionId,
                    "itemId" to itemId,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
        }
    }
}
