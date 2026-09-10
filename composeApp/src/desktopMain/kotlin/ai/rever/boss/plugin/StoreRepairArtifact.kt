package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.components.plugin.RetiredPluginIds
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.nio.file.Files

/** Publishes a verified store download only after its identity and version have been checked. */
internal object StoreRepairArtifact {
    // Runtime/API artifacts have separate bootstrap and filename contracts.
    fun supports(
        plugin: SystemPluginInfo,
        installedVersionOf: (String) -> String? = { id ->
            PluginPersistence
                .getInstalledPlugins()
                .firstOrNull { it.pluginId == id && it.enabled && File(it.jarPath).isFile }
                ?.installedVersion
        },
    ): Boolean =
        !plugin.downloadOnly &&
            plugin.pluginId !in PluginDependencyResolution.NOT_USER_INSTALLABLE &&
            !RetiredPluginIds.hiddenFromOffers(plugin.pluginId, installedVersionOf)

    fun promote(
        plugin: SystemPluginInfo,
        downloaded: File,
        manifest: PluginManifest,
        storeVersion: String,
        move: (File, File) -> Unit = { source, target -> Files.move(source.toPath(), target.toPath()) },
    ): File {
        require(supports(plugin)) { "Plugin is not eligible for automatic store repair" }
        require(manifest.pluginId == plugin.pluginId) { "Store JAR declares a different plugin id" }
        require(manifest.version == storeVersion) { "Store JAR version differs from the requested version" }
        val version = requireNotNull(SemanticVersion.parse(manifest.version)) { "Store JAR version is not semver" }
        plugin.minVersion?.let { minimum ->
            val floor = requireNotNull(SemanticVersion.parse(minimum)) { "Invalid system plugin version floor" }
            require(version >= floor) { "Store JAR is older than this host requires" }
        }
        val ipcReason = PluginStoreSetup.ipcIncompatibilityReason(manifest.minIpcVersion)
        require(ipcReason == null) { "Store JAR is IPC-incompatible: $ipcReason" }
        require(downloaded.name.endsWith(".jar.part")) { "Repair download must use a non-scannable part file" }
        // Keep the unique download basename: never overwrite a concurrent install or a loaded JAR.
        // Eligible plugins resolve version from their manifest; runtime filename consumers are excluded.
        val target = File(downloaded.parentFile, downloaded.name.removeSuffix(".part"))
        check(!target.exists()) { "Repair destination already exists" }
        var published = false
        try {
            // Sign first, publish last. Startup cannot scan the new JAR before its sidecar exists.
            PluginSignatureSidecar.persist(target.absolutePath, PluginSignatureSidecar.read(downloaded.absolutePath))
            move(downloaded, target)
            published = true
        } finally {
            if (!published) {
                target.delete()
                PluginSignatureSidecar.delete(target.absolutePath)
            }
        }
        PluginSignatureSidecar.delete(downloaded.absolutePath)
        return target
    }
}
