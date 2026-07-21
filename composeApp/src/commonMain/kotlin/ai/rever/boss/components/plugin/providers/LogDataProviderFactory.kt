package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.LogDataProvider

/**
 * Factory function to create platform-specific LogDataProvider.
 * Desktop implementation returns LogDataProviderImpl which wraps GlobalLogCapture.
 */
expect fun createLogDataProvider(): LogDataProvider
