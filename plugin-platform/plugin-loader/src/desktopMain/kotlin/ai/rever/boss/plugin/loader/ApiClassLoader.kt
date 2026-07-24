package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Shared, runtime-updatable classloader over the newest installed
 * boss-plugin-api jar. Installed as the parent of every [PluginClassLoader]
 * (host app classloader above it), it makes the API layer updatable without
 * a BossConsole release:
 *
 * - A type compiled into the HOST resolves parent-first from the host —
 *   identity with host-side implementations is preserved.
 * - A type only present in a NEWER api jar is served from here, once, with a
 *   single Class identity shared by all plugins — so cross-plugin
 *   `getPluginAPI` interfaces added to the SDK work on this host without a
 *   host rebuild.
 *
 * Member additions to host-compiled types are still shadowed by the host's
 * copy (parent-first); those remain host-contract changes gated by
 * minBossVersion. Only brand-new types ship via the jar (minApiVersion).
 *
 * Lifecycle: created at startup and HOT-SWAPPABLE at runtime. When a newer
 * api jar is installed, DynamicPluginManager.hotSwapApiLayer unloads every
 * plugin, closes this loader (releasing its jar handle), resolves a fresh
 * one over the new jar, and reloads all plugins against it — no app restart.
 * Outside that orchestrated swap the loader is never closed; it holds no
 * references to plugin classloaders, so ordinary plugin unload/GC is
 * unaffected. The updater installs newer api jars under NEW filenames (never
 * in-place), so the open jar handle never blocks the incoming jar.
 */
class ApiClassLoader(
    apiJarUrl: URL?,
    parent: ClassLoader,
) : URLClassLoader(listOfNotNull(apiJarUrl).toTypedArray(), parent) {
    /**
     * Version of the api jar this loader serves (jar manifest
     * Implementation-Version, falling back to plugin.json), or null when no
     * api jar was found (first run offline) — behavior is then identical to
     * the pre-ApiClassLoader host.
     */
    val apiVersion: String? = apiJarUrl?.let { readVersion(File(it.toURI())) }

    /**
     * Absolute path of the api jar, or null when none was found. Published so
     * the out-of-process spawner can append it to child-JVM classpaths (after
     * the runtime + plugin jars, so it only fills in missing types — the flat
     * classpath analogue of this loader's parent-first position).
     */
    val apiJarPath: String? = apiJarUrl?.let { File(it.toURI()).absolutePath }

    companion object {
        private val logger = BossLogger.forComponent("ApiClassLoader")

        /** Plugin id of the boss-plugin-api system plugin. */
        const val API_PLUGIN_ID = "ai.rever.boss.plugin.api"

        /**
         * Build an ApiClassLoader over the newest boss-plugin-api jar in
         * [pluginDir] (typically ~/.boss/plugins after bundled-copy and
         * reconciliation). Returns an empty loader when none is found.
         */
        fun fromPluginDir(
            pluginDir: File,
            parent: ClassLoader,
        ): ApiClassLoader {
            val candidates =
                pluginDir
                    .listFiles { file ->
                        file.isFile && file.extension == "jar"
                    }.orEmpty()
                    .mapNotNull { jar ->
                        val manifest =
                            try {
                                PluginManifestReader.readFromJar(jar.absolutePath)
                            } catch (e: Exception) {
                                // not a BOSS plugin jar
                                logger.debug(
                                    LogCategory.SYSTEM,
                                    "Skipping jar without readable plugin manifest",
                                    mapOf("jar" to jar.name, "error" to e.toString()),
                                )
                                null
                            }
                        if (manifest?.pluginId == API_PLUGIN_ID) {
                            val version = Version.parse(manifest.version)
                            if (version != null) jar to version else null
                        } else {
                            null
                        }
                    }

            val newest = candidates.maxByOrNull { it.second }
            if (newest == null) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "No boss-plugin-api jar found; API layer limited to host-compiled classes",
                    mapOf(
                        "pluginDir" to pluginDir.absolutePath,
                    ),
                )
                return ApiClassLoader(null, parent)
            }

            val loader = ApiClassLoader(newest.first.toURI().toURL(), parent)
            logger.info(
                LogCategory.SYSTEM,
                "API layer resolved",
                mapOf(
                    "jar" to newest.first.name,
                    "apiVersion" to (loader.apiVersion ?: "unknown"),
                ),
            )
            return loader
        }

        private fun readVersion(jar: File): String? {
            val fromManifest =
                try {
                    JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Could not read Implementation-Version from jar manifest",
                        mapOf("jar" to jar.name, "error" to e.toString()),
                    )
                    null
                }
            if (!fromManifest.isNullOrBlank()) return fromManifest

            return try {
                PluginManifestReader.readFromJar(jar.absolutePath).version
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Could not read version from plugin manifest",
                    mapOf("jar" to jar.name, "error" to e.toString()),
                )
                null
            }
        }
    }

    override fun toString(): String = "ApiClassLoader(apiVersion=$apiVersion, urls=${getURLs().size})"
}
