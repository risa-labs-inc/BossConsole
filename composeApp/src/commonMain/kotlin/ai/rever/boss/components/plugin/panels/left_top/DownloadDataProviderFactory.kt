package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.DownloadDataProvider

/**
 * Factory function to create platform-specific DownloadDataProvider.
 * Desktop: Returns DownloadDataProviderImpl that wraps FluckEngine
 * Other platforms: Returns a no-op implementation
 */
expect fun createDownloadDataProvider(): DownloadDataProvider
