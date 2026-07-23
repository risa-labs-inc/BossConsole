package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.serialization.json.Json
import java.io.File
import java.util.jar.JarFile

/**
 * Reads plugin manifests from JAR files.
 *
 * The manifest is expected at [PluginManifestConstants.MANIFEST_PATH]
 * (META-INF/boss-plugin/plugin.json) within the JAR.
 */
object PluginManifestReader {
    private val logger = BossLogger.forComponent("PluginManifestReader")

    /**
     * JSON parser with lenient settings for reading manifests.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Read a plugin manifest from a JAR file.
     *
     * @param jarPath Path to the plugin JAR file
     * @return The parsed manifest
     * @throws PluginManifestException if the manifest is missing or invalid
     */
    fun readFromJar(jarPath: String): PluginManifest {
        val file = File(jarPath)
        if (!file.exists()) {
            throw PluginManifestException(
                "Plugin JAR not found: $jarPath"
            )
        }

        if (!file.name.endsWith(".jar")) {
            throw PluginManifestException(
                "File is not a JAR: $jarPath"
            )
        }

        return try {
            JarFile(file).use { jar ->
                val manifestEntry = jar.getJarEntry(PluginManifestConstants.MANIFEST_PATH)
                    ?: throw PluginManifestException(
                        "Plugin manifest not found at ${PluginManifestConstants.MANIFEST_PATH}",
                        null
                    )

                val manifestContent = jar.getInputStream(manifestEntry).bufferedReader().use {
                    it.readText()
                }

                parseManifest(manifestContent, jarPath)
            }
        } catch (e: PluginManifestException) {
            throw e
        } catch (e: Exception) {
            throw PluginManifestException(
                "Failed to read manifest from JAR: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Parse a manifest from JSON content.
     *
     * @param content The JSON content
     * @param source Source identifier for error messages
     * @return The parsed manifest
     * @throws PluginManifestException if the manifest is invalid
     */
    fun parseManifest(content: String, source: String = "unknown"): PluginManifest {
        return try {
            val manifest = json.decodeFromString<PluginManifest>(content)
            validateManifest(manifest)
            manifest
        } catch (e: PluginManifestException) {
            throw e
        } catch (e: Exception) {
            throw PluginManifestException(
                "Failed to parse plugin manifest from $source: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Validate a parsed manifest.
     *
     * @param manifest The manifest to validate
     * @throws PluginManifestException if validation fails
     */
    fun validateManifest(manifest: PluginManifest) {
        val errors = mutableListOf<String>()

        // Required fields
        if (manifest.pluginId.isBlank()) {
            errors.add("pluginId is required")
        } else if (!isValidPluginId(manifest.pluginId)) {
            errors.add("pluginId must follow reverse domain notation (e.g., com.example.plugin)")
        }

        if (manifest.displayName.isBlank()) {
            errors.add("displayName is required")
        }

        if (manifest.version.isBlank()) {
            errors.add("version is required")
        } else if (!isValidVersion(manifest.version)) {
            errors.add("version must follow semantic versioning (e.g., 1.0.0)")
        }

        if (manifest.apiVersion.isBlank()) {
            errors.add("apiVersion is required")
        }

        if (manifest.mainClass.isBlank()) {
            errors.add("mainClass is required")
        } else if (!isValidClassName(manifest.mainClass)) {
            errors.add("mainClass must be a valid fully-qualified class name")
        }

        // API version compatibility
        if (!isCompatibleApiVersion(manifest.apiVersion)) {
            logger.warn(LogCategory.SYSTEM, "Plugin API version may be incompatible", mapOf(
                "pluginId" to manifest.pluginId,
                "requiredVersion" to manifest.apiVersion,
                "currentVersion" to PluginManifestConstants.CURRENT_API_VERSION
            ))
        }

        if (errors.isNotEmpty()) {
            throw PluginManifestException(
                "Invalid plugin manifest: ${errors.joinToString("; ")}",
                manifest.pluginId.ifBlank { null }
            )
        }

        logger.debug(LogCategory.SYSTEM, "Manifest validated successfully", mapOf(
            "pluginId" to manifest.pluginId,
            "version" to manifest.version
        ))
    }

    /**
     * Check if a plugin ID follows the expected format (reverse domain notation).
     */
    private fun isValidPluginId(pluginId: String): Boolean {
        // Allow alphanumeric, dots, hyphens, and underscores
        // Must have at least one dot (like com.example)
        val pattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]*(?:\\.[a-zA-Z0-9_-]+)+$")
        return pattern.matches(pluginId)
    }

    /**
     * Check if a version string follows semantic versioning.
     */
    private fun isValidVersion(version: String): Boolean {
        // Relaxed semver: major.minor.patch with optional prerelease/build
        val pattern = Regex("^\\d+\\.\\d+\\.\\d+(?:-[a-zA-Z0-9.]+)?(?:\\+[a-zA-Z0-9.]+)?$")
        return pattern.matches(version)
    }

    /**
     * Check if a class name is a valid fully-qualified Java class name.
     */
    private fun isValidClassName(className: String): Boolean {
        // Simple validation: alphanumeric with dots, no leading/trailing dots
        val pattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)*$")
        return pattern.matches(className)
    }

    /**
     * Check if the plugin's required API version is compatible with the current version.
     */
    private fun isCompatibleApiVersion(requiredVersion: String): Boolean {
        // Simple comparison for now - just check major version matches
        val required = requiredVersion.split(".").firstOrNull()?.toIntOrNull() ?: return false
        val current = PluginManifestConstants.CURRENT_API_VERSION.split(".").firstOrNull()?.toIntOrNull() ?: return false
        return required <= current
    }

    /**
     * Check if a JAR file contains a valid plugin manifest.
     *
     * @param jarPath Path to the JAR file
     * @return True if the JAR contains a valid manifest
     */
    fun hasValidManifest(jarPath: String): Boolean {
        return try {
            readFromJar(jarPath)
            true
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "JAR has no valid plugin manifest", mapOf("jarPath" to jarPath, "error" to e.toString()))
            false
        }
    }

    /**
     * Read all plugin manifests from a directory.
     *
     * @param directory Path to the directory containing plugin JARs
     * @return List of manifests with their JAR paths
     */
    fun readFromDirectory(directory: String): List<Pair<String, PluginManifest>> {
        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            logger.warn(LogCategory.SYSTEM, "Plugin directory does not exist", mapOf(
                "path" to directory
            ))
            return emptyList()
        }

        return dir.listFiles { file -> file.extension == "jar" }
            ?.mapNotNull { file ->
                try {
                    val manifest = readFromJar(file.absolutePath)
                    file.absolutePath to manifest
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Failed to read manifest from JAR", mapOf(
                        "path" to file.absolutePath,
                        "error" to (e.message ?: "unknown")
                    ))
                    null
                }
            }
            ?: emptyList()
    }
}
