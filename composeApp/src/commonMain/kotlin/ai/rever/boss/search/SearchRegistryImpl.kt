package ai.rever.boss.search

import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.plugin.api.SearchRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of SearchRegistry for managing search providers.
 *
 * Plugins register their SearchProviders through PluginContext.registerSearchProvider(),
 * and GlobalSearchService queries all registered providers to aggregate results.
 */
object SearchRegistryImpl : SearchRegistry {
    private val logger = BossLogger.forComponent("SearchRegistry")

    private val _providers = MutableStateFlow<List<SearchProvider>>(emptyList())
    override val providers: StateFlow<List<SearchProvider>> = _providers.asStateFlow()

    override fun registerProvider(provider: SearchProvider) {
        val current = _providers.value
        if (current.any { it.providerId == provider.providerId }) {
            logger.warn(
                LogCategory.SYSTEM,
                "Search provider already registered",
                mapOf(
                    "providerId" to provider.providerId,
                ),
            )
            // Update existing provider
            _providers.value =
                current.map {
                    if (it.providerId == provider.providerId) provider else it
                }
        } else {
            _providers.value = current + provider
            logger.info(
                LogCategory.SYSTEM,
                "Search provider registered",
                mapOf(
                    "providerId" to provider.providerId,
                    "displayName" to provider.displayName,
                ),
            )
        }
    }

    override fun unregisterProvider(providerId: String) {
        val current = _providers.value
        val provider = current.find { it.providerId == providerId }
        if (provider != null) {
            _providers.value = current.filter { it.providerId != providerId }
            logger.info(
                LogCategory.SYSTEM,
                "Search provider unregistered",
                mapOf(
                    "providerId" to providerId,
                ),
            )
        }
    }

    override fun getProvider(providerId: String): SearchProvider? = _providers.value.find { it.providerId == providerId }
}
