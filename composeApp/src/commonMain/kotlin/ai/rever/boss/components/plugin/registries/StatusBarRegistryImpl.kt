package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.api.StatusBarItemProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of plugin-contributed status-bar widgets, rendered by
 * BossBottomBar. Same lifecycle/RBAC conventions as [PanelMenuRegistryImpl];
 * widget content is wrapped in the plugin error boundary at the render site.
 */
object StatusBarRegistryImpl : AccessGatedRegistry {
    private val logger = BossLogger.forComponent("StatusBarRegistry")

    private val _items = MutableStateFlow<Map<String, StatusBarItemProvider>>(emptyMap())

    /** itemId -> provider. The bottom bar observes this. */
    val items: StateFlow<Map<String, StatusBarItemProvider>> = _items.asStateFlow()

    private val _access = MutableStateFlow(RegistryAccess())
    val access: StateFlow<RegistryAccess> = _access.asStateFlow()

    fun register(provider: StatusBarItemProvider) {
        _items.update { existing ->
            if (existing.containsKey(provider.itemId)) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Status-bar item re-registered (replacing previous)",
                    mapOf(
                        "itemId" to provider.itemId,
                    ),
                )
            }
            existing + (provider.itemId to provider)
        }
        logger.info(LogCategory.SYSTEM, "Status-bar item registered", mapOf("itemId" to provider.itemId))
    }

    fun unregister(itemId: String) {
        _items.update { it - itemId }
    }

    override fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    ) {
        _access.value = RegistryAccess(isAdmin, permissions)
    }

    /** RBAC-visible items for [alignment], in display order. */
    fun visibleItems(alignment: StatusBarAlignment): List<StatusBarItemProvider> {
        val access = _access.value
        return _items.value.values
            .filter { it.alignment == alignment && access.permits(requiresAdmin = false, requiredPermissions = it.requiredPermissions) }
            .sortedWith(compareBy({ it.order }, { it.itemId }))
    }
}
