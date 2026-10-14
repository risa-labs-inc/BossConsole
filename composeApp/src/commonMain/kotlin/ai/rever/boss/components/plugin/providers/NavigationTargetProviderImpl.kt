package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.plugin.api.NavigationTargetEvent
import ai.rever.boss.plugin.api.NavigationTargetProvider
import kotlinx.coroutines.flow.SharedFlow

/**
 * Implementation of NavigationTargetProvider that wraps the host's NavigationTargetBus.
 *
 * This allows dynamic plugins to listen for navigation target events
 * and position their editor cursors appropriately. The provider exposes the bus flow directly
 * so the bus remains the only replay owner and [clearCache] cannot leave a stale plugin copy.
 */
object NavigationTargetProviderImpl : NavigationTargetProvider {
    override val targets: SharedFlow<NavigationTargetEvent> = NavigationTargetBus.targets

    override fun clearCache() {
        NavigationTargetBus.clearCache()
    }
}
