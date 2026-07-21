package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.LogDataProvider

/**
 * Desktop implementation of LogDataProvider factory.
 * Returns LogDataProviderImpl which wraps GlobalLogCapture.
 */
actual fun createLogDataProvider(): LogDataProvider {
    return LogDataProviderImpl()
}
