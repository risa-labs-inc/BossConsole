package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.repository.PluginWithSource
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

/**
 * Service for installing plugins during the wizard flow.
 *
 * Handles downloading plugins from the repository and installing them
 * through the DynamicPluginManager. Also supports GitHub-sourced plugins.
 */
class PluginInstallService(
    private val dynamicPluginManager: DynamicPluginManager
) {
    private val logger = BossLogger.forComponent("PluginInstallService")

    /**
     * Install multiple plugins with progress reporting.
     *
     * @param plugins List of plugins to install (includes GitHub URL info)
     * @param onProgress Callback for progress updates (0.0 to 1.0, status message)
     * @return Result containing installation result with both successful and failed plugin IDs
     */
    suspend fun installPlugins(
        plugins: List<WizardPluginInfo>,
        onProgress: (Float, String) -> Unit
    ): Result<PluginInstallResult> = withContext(Dispatchers.IO) {
        val installedIds = mutableListOf<String>()
        val failedIds = mutableListOf<Pair<String, String>>() // pluginId to error message

        if (plugins.isEmpty()) {
            onProgress(1f, "No tools to install")
            return@withContext Result.success(PluginInstallResult(emptyList(), emptyList()))
        }

        val repositoryManager = PluginStoreSetup.repositoryManager
        val pluginDir = PluginStoreSetup.getPluginDir()
        val totalPlugins = plugins.size

        for ((index, plugin) in plugins.withIndex()) {
            val progress = index.toFloat() / totalPlugins
            onProgress(progress, "Installing ${plugin.name}...")

            try {
                logger.info(LogCategory.SYSTEM, "Installing plugin from wizard", mapOf(
                    "pluginId" to plugin.id,
                    "name" to plugin.name,
                    "hasGithubUrl" to plugin.githubUrl.isNotEmpty(),
                    "progress" to "${index + 1}/$totalPlugins"
                ))

                // Defense-in-depth: never wizard-install the microkernel runtime.
                // PluginListProvider already filters service-type plugins out of
                // the wizard list, but a stale fallback list or a future
                // mandatory-GitHub entry could still slip it through. The host
                // auto-installs it via PluginStoreSetup.ensureSystemPluginsInstalled
                // when kernel mode is enabled — letting it reach
                // dynamicPluginManager.installPlugin() trips the binary-compat
                // validator (cross-classloader kotlin.reflect access).
                if (plugin.id == ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID) {
                    logger.info(LogCategory.SYSTEM, "Wizard skipping microkernel runtime — auto-managed by host", mapOf(
                        "pluginId" to plugin.id
                    ))
                    installedIds.add(plugin.id)
                    continue
                }

                // Check if already installed
                if (dynamicPluginManager.isInstalled(plugin.id)) {
                    logger.info(LogCategory.SYSTEM, "Plugin already installed, skipping", mapOf(
                        "pluginId" to plugin.id
                    ))
                    installedIds.add(plugin.id)
                    continue
                }

                // If plugin has a GitHub URL, install from GitHub
                if (plugin.githubUrl.isNotEmpty()) {
                    val result = installFromGitHub(plugin, pluginDir, progress, totalPlugins, onProgress)
                    if (result.isSuccess) {
                        installedIds.add(plugin.id)
                    } else {
                        failedIds.add(plugin.id to (result.exceptionOrNull()?.message ?: "GitHub install failed"))
                    }
                    continue
                }

                // Otherwise, try to install from repository
                if (repositoryManager == null) {
                    failedIds.add(plugin.id to "Tool repository not initialized")
                    continue
                }

                // Get plugin info from repository
                val pluginResult = repositoryManager.getPlugin(plugin.id)
                val pluginWithSource: PluginWithSource? = pluginResult.getOrNull()

                if (pluginWithSource == null) {
                    logger.warn(LogCategory.SYSTEM, "Plugin not found in repository", mapOf(
                        "pluginId" to plugin.id
                    ))
                    failedIds.add(plugin.id to "Tool not found in repository")
                    continue
                }

                val pluginInfo = pluginWithSource.plugin

                // Download the latest version of the plugin (pass null for version)
                onProgress(progress + (0.2f / totalPlugins), "Downloading ${plugin.name}...")
                val tempPath = File(pluginDir, "${plugin.id}-downloading.jar").absolutePath
                val downloadResult = repositoryManager.downloadPlugin(plugin.id, null, tempPath)
                val downloadedPath: String? = downloadResult.getOrNull()

                if (downloadedPath == null) {
                    val error = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                    logger.error(LogCategory.SYSTEM, "Failed to download plugin", mapOf(
                        "pluginId" to plugin.id,
                        "error" to error
                    ))
                    failedIds.add(plugin.id to error)
                    continue
                }

                // Extract manifest to get the actual downloaded version
                onProgress(progress + (0.4f / totalPlugins), "Extracting manifest for ${plugin.name}...")
                val manifest = extractManifestFromJar(downloadedPath)
                val actualVersion = manifest?.version ?: pluginInfo.version

                // Rename to include actual version
                val finalFile = File(pluginDir, "${plugin.id}-${actualVersion}.jar")
                val downloadedFile = File(downloadedPath)
                if (downloadedFile.absolutePath != finalFile.absolutePath) {
                    if (finalFile.exists()) finalFile.delete()
                    downloadedFile.renameTo(finalFile)
                }
                val jarPath = finalFile.absolutePath

                // Install the plugin
                onProgress(progress + (0.6f / totalPlugins), "Loading ${plugin.name}...")
                val installResult = dynamicPluginManager.installPlugin(jarPath, enabled = true)

                if (installResult.isSuccess) {
                    // Persist the installation with actual version
                    PluginPersistence.addInstalledPlugin(
                        pluginId = plugin.id,
                        jarPath = jarPath,
                        enabled = true,
                        sourceUrl = pluginInfo.downloadUrl,
                        installedVersion = actualVersion
                    )

                    logger.info(LogCategory.SYSTEM, "Plugin installed successfully", mapOf(
                        "pluginId" to plugin.id,
                        "version" to actualVersion
                    ))
                    installedIds.add(plugin.id)
                } else {
                    val error = installResult.exceptionOrNull()?.message ?: "Installation failed"
                    logger.error(LogCategory.SYSTEM, "Failed to install plugin", mapOf(
                        "pluginId" to plugin.id,
                        "error" to error
                    ))
                    // Clean up the JAR on failed install
                    finalFile.delete()
                    failedIds.add(plugin.id to error)
                }

            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Exception installing plugin", mapOf(
                    "pluginId" to plugin.id
                ), e)
                failedIds.add(plugin.id to (e.message ?: "Unknown error"))
            }
        }

        onProgress(1f, "Installation complete")

        // Log summary
        logger.info(LogCategory.SYSTEM, "Plugin installation complete", mapOf(
            "total" to totalPlugins,
            "installed" to installedIds.size,
            "failed" to failedIds.size
        ))

        if (failedIds.isNotEmpty()) {
            logger.warn(LogCategory.SYSTEM, "Some plugins failed to install", mapOf(
                "failed" to failedIds.map { "${it.first}: ${it.second}" }.joinToString("; ")
            ))
        }

        // Return result with both installed and failed IDs
        Result.success(PluginInstallResult(installedIds, failedIds))
    }

    /**
     * Install a plugin from GitHub.
     * Downloads the release JAR directly from GitHub releases.
     */
    private suspend fun installFromGitHub(
        plugin: WizardPluginInfo,
        pluginDir: File,
        baseProgress: Float,
        totalPlugins: Int,
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onProgress(baseProgress + (0.1f / totalPlugins), "Checking GitHub releases for ${plugin.name}...")

            // Parse GitHub URL to get owner/repo
            val regex = Regex("https://github\\.com/([^/]+)/([^/]+)(?:/.*)?")
            val match = regex.matchEntire(plugin.githubUrl.trim().trimEnd('/'))
                ?: return@withContext Result.failure(Exception("Invalid GitHub URL format"))

            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")

            // Try to download from GitHub releases
            onProgress(baseProgress + (0.2f / totalPlugins), "Downloading ${plugin.name} from GitHub...")

            val jarPath = downloadFromGitHubRelease(owner, repo, pluginDir)
                ?: return@withContext Result.failure(Exception("No JAR found in GitHub releases for $owner/$repo"))

            onProgress(baseProgress + (0.6f / totalPlugins), "Extracting manifest for ${plugin.name}...")

            // Extract manifest from JAR
            val manifest = extractManifestFromJar(jarPath)
                ?: return@withContext Result.failure(Exception("Downloaded JAR does not contain valid plugin manifest"))

            // Verify manifest plugin ID matches expected ID
            if (manifest.pluginId != plugin.id) {
                logger.warn(LogCategory.SYSTEM, "Plugin ID mismatch between expected and manifest", mapOf(
                    "expectedId" to plugin.id,
                    "manifestId" to manifest.pluginId,
                    "githubUrl" to plugin.githubUrl
                ))
                // Continue with manifest ID as it's the authoritative source
            }

            onProgress(baseProgress + (0.7f / totalPlugins), "Installing ${plugin.name}...")

            // Install the plugin
            val installResult = dynamicPluginManager.installPlugin(jarPath, enabled = true)

            if (installResult.isFailure) {
                return@withContext Result.failure(installResult.exceptionOrNull() ?: Exception("Install failed"))
            }

            // Persist the installation
            PluginPersistence.addInstalledPlugin(
                pluginId = manifest.pluginId,
                jarPath = jarPath,
                enabled = true,
                sourceUrl = plugin.githubUrl,
                installedVersion = manifest.version
            )

            logger.info(LogCategory.SYSTEM, "GitHub plugin installed successfully", mapOf(
                "pluginId" to manifest.pluginId,
                "version" to manifest.version
            ))

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to install GitHub plugin", mapOf(
                "pluginId" to plugin.id,
                "githubUrl" to plugin.githubUrl
            ), e)
            Result.failure(e)
        }
    }

    /**
     * Download a JAR from GitHub releases.
     * Returns the path to the downloaded JAR, or null if not found.
     */
    private fun downloadFromGitHubRelease(owner: String, repo: String, pluginDir: File): String? {
        var connection: java.net.HttpURLConnection? = null
        var jarConnection: java.net.HttpURLConnection? = null

        return try {
            // Use GitHub API to get latest release
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

            connection = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "BOSS-Plugin-Wizard")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Add GitHub token if available (increases rate limit from 60 to 5000 req/hr)
            getGitHubToken()?.let { token ->
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            if (connection.responseCode != 200) {
                logger.debug(LogCategory.SYSTEM, "No releases found for $owner/$repo", mapOf(
                    "responseCode" to connection.responseCode
                ))
                return null
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val json = Json { ignoreUnknownKeys = true }

            // Parse response to find JAR asset
            val releaseData = json.parseToJsonElement(responseText).jsonObject
            val assets = releaseData["assets"]?.jsonArray ?: return null

            // Find a JAR file (not sources, javadoc, or test)
            var downloadUrl = ""
            var jarName = ""

            for (asset in assets) {
                val assetObj = asset.jsonObject
                val name = assetObj["name"]?.jsonPrimitive?.content ?: continue
                if (name.endsWith(".jar") &&
                    !name.contains("-sources", ignoreCase = true) &&
                    !name.contains("-javadoc", ignoreCase = true) &&
                    !name.contains("-test", ignoreCase = true)) {
                    jarName = name
                    downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: continue
                    break
                }
            }

            if (downloadUrl.isEmpty()) {
                logger.debug(LogCategory.SYSTEM, "No JAR asset found in release for $owner/$repo")
                return null
            }

            logger.info(LogCategory.SYSTEM, "Downloading JAR from GitHub release", mapOf(
                "name" to jarName,
                "url" to downloadUrl
            ))

            // Download the JAR
            jarConnection = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
            jarConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Wizard")
            jarConnection.connectTimeout = 30000
            jarConnection.readTimeout = 60000

            // Add GitHub token for download as well
            getGitHubToken()?.let { token ->
                jarConnection.setRequestProperty("Authorization", "Bearer $token")
            }

            if (jarConnection.responseCode != 200) {
                logger.warn(LogCategory.SYSTEM, "Failed to download JAR", mapOf(
                    "responseCode" to jarConnection.responseCode
                ))
                return null
            }

            // Save to plugin directory
            pluginDir.mkdirs()
            val targetFile = File(pluginDir, jarName)
            jarConnection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            logger.info(LogCategory.SYSTEM, "Downloaded JAR from GitHub release", mapOf(
                "path" to targetFile.absolutePath,
                "size" to targetFile.length()
            ))

            targetFile.absolutePath
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error downloading from GitHub release", mapOf(
                "owner" to owner,
                "repo" to repo
            ), e)
            null
        } finally {
            // Properly close HTTP connections to prevent resource leaks
            connection?.disconnect()
            jarConnection?.disconnect()
        }
    }

    /**
     * Get GitHub token from environment or system properties.
     * Token increases API rate limit from 60 to 5000 requests per hour.
     */
    private fun getGitHubToken(): String? {
        return System.getenv("GITHUB_TOKEN")
            ?: System.getProperty("GITHUB_TOKEN")
            ?: try {
                // Try to read from local.properties
                val localProps = File(System.getProperty("user.dir"), "local.properties")
                if (localProps.exists()) {
                    localProps.readLines()
                        .firstOrNull { it.startsWith("GITHUB_TOKEN=") }
                        ?.substringAfter("=")
                        ?.trim()
                } else null
            } catch (e: Exception) {
                null
            }
    }

    /**
     * Extract plugin manifest from a JAR file.
     */
    private fun extractManifestFromJar(jarPath: String): PluginManifest? {
        return try {
            val jarFile = JarFile(File(jarPath))
            jarFile.use { jar ->
                val manifestEntry = jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                if (manifestEntry == null) {
                    logger.debug(LogCategory.SYSTEM, "No plugin manifest found in JAR", mapOf(
                        "jarPath" to jarPath
                    ))
                    return null
                }

                val manifestJson = jar.getInputStream(manifestEntry).bufferedReader().readText()
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<PluginManifest>(manifestJson)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to extract manifest from JAR", mapOf(
                "jarPath" to jarPath,
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    /**
     * Install multiple plugins by IDs (legacy method for backward compatibility).
     *
     * @param pluginIds List of plugin IDs to install
     * @param onProgress Callback for progress updates (0.0 to 1.0, status message)
     * @return Result containing installation result with both successful and failed plugin IDs
     */
    suspend fun installPluginsByIds(
        pluginIds: List<String>,
        onProgress: (Float, String) -> Unit
    ): Result<PluginInstallResult> {
        // Convert IDs to WizardPluginInfo with minimal info
        val plugins = pluginIds.map { id ->
            WizardPluginInfo(
                id = id,
                name = id.substringAfterLast("."),
                description = "",
                version = ""
            )
        }
        return installPlugins(plugins, onProgress)
    }

    companion object {
        /**
         * Create a PluginInstallService with the given DynamicPluginManager.
         */
        fun create(dynamicPluginManager: DynamicPluginManager): PluginInstallService {
            return PluginInstallService(dynamicPluginManager)
        }
    }
}
