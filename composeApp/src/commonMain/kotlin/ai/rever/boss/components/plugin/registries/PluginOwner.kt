package ai.rever.boss.components.plugin.registries

import ai.rever.boss.plugin.loader.PluginClassLoader

/**
 * Recover the owning pluginId of a plugin-contributed object from its
 * defining classloader. Returns null for host-defined contributions (or
 * anything not loaded through a [PluginClassLoader]) — callers then skip
 * plugin-specific crash attribution.
 *
 * This avoids widening the registration APIs to carry a pluginId: the
 * classloader already knows.
 */
fun owningPluginId(contribution: Any): String? =
    (contribution.javaClass.classLoader as? PluginClassLoader)?.pluginId
