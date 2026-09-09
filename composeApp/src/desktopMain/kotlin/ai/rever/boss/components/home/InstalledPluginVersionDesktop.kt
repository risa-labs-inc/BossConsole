package ai.rever.boss.components.home

import ai.rever.boss.plugin.PluginPersistence

internal actual fun installedPluginVersionOf(pluginId: String): String? = PluginPersistence.getInstalledPlugin(pluginId)?.installedVersion
