package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.LogDataProvider

/**
 * Factory function to create platform-specific LogDataProvider.
 * Desktop implementation returns LogDataProviderImpl which wraps GlobalLogCapture.
 */
expect fun createLogDataProvider(): LogDataProvider

/**
 * A host-side provider that owns background work and must be released with its owner.
 *
 * Declared here rather than on `LogDataProvider` because that interface is part of
 * `boss-plugin-api`: adding a member to it is an api release that every plugin's pinned
 * version has to catch up with, for a lifecycle concern no plugin participates in. Plugins
 * receive these providers already built and never dispose them.
 *
 * `DefaultPlugin` is created per window and its providers are created with it, so a provider
 * holding a coroutine or a listener registration on a process-wide singleton outlives the
 * window that made it unless something says otherwise. This is that something.
 */
interface DisposableProvider {
    fun dispose()
}
