package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.DownloadDataProvider

/**
 * Desktop implementation of download data provider factory.
 * Returns DownloadDataProviderImpl that wraps FluckEngine.
 */
actual fun createDownloadDataProvider(): DownloadDataProvider = DownloadDataProviderImpl()
