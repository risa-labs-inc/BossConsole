package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.NavigationResolverProvider

/**
 * Factory for creating NavigationResolverProvider instances.
 * Platform-specific implementations provide PSI-based navigation.
 */
expect fun createNavigationResolverProvider(): NavigationResolverProvider?
