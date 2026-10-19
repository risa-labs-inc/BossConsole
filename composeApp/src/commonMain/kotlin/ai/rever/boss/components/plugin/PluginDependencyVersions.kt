package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.dependency.VersionRequirement

/** Version checks are separate from the existing advisory flow for plugins that are absent. */
internal object PluginDependencyVersions {
    fun requireCompatible(
        manifest: PluginManifest,
        installedVersions: Map<String, String>,
    ) {
        val required = manifest.dependencies.filter { !it.optional && it.pluginId != manifest.pluginId }
        for (dependency in required) {
            val range =
                VersionRequirement.parse(dependency.version)
                    ?: throw IllegalStateException(
                        "${manifest.displayName} declares an unsupported dependency version range: " +
                            "${dependency.pluginId} ${dependency.version}. Update ${manifest.displayName}.",
                    )
            // Missing plugins retain the install-time dependency prompt. Once present, a
            // required dependency must meet its range, even if its version is unparseable.
            val installed = installedVersions[dependency.pluginId] ?: continue
            check(range.matches(installed)) {
                "${manifest.displayName} requires ${dependency.pluginId} ${dependency.version}, " +
                    "but $installed is installed. Install a compatible version of ${dependency.pluginId}."
            }
        }
    }
}
