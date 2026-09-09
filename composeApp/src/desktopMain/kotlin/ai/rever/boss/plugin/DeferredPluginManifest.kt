package ai.rever.boss.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.utils.Version
import java.io.File

/** A deferred update bypasses installPlugin, so validate before recording it. */
internal fun readDeferredPluginManifest(
    pluginId: String,
    jarPath: String,
): PluginManifest {
    val manifest = PluginManifestReader.readFromJar(jarPath)
    require(manifest.pluginId == pluginId) { "Deferred update declares ${manifest.pluginId}, expected $pluginId" }
    val siblings =
        File(jarPath)
            .parentFile
            ?.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .mapNotNull { runCatching { PluginManifestReader.readFromJar(it.absolutePath) }.getOrNull() }
    requireDeferredVersion(manifest, siblings)
    return manifest
}

/** Startup keeps the highest version; never promise a selection that it would discard. */
internal fun requireDeferredVersion(
    manifest: PluginManifest,
    candidates: List<PluginManifest>,
) {
    val selected = requireNotNull(Version.parse(manifest.version)) { "Cannot defer an unrecognized plugin version." }
    require(
        candidates.filter { it.pluginId == manifest.pluginId }.all { candidate ->
            val version = Version.parse(candidate.version)
            version != null && version <= selected
        },
    ) { "Cannot defer this plugin version while a newer or unrecognized artifact remains installed." }
}
