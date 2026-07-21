package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.api.SettingsPageProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of plugin-contributed settings pages, rendered by the
 * host settings window under a "Plugins" divider. Same lifecycle/RBAC
 * conventions as [PanelMenuRegistryImpl]; page content is wrapped in the
 * plugin error boundary at the render site.
 */
object SettingsPageRegistryImpl : AccessGatedRegistry {
    private val logger = BossLogger.forComponent("SettingsPageRegistry")

    private val _pages = MutableStateFlow<Map<String, SettingsPageProvider>>(emptyMap())

    /** pageId -> provider. The settings sidebar observes this. */
    val pages: StateFlow<Map<String, SettingsPageProvider>> = _pages.asStateFlow()

    private val _access = MutableStateFlow(RegistryAccess())
    val access: StateFlow<RegistryAccess> = _access.asStateFlow()

    fun register(provider: SettingsPageProvider) {
        _pages.update { existing ->
            if (existing.containsKey(provider.pageId)) {
                logger.warn(LogCategory.SYSTEM, "Settings page re-registered (replacing previous)", mapOf(
                    "pageId" to provider.pageId
                ))
            }
            existing + (provider.pageId to provider)
        }
        logger.info(LogCategory.SYSTEM, "Settings page registered", mapOf("pageId" to provider.pageId))
    }

    fun unregister(pageId: String) {
        _pages.update { it - pageId }
    }

    override fun updateAccess(isAdmin: Boolean, permissions: Set<String>) {
        _access.value = RegistryAccess(isAdmin, permissions)
    }

    /** RBAC-visible pages in display order. */
    fun visiblePages(): List<SettingsPageProvider> {
        val access = _access.value
        return _pages.value.values
            .filter { access.permits(it.requiresAdmin, it.requiredPermissions) }
            .sortedWith(compareBy({ it.order }, { it.displayName }))
    }

    /** The page for [pageId], or null when absent or not RBAC-visible. */
    fun visiblePage(pageId: String): SettingsPageProvider? =
        _pages.value[pageId]?.takeIf { _access.value.permits(it.requiresAdmin, it.requiredPermissions) }
}
