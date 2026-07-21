package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PerformanceDataProvider

/**
 * Factory function to create platform-specific PerformanceDataProvider.
 * Desktop implementation returns PerformanceDataProviderImpl.
 */
expect fun createPerformanceDataProvider(): PerformanceDataProvider
