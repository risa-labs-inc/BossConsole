package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.MissingDependencyReporter
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.PluginWithSource
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * Service for installing plugins during the wizard flow.
 *
 * Handles downloading plugins from the repository and installing them
 * through the DynamicPluginManager. Also supports GitHub-sourced plugins.
 */
class PluginInstallService(
    private val dynamicPluginManager: DynamicPluginManager,
    /** Raises the install-time dependency prompt; see [MissingDependencyReporter]. */
    private val dependencyReporter: MissingDependencyReporter =
        MissingDependencyReporter.forManager(dynamicPluginManager),
    /**
     * Whether the loader still holds an id, which a DISABLED manager entry does not tell us.
     * Injected for the same reason [dependencyReporter] is: so a test can drive the decision.
     */
    private val isResident: (String) -> Boolean = { dynamicPluginManager.isPluginResident(it) },
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
        onProgress: (Float, String) -> Unit,
    ): Result<PluginInstallResult> =
        withContext(Dispatchers.IO) {
            val installedIds = mutableListOf<String>()

            /** Manifests of everything this batch loaded, for the one dependency report below. */
            val installedManifests = mutableListOf<PluginManifest>()
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
                    logger.info(
                        LogCategory.SYSTEM,
                        "Installing plugin from wizard",
                        mapOf(
                            "pluginId" to plugin.id,
                            "name" to plugin.name,
                            "hasGithubUrl" to plugin.githubUrl.isNotEmpty(),
                            "progress" to "${index + 1}/$totalPlugins",
                        ),
                    )

                    // Defense-in-depth: never wizard-install the microkernel runtime.
                    // PluginListProvider already filters service-type plugins out of
                    // the wizard list, but a stale fallback list or a future
                    // mandatory-GitHub entry could still slip it through. The host
                    // auto-installs it via PluginStoreSetup.ensureSystemPluginsInstalled
                    // when kernel mode is enabled — letting it reach
                    // dynamicPluginManager.installPlugin() trips the binary-compat
                    // validator (cross-classloader kotlin.reflect access).
                    if (plugin.id == ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID) {
                        logger.info(
                            LogCategory.SYSTEM,
                            "Wizard skipping microkernel runtime - auto-managed by host",
                            mapOf(
                                "pluginId" to plugin.id,
                            ),
                        )
                        installedIds.add(plugin.id)
                        continue
                    }

                    // `installedAndOnDisk`, not the manager's `isInstalled`. That one is
                    // `pluginStates.containsKey`, and an entry is not the same as a usable
                    // plugin: `installPlugin` registers a DISABLED entry for a jar it rejected
                    // as binary-incompatible and then deletes the jar. An entry-only check
                    // therefore reports "already installed, skipping" for a plugin that is not
                    // there, adds its id to installedIds, and the wizard finishes claiming it
                    // installed something it never did. This is the same trap the dependency
                    // prompt already hit once, which is why there is one definition of the
                    // predicate to reach for; this file already uses it after the loop.
                    if (plugin.id in installedAndUsable()) {
                        logger.info(
                            LogCategory.SYSTEM,
                            "Plugin already installed, skipping",
                            mapOf(
                                "pluginId" to plugin.id,
                            ),
                        )
                        installedIds.add(plugin.id)
                        continue
                    }

                    // If plugin has a GitHub URL, install from GitHub
                    if (plugin.githubUrl.isNotEmpty()) {
                        val result = installFromGitHub(plugin, pluginDir, progress, totalPlugins, onProgress)
                        val installedPlugin = result.getOrNull()
                        if (installedPlugin != null) {
                            installedIds.add(plugin.id)
                            // Same LOADED gate as the store branch: a plugin that installed but
                            // did not register must not have its dependencies offered.
                            if (installedPlugin.state == PluginState.LOADED) {
                                installedManifests.add(installedPlugin.manifest)
                            }
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
                        // A failed lookup is not an absent plugin, and saying "not found" for both is
                        // what made a decode bug in one store row look like a missing plugin. The
                        // manager now distinguishes them; report the cause when there is one.
                        val lookupFailure = pluginResult.exceptionOrNull()
                        if (lookupFailure != null) {
                            logger.error(
                                LogCategory.SYSTEM,
                                "Plugin repository lookup failed",
                                mapOf("pluginId" to plugin.id),
                                error = lookupFailure,
                            )
                        } else {
                            logger.warn(
                                LogCategory.SYSTEM,
                                "Plugin not found in repository",
                                mapOf(
                                    "pluginId" to plugin.id,
                                ),
                            )
                        }
                        // The message reaches the wizard's failure list, so it is the exception's
                        // short message and never its cause - see PluginLookupException.
                        failedIds.add(
                            plugin.id to (lookupFailure?.message ?: "Tool not found in repository"),
                        )
                        continue
                    }

                    val pluginInfo = pluginWithSource.plugin

                    // Download the latest version of the plugin (pass null for version)
                    onProgress(progress + (0.2f / totalPlugins), "Downloading ${plugin.name}...")
                    // Unique and not `.jar`: a fixed name is scannable as a plugin and two
                    // installs of the same id would collide on it.
                    val tempPath = File(pluginDir, stagingNameFor("${plugin.id}.jar")).absolutePath
                    val downloadResult = repositoryManager.downloadPlugin(plugin.id, null, tempPath)
                    val downloadedPath: String? = downloadResult.getOrNull()

                    if (downloadedPath == null) {
                        val error = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                        logger.error(
                            LogCategory.SYSTEM,
                            "Failed to download plugin",
                            mapOf(
                                "pluginId" to plugin.id,
                                "error" to error,
                            ),
                        )
                        failedIds.add(plugin.id to error)
                        continue
                    }

                    // Extract manifest to get the actual downloaded version
                    onProgress(progress + (0.4f / totalPlugins), "Extracting manifest for ${plugin.name}...")
                    val manifest = extractManifestFromJar(downloadedPath)
                    val actualVersion = manifest?.version ?: pluginInfo.version

                    // Rename to include actual version
                    val finalFile = File(pluginDir, "${plugin.id}-$actualVersion.jar")
                    val jarPath = finalFile.absolutePath

                    // Install the plugin. The manifest id is what the loader refuses on, so
                    // residency is asked about that rather than the wizard's expectation.
                    onProgress(progress + (0.6f / totalPlugins), "Loading ${plugin.name}...")
                    val installResult =
                        stageAndInstall(
                            downloadedFile = File(downloadedPath),
                            finalFile = finalFile,
                            pluginId = manifest?.pluginId ?: plugin.id,
                            isResident = isResident,
                        ) { usableWizardInstallResult(dynamicPluginManager.installPlugin(it, enabled = true)) }

                    if (installResult.isSuccess) {
                        // Only a plugin that actually registered: `installPlugin` returns success
                        // with `state = DISABLED` when registration failed as binary-incompatible,
                        // and prompting then offers a second plugin to support a dead one.
                        val registered = installResult.getOrNull()?.state == PluginState.LOADED
                        if (manifest != null && registered) {
                            installedManifests.add(manifest)
                        } else if (manifest == null) {
                            // Installed, but its dependencies were never checked. Say so rather
                            // than leaving the batch quietly incomplete.
                            logger.warn(
                                LogCategory.SYSTEM,
                                "Installed a plugin whose manifest could not be read, so dependencies went unchecked",
                                mapOf("pluginId" to plugin.id),
                            )
                        }

                        // Persist the installation with actual version
                        PluginPersistence.addInstalledPlugin(
                            pluginId = plugin.id,
                            jarPath = jarPath,
                            enabled = true,
                            sourceUrl = pluginInfo.downloadUrl,
                            installedVersion = actualVersion,
                        )

                        logger.info(
                            LogCategory.SYSTEM,
                            "Plugin installed successfully",
                            mapOf(
                                "pluginId" to plugin.id,
                                "version" to actualVersion,
                            ),
                        )
                        installedIds.add(plugin.id)
                    } else {
                        val error = installResult.exceptionOrNull()?.message ?: "Installation failed"
                        logger.error(
                            LogCategory.SYSTEM,
                            "Failed to install plugin",
                            mapOf(
                                "pluginId" to plugin.id,
                                "error" to error,
                            ),
                        )
                        // No delete here: stageAndInstall owns whatever it put at the destination,
                        // and refuses outright rather than replacing anything already installed.
                        failedIds.add(plugin.id to error)
                    }
                } catch (e: Exception) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Exception installing plugin",
                        mapOf(
                            "pluginId" to plugin.id,
                        ),
                        e,
                    )
                    failedIds.add(plugin.id to (e.message ?: "Unknown error"))
                }
            }

            onProgress(1f, "Installation complete")

            // Report unmet dependencies once the whole batch is in, never per iteration.
            // Reporting inside the loop meant a selection of [jupyter-notebook, ai-gateway] - the
            // pick this feature exists for - prompted for the gateway while the wizard was still
            // two lines from installing it: the loop runs on IO, so the collector on Main showed
            // the dialog over the wizard, and taking Install raced the wizard's own download.
            // (The two paths write different filenames for one plugin id, so nothing collides at
            // the path level and the coalescing guard never sees a shared key.) After the loop,
            // `installedAndOnDisk` already contains everything the batch installed, so nothing
            // intra-batch is reported at all. This also covers the GitHub branch, which reports
            // nowhere else.
            installedManifests.forEach { installedManifest -> dependencyReporter.report(installedManifest) }

            // Log summary
            logger.info(
                LogCategory.SYSTEM,
                "Plugin installation complete",
                mapOf(
                    "total" to totalPlugins,
                    "installed" to installedIds.size,
                    "failed" to failedIds.size,
                ),
            )

            if (failedIds.isNotEmpty()) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Some plugins failed to install",
                    mapOf(
                        "failed" to failedIds.map { "${it.first}: ${it.second}" }.joinToString("; "),
                    ),
                )
            }

            // Return result with both installed and failed IDs
            Result.success(PluginInstallResult(installedIds, failedIds))
        }

    /**
     * Plugin ids that are installed AND usable, by the codebase's single definition.
     *
     * Recomputed per call rather than hoisted: the loop installs plugins, so a snapshot taken
     * before it would go stale exactly when a later entry in the batch depends on an earlier one.
     */
    private fun installedAndUsable(): Set<String> =
        PluginDependencyResolution.installedAndOnDisk(
            states = dynamicPluginManager.pluginStates.value,
            exists = { File(it).isFile },
            isIncompatible = { PluginCrashRegistry.isIncompatible(it) },
        )

    /**
     * The manifest id is authoritative, so a mismatch with the wizard's expectation is logged and
     * the install continues.
     */
    private fun warnOnIdMismatch(
        plugin: WizardPluginInfo,
        manifest: PluginManifest,
    ) {
        if (manifest.pluginId == plugin.id) return
        logger.warn(
            LogCategory.SYSTEM,
            "Plugin ID mismatch between expected and manifest",
            mapOf(
                "expectedId" to plugin.id,
                "manifestId" to manifest.pluginId,
                "githubUrl" to plugin.githubUrl,
            ),
        )
    }

    /**
     * Returns what the manager registered, so the caller's batch dependency report can include it
     * *and* check that it actually loaded - `installPlugin` returns success with
     * `state = DISABLED` for a registration-time binary incompatibility.
     */
    private suspend fun installFromGitHub(
        plugin: WizardPluginInfo,
        pluginDir: File,
        baseProgress: Float,
        totalPlugins: Int,
        onProgress: (Float, String) -> Unit,
    ): Result<DynamicPluginInfo> =
        withContext(Dispatchers.IO) {
            try {
                onProgress(baseProgress + (0.1f / totalPlugins), "Checking GitHub releases for ${plugin.name}...")

                val (owner, repo) =
                    ownerAndRepo(plugin.githubUrl)
                        ?: return@withContext Result.failure(Exception("Invalid GitHub URL format"))

                // Try to download from GitHub releases
                onProgress(baseProgress + (0.2f / totalPlugins), "Downloading ${plugin.name} from GitHub...")

                val stagedPath =
                    downloadFromGitHubRelease(owner, repo, pluginDir)
                        ?: return@withContext Result.failure(Exception("No JAR found in GitHub releases for $owner/$repo"))
                val staged = File(stagedPath)

                onProgress(baseProgress + (0.6f / totalPlugins), "Extracting manifest for ${plugin.name}...")

                // Read the manifest from the STAGED file. Nothing has touched the installed
                // artifact yet, so an unreadable manifest costs only the download.
                val manifest =
                    extractManifestFromJar(stagedPath)
                        ?: run {
                            SignedArtifact(staged).delete()
                            return@withContext Result.failure(
                                Exception("Downloaded JAR does not contain valid plugin manifest"),
                            )
                        }

                warnOnIdMismatch(plugin, manifest)

                onProgress(baseProgress + (0.7f / totalPlugins), "Installing ${plugin.name}...")

                // Same guarded promotion the Store path uses.
                val finalFile = finalFileForStaged(staged)
                val installResult =
                    stageAndInstall(
                        downloadedFile = staged,
                        finalFile = finalFile,
                        pluginId = manifest.pluginId,
                        isResident = isResident,
                    ) { usableWizardInstallResult(dynamicPluginManager.installPlugin(it, enabled = true)) }

                val installed =
                    installResult.getOrNull()
                        ?: return@withContext Result.failure(
                            installResult.exceptionOrNull() ?: Exception("Install failed"),
                        )

                recordGitHubInstall(manifest, finalFile, plugin.githubUrl, logger)

                Result.success(installed)
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to install GitHub plugin",
                    mapOf(
                        "pluginId" to plugin.id,
                        "githubUrl" to plugin.githubUrl,
                    ),
                    e,
                )
                Result.failure(e)
            }
        }

    /**
     * Download a JAR from GitHub releases.
     * Returns the path to the downloaded JAR, or null if not found.
     */
    private fun downloadFromGitHubRelease(
        owner: String,
        repo: String,
        pluginDir: File,
    ): String? {
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
                logger.debug(
                    LogCategory.SYSTEM,
                    "No releases found for $owner/$repo",
                    mapOf(
                        "responseCode" to connection.responseCode,
                    ),
                )
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
                    !name.contains("-test", ignoreCase = true)
                ) {
                    jarName = name
                    downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: continue
                    break
                }
            }

            if (downloadUrl.isEmpty()) {
                logger.debug(LogCategory.SYSTEM, "No JAR asset found in release for $owner/$repo")
                return null
            }

            logger.info(
                LogCategory.SYSTEM,
                "Downloading JAR from GitHub release",
                mapOf(
                    "name" to jarName,
                    "url" to downloadUrl,
                ),
            )

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
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to download JAR",
                    mapOf(
                        "responseCode" to jarConnection.responseCode,
                    ),
                )
                return null
            }

            // Save to a staging file, never straight to the installed name. Opening the final
            // file's stream truncates an installed artifact before the manifest has been read or
            // the loader consulted, so a refusal, or a stream that died halfway, left partial bytes
            // at the path `installed.json` still names.
            pluginDir.mkdirs()
            val targetFile = File(pluginDir, stagingNameFor(jarName))
            writeStagedDownload(jarConnection.inputStream, targetFile)

            logger.info(
                LogCategory.SYSTEM,
                "Downloaded JAR from GitHub release",
                mapOf(
                    "path" to targetFile.absolutePath,
                    "size" to targetFile.length(),
                ),
            )

            targetFile.absolutePath
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Error downloading from GitHub release",
                mapOf(
                    "owner" to owner,
                    "repo" to repo,
                ),
                e,
            )
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
    private fun getGitHubToken(): String? =
        System.getenv("GITHUB_TOKEN")
            ?: System.getProperty("GITHUB_TOKEN")
            ?: try {
                // Try to read from local.properties
                val localProps = File(System.getProperty("user.dir"), "local.properties")
                if (localProps.exists()) {
                    localProps
                        .readLines()
                        .firstOrNull { it.startsWith("GITHUB_TOKEN=") }
                        ?.substringAfter("=")
                        ?.trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                // No token means lower GitHub rate limits, not a failure
                logger.debug(
                    LogCategory.SYSTEM,
                    "Could not read GITHUB_TOKEN from local.properties",
                    mapOf("error" to e.toString()),
                )
                null
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
                    logger.debug(
                        LogCategory.SYSTEM,
                        "No plugin manifest found in JAR",
                        mapOf(
                            "jarPath" to jarPath,
                        ),
                    )
                    return null
                }

                val manifestJson = jar.getInputStream(manifestEntry).bufferedReader().readText()
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<PluginManifest>(manifestJson)
            }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to extract manifest from JAR",
                mapOf(
                    "jarPath" to jarPath,
                    "error" to (e.message ?: "unknown"),
                ),
            )
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
        onProgress: (Float, String) -> Unit,
    ): Result<PluginInstallResult> {
        // Convert IDs to WizardPluginInfo with minimal info
        val plugins =
            pluginIds.map { id ->
                WizardPluginInfo(
                    id = id,
                    name = id.substringAfterLast("."),
                    description = "",
                    version = "",
                )
            }
        return installPlugins(plugins, onProgress)
    }

    companion object {
        /**
         * Create a PluginInstallService with the given DynamicPluginManager.
         */
        fun create(dynamicPluginManager: DynamicPluginManager): PluginInstallService = PluginInstallService(dynamicPluginManager)
    }
}

/**
 * Marks a download still in flight. Deliberately breaks the `.jar` suffix so a directory scan cannot
 * mistake a half-written download for an installed plugin, and carries a nonce so two installs of
 * the same plugin cannot collide on one staging name.
 */
private val GITHUB_URL = Regex("https://github\\.com/([^/]+)/([^/]+)(?:/.*)?")

/**
 * Owner and repository from a GitHub URL, or null when it is not one.
 *
 * Top-level rather than a member: it needs nothing from the service, and that class sits on
 * detekt's function-count limit. `.git` is stripped because a clone URL is what people paste.
 */
internal fun ownerAndRepo(githubUrl: String): Pair<String, String>? {
    val match = GITHUB_URL.matchEntire(githubUrl.trim().trimEnd('/')) ?: return null
    return match.groupValues[1] to match.groupValues[2].removeSuffix(".git")
}

/** Records a completed GitHub install. Top-level for the same reason as [ownerAndRepo]. */
internal fun recordGitHubInstall(
    manifest: PluginManifest,
    finalFile: File,
    sourceUrl: String,
    logger: ComponentLogger,
) {
    PluginPersistence.addInstalledPlugin(
        pluginId = manifest.pluginId,
        jarPath = finalFile.absolutePath,
        enabled = true,
        sourceUrl = sourceUrl,
        installedVersion = manifest.version,
    )
    logger.info(
        LogCategory.SYSTEM,
        "GitHub plugin installed successfully",
        mapOf(
            "pluginId" to manifest.pluginId,
            "version" to manifest.version,
        ),
    )
}

private const val STAGING_MARKER = ".downloading."

internal fun stagingNameFor(installedName: String): String = "$installedName$STAGING_MARKER${System.nanoTime()}"

/** The installed name a staged download is destined for: its name with the staging marker removed. */
internal fun finalFileForStaged(staged: File): File {
    // Block body deliberately: as an expression body this fits ktlint's 140-column limit on one
    // line, which puts it over detekt's 120. The two gates disagree; this satisfies both.
    val installedName = staged.name.substringBefore(STAGING_MARKER)
    return File(staged.parentFile, installedName)
}

/**
 * Streams a download onto its staging file, removing a partial one if the stream dies.
 *
 * Top-level so the nesting lives here rather than deepening `downloadFromGitHubRelease`, which
 * detekt already watches. A partial staging file is this call's own litter: nothing else knows the
 * name, so nothing else will clean it up.
 */
internal fun writeStagedDownload(
    input: java.io.InputStream,
    target: File,
) {
    try {
        input.use { source ->
            target.outputStream().use { output ->
                source.copyTo(output)
            }
        }
    } catch (e: java.io.IOException) {
        // IOException rather than Exception: this is stream and file work, so that is the type it
        // fails with, and naming it satisfies detekt without a suppression.
        target.delete()
        throw e
    }
}

/**
 * A plugin jar and the signature sidecar that belongs to it.
 *
 * They move as a unit because they are one fact. A `.sig` asserts the store vetted exactly those
 * bytes, so a jar promoted without its sidecar loads unsigned, and a sidecar left beside different
 * bytes is worse than none. `RemotePluginRepository` writes the sidecar beside the DOWNLOAD path, so
 * promotion is the moment it has to travel; an earlier version of this moved only the jar and left
 * every signed store install loading without its signature.
 *
 * Both suffixes the loader knows are carried: `.sig` and the `.nosig` marker.
 */
internal class SignedArtifact(
    val jar: File,
) {
    private val sidecars: List<File>
        get() =
            listOf(
                File(PluginSignatureSidecar.pathFor(jar.absolutePath)),
                File(PluginSignatureSidecar.unsignablePathFor(jar.absolutePath)),
            )

    /** Every path this artifact occupies, whether or not a file is currently there. */
    private val footprint: List<File> get() = listOf(jar) + sidecars

    /** The first path of this artifact's footprint that is already taken, or null if all are free. */
    fun firstOccupiedPath(): File? = footprint.firstOrNull { it.exists() }

    /**
     * Move this artifact onto [destination] as a unit, or leave [destination] as it was found.
     *
     * The jar and its sidecar are one fact, but they are two file moves, and refusing an occupied
     * destination does not make two moves indivisible. The jar used to move first and the sidecar
     * after, outside any failure boundary: if the second move failed - the final `.sig` path being
     * a nonempty directory is enough, and a permission change between the check and the move will
     * do it too - the jar was left sitting at its scannable installed name with its signature
     * stranded at the staging name, and the occupied-destination refusal then blocked every retry.
     *
     * So each published path is recorded as it lands, and a failure deletes exactly those and
     * nothing else. Deleting rather than moving back is deliberate: every one of these paths was
     * verified free before the move, so deleting them cannot destroy an artifact this call did not
     * create, whereas a move back has a failure of its own that would leave the same mess. What is
     * lost is a download, which the retry re-fetches.
     */
    fun moveTo(destination: SignedArtifact) {
        val published = mutableListOf<File>()
        try {
            destination.jar.claimFrom(jar)
            published += destination.jar
            for (sidecar in sidecars.filter { it.isFile }) {
                val target = destination.sidecarMatching(sidecar, jar)
                target.claimFrom(sidecar)
                published += target
            }
        } catch (e: IOException) {
            // Newest first, though order only matters for readability: these are distinct paths.
            published.asReversed().forEach { runCatching { it.delete() } }
            throw e
        }
    }

    /** This artifact's path for [sidecar], which sits beside [sourceJar]. */
    private fun sidecarMatching(
        sidecar: File,
        sourceJar: File,
    ): File = File(sidecar.absolutePath.replace(sourceJar.absolutePath, jar.absolutePath))

    /** Remove the jar and any sidecar beside it. */
    fun delete() {
        jar.delete()
        sidecars.forEach { it.delete() }
    }
}

/**
 * Move [source] onto this path, failing rather than replacing whatever is already here.
 *
 * Not [atomicMoveFrom]: that passes `REPLACE_EXISTING`, and `ATOMIC_MOVE` on POSIX is `rename(2)`,
 * which overwrites the target silently - verified on this platform rather than assumed. A promotion
 * must never replace a file it has not established was absent, so this uses the plain move, whose
 * documented behaviour is to throw `FileAlreadyExistsException` instead. Atomicity buys nothing
 * here: the target is verified free, so there is no existing content a reader could catch torn.
 */
private fun File.claimFrom(source: File) {
    Files.move(source.toPath(), toPath())
}

/**
 * Install a staged download, refusing rather than overwriting anything already installed.
 *
 * Two refusals, both before a single byte moves, because a refusal that arrives afterwards cannot
 * be undone:
 *
 * **The plugin is still resident.** `disablePlugin` unregisters panels and sets DISABLED but never
 * unloads, so a user-disabled plugin keeps its id in the loader. If its recorded `jarPath` has also
 * gone stale, `installedAndOnDisk` stops counting it, the wizard offers it again, and `loadPlugin`
 * refuses the resident id. That refusal used to arrive after the download had been moved over the
 * installed artifact, and the failure branch then deleted the file `installed.json` still names.
 *
 * **The destination is occupied.** Anything already at the installed path belongs to an install that
 * is not this one, and this function will not replace it. An earlier version moved over it and tried
 * to roll back on failure; that grew a tail of failure paths of its own (a backup that will not
 * rename, a promotion that throws between backup and install, a restore that silently fails) and
 * each one could lose the artifact it existed to protect. Refusing has no such tail: nothing is
 * overwritten, so nothing needs undoing, and the working plugin on disk is never at risk.
 *
 * The refusal covers the artifact's whole footprint - the jar and both sidecar names - which is
 * what makes the cleanup unambiguous: every path this call writes was established free, so
 * removing it on failure cannot destroy somebody else's artifact.
 *
 * The bound on that claim, stated rather than papered over: the check and the move are two
 * operations, so it holds against anything already on disk, not against a second installer racing
 * this one. `ATOMIC_MOVE` cannot close the gap - on POSIX it is `rename(2)`, which replaces the
 * target silently - and NIO exposes no create-exclusive move. Promotion therefore uses a plain
 * non-replacing move, so a racing installer collides with `FileAlreadyExistsException` and this
 * call reverts, rather than the two silently overwriting each other. Serialising installs is the
 * only thing that would make the race impossible, and this function is not where that belongs:
 * both callers run on a single wizard install path today.
 *
 * [install] is the loader call, injected so the decision and the cleanup are testable without a
 * DynamicPluginManager.
 */
internal suspend fun stageAndInstall(
    downloadedFile: File,
    finalFile: File,
    pluginId: String,
    isResident: (String) -> Boolean,
    install: suspend (jarPath: String) -> Result<DynamicPluginInfo>,
): Result<DynamicPluginInfo> {
    val staged = SignedArtifact(downloadedFile)
    val destination = SignedArtifact(finalFile)
    val movingIntoPlace = downloadedFile.absolutePath != finalFile.absolutePath

    // The whole footprint, not just the jar: a stale `.sig` left beside a jar that was removed
    // by hand is still somebody else's file, and promoting an unsigned download next to it would
    // leave an old signature asserting bytes it never vetted - which PluginSignatureSidecar's own
    // contract calls out as the thing never to do. Refusing also means every path this call is
    // about to write was established free, which is what makes the cleanup below precise.
    val occupied = if (movingIntoPlace) destination.firstOccupiedPath() else null

    // Both refusals in one place, and both decided before a byte moves: a refusal that arrives
    // after the move cannot be undone.
    val refusal =
        when {
            isResident(pluginId) -> {
                "$pluginId is still loaded from an earlier session, so it cannot be reinstalled now. " +
                    "Restart BOSS, or remove it in the plugin manager, and try again."
            }

            occupied != null -> {
                "${occupied.name} already exists, so $pluginId was not reinstalled over it. " +
                    "Remove the existing plugin in the plugin manager, or delete that file, and try again."
            }

            else -> {
                null
            }
        }
    if (refusal != null) {
        // Only the download is removed. Whatever is installed stays exactly as it was - which
        // means NOT removing it when the download is the destination: there is no separate
        // download then, and deleting it would delete the installed jar this refusal protects.
        if (movingIntoPlace) staged.delete()
        return Result.failure(IllegalStateException(refusal))
    }

    // Inside the failure boundary, not before it. Promotion is two file moves and can fail
    // halfway; when it does, moveTo has already put the destination back the way it found it and
    // the download is cleared here, so the next attempt meets a free path rather than a refusal
    // caused by the previous attempt's wreckage.
    val promotion =
        if (movingIntoPlace) {
            // Was delete-then-renameTo, which works on Windows but leaves a window in which
            // neither file exists - a crash there loses a plugin jar.
            runCatching { staged.moveTo(destination) }
        } else {
            Result.success(Unit)
        }
    val result =
        if (promotion.isFailure) {
            // The loader is never called: there is nothing whole at the destination to load.
            staged.delete()
            Result.failure(promotion.exceptionOrNull() ?: IOException("could not promote $pluginId"))
        } else {
            try {
                install(finalFile.absolutePath)
            } catch (e: CancellationException) {
                // Cancellation is not a failed install, and swallowing it would break structured
                // concurrency. Clear what this call put there, then let it propagate.
                destination.delete()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Deliberately broad: the loader is injected and a plugin's own code runs inside
                // it, so what it can throw is not knowable here. Without this the exception
                // escaped past the cleanup and left an unloadable jar at the installed path.
                Result.failure(e)
            }
        }

    // Every path this touches was established free above and written only by this call, so the
    // cleanup cannot take an artifact that belongs to somebody else. After a failed promotion it
    // is a no-op - moveTo has already put the destination back.
    if (result.isFailure) destination.delete()
    return result
}

/** Reject unloaded binary-incompatible results before either wizard path persists success. */
internal fun usableWizardInstallResult(
    result: Result<DynamicPluginInfo>,
    exists: (String) -> Boolean = { File(it).isFile },
    isIncompatible: (String) -> Boolean = { PluginCrashRegistry.isIncompatible(it) },
): Result<DynamicPluginInfo> {
    val installed = result.getOrNull() ?: return result
    val id = installed.manifest.pluginId
    val usable =
        PluginDependencyResolution.installedAndOnDisk(
            states = mapOf(id to installed),
            exists = exists,
            isIncompatible = isIncompatible,
        )
    return if (id in usable) {
        result
    } else {
        Result.failure(IllegalStateException("Tool did not become usable after installation: $id"))
    }
}
