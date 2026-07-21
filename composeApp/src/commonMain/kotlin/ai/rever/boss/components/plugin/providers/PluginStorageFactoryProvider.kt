package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PluginStorageFactory

/**
 * Factory function to create platform-specific PluginStorageFactory.
 * Desktop implementation stores data in ~/.boss/plugin-data/{pluginId}/
 *
 * @return PluginStorageFactory implementation
 */
expect fun createPluginStorageFactory(): PluginStorageFactory
