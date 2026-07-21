package ai.rever.boss.components.plugin.providers

import ai.rever.boss.performance.PerformanceDataProviderImpl
import ai.rever.boss.plugin.api.PerformanceDataProvider

/**
 * Desktop implementation of PerformanceDataProvider factory.
 */
actual fun createPerformanceDataProvider(): PerformanceDataProvider {
    return PerformanceDataProviderImpl()
}
