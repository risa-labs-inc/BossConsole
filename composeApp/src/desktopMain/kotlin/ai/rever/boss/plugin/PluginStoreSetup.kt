package ai.rever.boss.plugin

import ai.rever.boss.config.GitHubConfig
import ai.rever.boss.config.SupabaseClientConfig
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.plugin.repository.LocalPluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.remote.PluginDownloadCache
import ai.rever.boss.plugin.repository.remote.PluginStoreConfig
import ai.rever.boss.plugin.repository.remote.PluginStoreRealtimeService
import ai.rever.boss.plugin.repository.remote.RemotePluginRepository
import ai.rever.boss.plugin.updater.PluginUpdateManager
import ai.rever.boss.plugin.updater.UpdateCheckerConfig
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Information about a system plugin that should always be installed.
 * System plugins are auto-downloaded from GitHub releases if missing.
 */
data class SystemPluginInfo(
    /** Unique plugin ID (e.g., "ai.rever.boss.plugin.api") */
    val pluginId: String,
    /** GitHub repository in format "owner/repo" */
    val githubRepo: String,
    /** Artifact prefix for JAR files (e.g., "boss-plugin-api") */
    val artifactPrefix: String,
    /** Load priority (lower = loads first) */
    val loadPriority: Int,
    /** If true, the JAR is downloaded but not registered for plugin loading.
     *  Used for runtime dependencies (e.g., microkernel runtime) that live in
     *  the plugins directory but are not loadable UI/service plugins. */
    val downloadOnly: Boolean = false,
    /** Minimum plugin version this host build requires. When the installed JAR
     *  is older (or its version can't be determined), it is updated
     *  synchronously BEFORE load instead of via the usual background
     *  check-for-next-launch. Set this when a host release changes the
     *  host↔plugin contract — e.g. editor-tab 1.4.0 bundles BossEditor after
     *  the host dropped it, so older plugin JARs cannot run on this host. */
    val minVersion: String? = null
)

/**
 * Sets up the plugin store infrastructure including:
 * - Local and remote plugin repositories
 * - Plugin update manager
 * - Download cache
 *
 * This is the central point for plugin store initialization.
 */
object PluginStoreSetup {
    private val logger = BossLogger.forComponent("PluginStoreSetup")

    private val manifestJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var initialized = false

    /**
     * Local plugin directory (installed plugins).
     */
    private val _pluginDir: File by lazy {
        BossDirectories.resolve("plugins").apply { mkdirs() }
    }

    /**
     * Download cache directory.
     */
    private val _cacheDir: File by lazy {
        BossDirectories.resolve("plugin-cache").apply { mkdirs() }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Tracks which [downloadOnly] plugins already have an in-flight background
     * update check on [scope], keyed by `pluginId`. Prevents repeated calls to
     * [ensureSystemPluginsInstalled] within the same session (e.g. from plugin
     * manager refreshes) from racing to download the same JAR.
     */
    private val inFlightUpdateChecks =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    /**
     * Whether microkernel mode is active (OOP plugins need the runtime JAR).
     *
     * Reads from [KernelBootstrap] when it's initialised, but always consults
     * the `BOSS_MODE` env var / `boss.mode` system property as a fallback so
     * that if `systemPlugins` is evaluated before the kernel bootstraps we
     * don't cache `false` forever and silently skip the runtime install.
     */
    private val isKernelMode: Boolean by lazy {
        // Reflection access — `ai.rever.boss.kernel.KernelBootstrap` lives in
        // a module excluded from the Windows-ARM64 build (no protoc binary
        // for that target, see settings.gradle.kts). A direct reference would
        // fail compilation there even though it'd run fine on every other
        // platform. The `BOSS_MODE` env-var fallback also covers the case
        // where the kernel hasn't bootstrapped yet at first read.
        val fromKernel = try {
            val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
            val companionCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap\$Companion")
            val companion = bootstrapCls.getDeclaredField("Companion").get(null)
            val instance = companionCls.getMethod("getInstance").invoke(companion)
            instance?.let { bootstrapCls.getMethod("isKernelMode").invoke(it) as? Boolean }
        } catch (_: Throwable) {
            null
        }
        if (fromKernel != null) return@lazy fromKernel

        val mode = System.getenv("BOSS_MODE") ?: System.getProperty("boss.mode", "")
        mode.equals("KERNEL", ignoreCase = true)
    }

    /**
     * List of system plugins that must always be installed.
     * These are auto-downloaded from GitHub releases if missing.
     * Ordered by load priority (lower = loads first).
     *
     * Sourced from the remote manifest (system_plugins table) via
     * [SystemPluginManifestService]: cache → built-in fallback, with startup
     * catch-up + Realtime sync. A live getter (not lazy) so system plugins
     * pushed mid-session are seen by later [ensureSystemPluginsInstalled]
     * runs; version-floor changes apply on next launch.
     */
    private val systemPlugins: List<SystemPluginInfo>
        get() = SystemPluginManifestService.currentList(isKernelMode)

    // Plugin infrastructure components
    private var _downloadCache: PluginDownloadCache? = null
    private var _localRepository: LocalPluginRepository? = null
    private var _remoteRepository: RemotePluginRepository? = null
    private var _repositoryManager: PluginRepositoryManager? = null
    private var _updateManager: PluginUpdateManager? = null
    private var _realtimeService: PluginStoreRealtimeService? = null

    /**
     * Download cache for plugin JARs.
     */
    val downloadCache: PluginDownloadCache?
        get() = _downloadCache

    /**
     * Local plugin repository.
     */
    val localRepository: LocalPluginRepository?
        get() = _localRepository

    /**
     * Remote plugin repository (BOSS Plugin Store).
     */
    val remoteRepository: RemotePluginRepository?
        get() = _remoteRepository

    /**
     * Repository manager aggregating all repositories.
     */
    val repositoryManager: PluginRepositoryManager?
        get() = _repositoryManager

    /**
     * Plugin update manager.
     */
    val updateManager: PluginUpdateManager?
        get() = _updateManager

    /**
     * Realtime service for live plugin store updates.
     */
    val realtimeService: PluginStoreRealtimeService?
        get() = _realtimeService

    /**
     * Initialize the plugin store infrastructure.
     *
     * This should be called early in the application lifecycle.
     */
    fun initialize() {
        if (initialized) {
            logger.debug(LogCategory.SYSTEM, "Plugin store already initialized")
            return
        }

        try {
            logger.info(LogCategory.SYSTEM, "Initializing plugin store infrastructure")

            // Publish the host IPC contract version so in-process plugins (e.g.
            // the plugin-manager) can judge store-version compatibility without
            // depending on :boss-ipc. Null on Windows-ARM64 (boss-ipc excluded),
            // where there are no out-of-process plugins to gate anyway.
            IpcCompatibility.hostVersion?.let { System.setProperty("boss.ipc.version", it) }

            // Create download cache
            _downloadCache = PluginDownloadCache(_cacheDir)

            // Create local repository
            _localRepository = LocalPluginRepository(_pluginDir)

            // Create repository manager
            _repositoryManager = PluginRepositoryManager().apply {
                addRepository(_localRepository!!)
            }

            // Initialize remote repository with Supabase credentials
            initializeRemoteRepository()

            // Create update manager
            _updateManager = PluginUpdateManager(
                repositoryManager = _repositoryManager!!,
                config = UpdateCheckerConfig(
                    checkIntervalMs = 3600000L // Check every hour
                ),
                // Gate store updates by host IPC compatibility so an
                // incompatible newer version is reported, never auto-installed.
                hostIpcVersion = IpcCompatibility.hostVersion ?: "1.0.0",
                isIpcCompatible = { IpcCompatibility.isInstallable(it) },
                // Gate by minBossVersion too: without this, an update built
                // against a newer host replaces the working jar and only THEN
                // gets rejected by the loader (Toolbox 1.8.4 on BOSS 9.2.25).
                hostBossVersion = AppVersion.currentVersionString(),
                // Gate by minApiVersion: lambda because the api layer resolves
                // later in startup (initializeApiLayer publishes the property).
                hostApiVersion = { System.getProperty("boss.api.version") ?: "" }
            )

            // Create and start realtime service for live updates
            _realtimeService = PluginStoreRealtimeService()

            // Sync the system-plugins manifest (startup catch-up + Realtime).
            // New rows install live; min_version bumps apply next launch.
            SystemPluginManifestService.startSync {
                ensureSystemPluginsInstalled()
            }

            initialized = true
            logger.info(LogCategory.SYSTEM, "Plugin store initialization complete", mapOf(
                "pluginDir" to _pluginDir.absolutePath,
                "cacheDir" to _cacheDir.absolutePath,
                "hasRemoteRepo" to (_remoteRepository != null)
            ))

        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to initialize plugin store", error = e)
        }
    }

    /**
     * Initialize the remote plugin repository with Supabase credentials.
     */
    private fun initializeRemoteRepository() {
        try {
            logger.info(LogCategory.NETWORK, "Initializing remote plugin repository")

            // Initialize the config with Supabase credentials
            PluginStoreConfig.initialize(
                functionUrl = SupabaseClientConfig.functionUrl,
                anonKey = SupabaseClientConfig.anonKey,
                accessToken = null // Will be set when user logs in
            )

            // Create remote repository
            _remoteRepository = RemotePluginRepository(_downloadCache!!)

            // Register with repository manager
            _repositoryManager?.addRepository(_remoteRepository!!)

            // Listen for auth state changes to update access token
            // Wait for Supabase to be initialized first
            scope.launch {
                // Wait for Supabase initialization before accessing auth
                SupabaseConfig.isInitialized.first { it }

                // Now safe to collect auth session status
                SupabaseConfig.auth.sessionStatus.collect { status: SessionStatus ->
                    val token = when (status) {
                        is SessionStatus.Authenticated -> status.session.accessToken
                        else -> null
                    }
                    logger.debug(LogCategory.AUTH, "Updating plugin store access token", mapOf(
                        "hasToken" to (token != null)
                    ))
                    PluginStoreConfig.accessToken = token
                }
            }

            // Check health in background
            scope.launch {
                val isHealthy = _remoteRepository?.checkHealth() ?: false
                logger.info(LogCategory.NETWORK, "Remote plugin store health check", mapOf(
                    "healthy" to isHealthy
                ))
            }

            // Start realtime service for live updates
            _realtimeService?.start()

            logger.info(LogCategory.NETWORK, "Remote plugin repository initialized")

        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to initialize remote repository", error = e)
            // Continue without remote repository - local plugins will still work
        }
    }

    /**
     * Check if the plugin store is initialized.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Get the plugin directory path.
     */
    fun getPluginDir(): File = _pluginDir

    /**
     * Get the cache directory path.
     */
    fun getCacheDir(): File = _cacheDir

    /**
     * Refresh all repositories.
     */
    suspend fun refresh() {
        _repositoryManager?.refreshAll()
    }

    /**
     * Clear the download cache.
     *
     * @return Number of files removed
     */
    fun clearCache(): Int {
        return _downloadCache?.clearCache() ?: 0
    }

    /**
     * Get cache statistics.
     *
     * @return Map containing cache size and file count
     */
    fun getCacheStats(): Map<String, Any> {
        val cache = _downloadCache ?: return emptyMap()
        return mapOf(
            "sizeBytes" to cache.getCacheSize(),
            "pluginCount" to cache.getCachedPluginCount(),
            "fileCount" to cache.getCachedFileCount()
        )
    }

    /**
     * Clean up and shutdown the plugin store.
     */
    fun shutdown() {
        logger.info(LogCategory.SYSTEM, "Shutting down plugin store")
        _realtimeService?.dispose()
        _updateManager?.dispose()
        PluginStoreConfig.clear()
        _downloadCache = null
        _localRepository = null
        _remoteRepository = null
        _repositoryManager = null
        _updateManager = null
        _realtimeService = null
        initialized = false
    }

    /**
     * Set the callback to be invoked when plugins change in realtime.
     * This should be called by the PluginManagerComponent to refresh its state.
     */
    fun setOnPluginsChangedCallback(callback: suspend () -> Unit) {
        _realtimeService?.onRefreshRequested = callback
    }

    /**
     * Ensure all system plugins are installed.
     * If a system plugin is missing, it will be auto-downloaded from GitHub releases.
     * This ensures core functionality is always available.
     */
    private suspend fun ensureSystemPluginsInstalled() {
        logger.info(LogCategory.SYSTEM, "Checking system plugins installation", mapOf(
            "systemPluginCount" to systemPlugins.size
        ))

        val installedPlugins = PluginPersistence.getInstalledPlugins()
        val installedIds = installedPlugins.map { it.pluginId }.toSet()

        for (systemPlugin in systemPlugins) {
            try {
                // For download-only plugins, check if JAR exists on disk (not in persistence)
                if (systemPlugin.downloadOnly) {
                    val existingJar = _pluginDir.listFiles()?.firstOrNull {
                        it.name.startsWith("${systemPlugin.artifactPrefix}-") && it.name.endsWith(".jar")
                    }
                    if (existingJar != null) {
                        // Runtime is already on disk — proceed with startup immediately.
                        // Kick off the update check in the background so a slow or
                        // rate-limited GitHub doesn't add up to 5 s to every launch.
                        // A newer version, if found, replaces the JAR on disk and
                        // is picked up on the next restart.
                        scheduleBackgroundUpdateCheck(systemPlugin, existingJar)
                        continue
                    }
                } else {
                    // Check if plugin JAR exists in persistence
                    val existingEntry = installedPlugins.find { it.pluginId == systemPlugin.pluginId }
                    val jarExists = existingEntry?.let { File(it.jarPath).exists() } ?: false

                    if (installedIds.contains(systemPlugin.pluginId) && jarExists) {
                        val jarFile = File(existingEntry.jarPath)
                        val installedVersion = extractVersionFromJarFileName(jarFile.name, systemPlugin.artifactPrefix)
                            ?: runCatching { readPluginManifest(jarFile)?.version }.getOrNull()
                        // Installed JAR is older than this host requires (or its
                        // version is unreadable, which only very old JARs are):
                        // fall through to the synchronous download below so the
                        // contract-breaking version is never loaded. If the
                        // download fails (offline), the old JAR still loads and
                        // the update is retried next launch.
                        val tooOldForHost = isTooOldForHost(installedVersion, systemPlugin.minVersion)
                        if (!tooOldForHost) {
                            // Plugin is on disk — proceed with startup using the
                            // current JAR. Kick off a background update check so
                            // newer releases are pulled in for the *next* launch
                            // (the running session keeps its already-loaded
                            // classloader — replacing on the fly would break
                            // anything currently holding a class reference).
                            scheduleBackgroundUpdateCheck(systemPlugin, jarFile)
                            logger.debug(LogCategory.SYSTEM, "System plugin already installed", mapOf(
                                "pluginId" to systemPlugin.pluginId
                            ))
                            continue
                        }
                        logger.info(LogCategory.SYSTEM, "System plugin older than this host requires - updating before load", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "installedVersion" to (installedVersion ?: "unknown"),
                            "minVersion" to (systemPlugin.minVersion ?: "")
                        ))
                    }
                }

                logger.info(LogCategory.SYSTEM, "System plugin missing - downloading from GitHub", mapOf(
                    "pluginId" to systemPlugin.pluginId,
                    "repo" to systemPlugin.githubRepo
                ))

                // Download from GitHub releases
                val downloaded = downloadSystemPluginFromGitHub(systemPlugin)
                if (downloaded) {
                    logger.info(LogCategory.SYSTEM, "Successfully downloaded system plugin", mapOf(
                        "pluginId" to systemPlugin.pluginId
                    ))
                } else {
                    logger.warn(LogCategory.SYSTEM, "Failed to download system plugin", mapOf(
                        "pluginId" to systemPlugin.pluginId
                    ))
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error ensuring system plugin installed", mapOf(
                    "pluginId" to systemPlugin.pluginId,
                    "error" to (e.message ?: "unknown")
                ), e)
            }
        }
    }

    /**
     * Kick off a non-blocking update check for a system plugin.
     *
     * The existing JAR stays in use for the current session regardless — we
     * never swap a JAR out from under a live classloader. A newer release is
     * downloaded in the background and replaces the file on disk for the next
     * startup. `PluginPersistence` is updated by [downloadSystemPluginFromGitHub]
     * for non-downloadOnly plugins so the new path is picked up automatically.
     *
     * Runs on [scope] so it survives any caller that cancels mid-startup.
     */
    private fun scheduleBackgroundUpdateCheck(
        systemPlugin: SystemPluginInfo,
        existingJar: File,
    ) {
        // Dedup: only one background update check per pluginId at a time.
        // compareAndSet gives us a lock-free test-and-set; if another coroutine
        // already holds the flag we skip.
        val flag = inFlightUpdateChecks.computeIfAbsent(systemPlugin.pluginId) {
            java.util.concurrent.atomic.AtomicBoolean(false)
        }
        if (!flag.compareAndSet(false, true)) {
            logger.debug(LogCategory.SYSTEM, "Background update check already in flight — skipping", mapOf(
                "pluginId" to systemPlugin.pluginId
            ))
            return
        }
        scope.launch {
            try {
                // Filename is fast and doesn't log noise. Older JARs shipped with
                // a non-kotlin-serialization-compatible `type` field in their
                // plugin.json, so reading the manifest would log an ERROR on
                // every startup even though the fallback handles it. Manifest
                // read is therefore only used when the filename lacks a version.
                val installedVersion = extractVersionFromJarFileName(existingJar.name, systemPlugin.artifactPrefix)
                    ?: runCatching { readPluginManifest(existingJar)?.version }.getOrNull()
                val latestVersion = fetchLatestReleaseVersion(systemPlugin.githubRepo)
                when {
                    latestVersion == null -> {
                        logger.debug(LogCategory.SYSTEM, "Background update check skipped (offline or rate-limited)", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "installedVersion" to (installedVersion ?: "unknown")
                        ))
                    }
                    installedVersion == null -> {
                        logger.info(LogCategory.SYSTEM, "Installed system plugin version unknown — refreshing in background", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "latestVersion" to latestVersion
                        ))
                        downloadSystemPluginFromGitHub(systemPlugin)
                    }
                    installedVersion == latestVersion -> {
                        logger.debug(LogCategory.SYSTEM, "System plugin up-to-date", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "version" to installedVersion
                        ))
                    }
                    !isNewerVersion(latestVersion, installedVersion) -> {
                        // Installed version is NEWER than the latest published release —
                        // e.g. a local dev build ahead of the store. Do NOT downgrade:
                        // downloadSystemPluginFromGitHub deletes other versions, which
                        // would clobber the local build. Only update when the published
                        // release is strictly newer (the else branch below).
                        logger.debug(LogCategory.SYSTEM, "Local system plugin newer than published — keeping local build", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "installedVersion" to installedVersion,
                            "latestVersion" to latestVersion
                        ))
                    }
                    else -> {
                        logger.info(LogCategory.SYSTEM, "Newer system plugin version available — updating in background", mapOf(
                            "pluginId" to systemPlugin.pluginId,
                            "installedVersion" to installedVersion,
                            "latestVersion" to latestVersion
                        ))
                        downloadSystemPluginFromGitHub(systemPlugin)
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Background update check failed", mapOf(
                    "pluginId" to systemPlugin.pluginId,
                    "error" to (e.message ?: "unknown")
                ))
            } finally {
                flag.set(false)
            }
        }
    }

    /**
     * Apply GitHub API auth + standard headers to a connection. When a
     * `GITHUB_TOKEN` is available (env var, system property, local.properties,
     * or `gh` CLI) the request uses authenticated rate limits (5000/hr instead
     * of 60/hr) — otherwise anonymous callers on shared IPs (CI, many BOSS
     * instances) silently hit 60/hr and the update check degrades to a no-op.
     */
    private fun java.net.HttpURLConnection.applyGitHubAuth() {
        setRequestProperty("Accept", "application/vnd.github.v3+json")
        setRequestProperty("User-Agent", "BossConsole")
        val token = try {
            GitHubConfig.getAuthContext().takeIf { it.isAuthenticated }?.token
        } catch (_: Exception) {
            null
        }
        if (!token.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    /**
     * Fetch the latest release's version string (tag name with the leading
     * "v" stripped) from a GitHub repository. Returns null if the call fails.
     *
     * A 403 from the API is usually a rate-limit hit — we log it at WARN so
     * stale-runtime bugs don't hide behind silent fallback.
     */
    private suspend fun fetchLatestReleaseVersion(githubRepo: String): String? {
        return withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
                connection = (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                    applyGitHubAuth()
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val status = connection.responseCode
                if (status == 403 || status == 429) {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    val reset = connection.getHeaderField("X-RateLimit-Reset")
                    logger.warn(LogCategory.SYSTEM, "GitHub API rate-limited while checking for runtime update", mapOf(
                        "repo" to githubRepo,
                        "status" to status,
                        "remaining" to (remaining ?: "unknown"),
                        "resetEpoch" to (reset ?: "unknown"),
                        "hint" to "set GITHUB_TOKEN in env or local.properties to raise the limit from 60/hr to 5000/hr"
                    ))
                    return@withContext null
                }
                if (status !in 200..299) {
                    logger.debug(LogCategory.SYSTEM, "GitHub releases API returned non-2xx", mapOf(
                        "repo" to githubRepo,
                        "status" to status
                    ))
                    return@withContext null
                }
                val responseText = connection.inputStream.bufferedReader().readText()
                Regex(""""tag_name"\s*:\s*"([^"]+)"""")
                    .find(responseText)
                    ?.groupValues
                    ?.get(1)
                    ?.removePrefix("v")
            } catch (e: Exception) {
                logger.debug(LogCategory.SYSTEM, "Failed to fetch latest release version", mapOf(
                    "repo" to githubRepo,
                    "error" to (e.message ?: "unknown")
                ))
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Extract the semver component from a plugin JAR filename.
     * Handles the `{prefix}-{version}.jar` and `{prefix}-{version}-all.jar`
     * patterns produced by Gradle. Returns null if the filename doesn't match.
     */
    internal fun extractVersionFromJarFileName(fileName: String, artifactPrefix: String): String? {
        val withoutPrefix = fileName.removePrefix("$artifactPrefix-")
        if (withoutPrefix == fileName) return null
        val version = withoutPrefix
            .removeSuffix(".jar")
            .removeSuffix("-all")
        return version.takeIf { it.matches(Regex("""\d+\.\d+\.\d+(?:[-+.][A-Za-z0-9.]+)*""")) }
    }

    /**
     * Download a system plugin from GitHub releases.
     *
     * @param plugin The system plugin info
     * @return true if download was successful, false otherwise
     */
    private suspend fun downloadSystemPluginFromGitHub(plugin: SystemPluginInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = "https://api.github.com/repos/${plugin.githubRepo}/releases/latest"
                logger.debug(LogCategory.SYSTEM, "Fetching latest release from GitHub", mapOf(
                    "url" to apiUrl
                ))

                // Fetch release info (authenticated when GITHUB_TOKEN is set)
                val connection = (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                    applyGitHubAuth()
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val status = connection.responseCode
                if (status == 403 || status == 429) {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    logger.warn(LogCategory.SYSTEM, "GitHub API rate-limited while downloading system plugin", mapOf(
                        "pluginId" to plugin.pluginId,
                        "repo" to plugin.githubRepo,
                        "status" to status,
                        "remaining" to (remaining ?: "unknown"),
                        "hint" to "set GITHUB_TOKEN in env or local.properties to raise the limit from 60/hr to 5000/hr"
                    ))
                    return@withContext false
                }
                if (status !in 200..299) {
                    logger.warn(LogCategory.SYSTEM, "GitHub releases API returned non-2xx", mapOf(
                        "pluginId" to plugin.pluginId,
                        "status" to status
                    ))
                    return@withContext false
                }

                val responseText = connection.inputStream.bufferedReader().readText()

                // Parse JSON to find the JAR asset
                val tagNameMatch = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(responseText)
                val tagName = tagNameMatch?.groupValues?.get(1) ?: "unknown"

                // The host's minimum must hold for the release we are about to
                // INSTALL, not just trigger the download: if the repo's latest
                // release still predates minVersion (release-order violation,
                // yanked release), installing it would persist the exact
                // contract-breaking JAR the gate exists to prevent. Keep
                // whatever is on disk and retry next launch instead.
                val requiredMin = plugin.minVersion
                if (requiredMin != null && isTooOldForHost(tagName.removePrefix("v"), requiredMin)) {
                    logger.error(LogCategory.SYSTEM, "Latest GitHub release is older than this host requires - not installing", mapOf(
                        "pluginId" to plugin.pluginId,
                        "repo" to plugin.githubRepo,
                        "latestTag" to tagName,
                        "minVersion" to requiredMin
                    ))
                    return@withContext false
                }

                // Find the JAR download URL (skips "-thin.jar" assets — see
                // pickPluginJarUrl).
                val jarUrl = pickPluginJarUrl(responseText, plugin.artifactPrefix)

                if (jarUrl == null) {
                    logger.warn(LogCategory.SYSTEM, "No JAR asset found in GitHub release", mapOf(
                        "pluginId" to plugin.pluginId,
                        "repo" to plugin.githubRepo,
                        "tag" to tagName
                    ))
                    return@withContext false
                }
                val jarFileName = jarUrl.substringAfterLast("/")
                val destFile = File(_pluginDir, jarFileName)
                // Stage the download in a sibling `.tmp` file and atomic-rename
                // on success so a failed mid-write download can never leave the
                // plugins directory with the old JAR deleted and no replacement.
                val tmpFile = File(_pluginDir, "$jarFileName.tmp")

                logger.info(LogCategory.SYSTEM, "Downloading system plugin JAR", mapOf(
                    "pluginId" to plugin.pluginId,
                    "version" to tagName,
                    "url" to jarUrl,
                    "dest" to destFile.absolutePath
                ))

                // Download the JAR into the tmp file first (authenticated so
                // release-asset fetches from private/gated repos work and share
                // the 5000/hr pool).
                val jarConn = (URL(jarUrl).openConnection() as java.net.HttpURLConnection).apply {
                    applyGitHubAuth()
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 60000
                }
                try {
                    jarConn.inputStream.use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    tmpFile.delete()
                    throw e
                } finally {
                    jarConn.disconnect()
                }

                // Verify the staged file before replacing anything
                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    tmpFile.delete()
                    logger.error(LogCategory.SYSTEM, "Downloaded JAR is empty or missing", mapOf(
                        "pluginId" to plugin.pluginId,
                        "file" to tmpFile.absolutePath
                    ))
                    return@withContext false
                }

                // IPC-compat gate: if the fetched JAR declares a minIpcVersion
                // this host can't satisfy, discard the tmp file and keep the
                // currently-installed version. Applies to every system plugin
                // (terminal-tab, microkernel runtime, …) — a plugin that moved
                // to a newer IPC contract must not be auto-installed onto an
                // older host that would then fail to load it at spawn time and
                // leave the user with a broken tab. Users keep the compatible
                // JAR they already have until BossConsole is updated. A blank
                // minIpcVersion (legacy JARs from before the field existed)
                // skips the check.
                //
                // Reflection access: `ai.rever.boss.ipc.IpcVersion` lives in
                // `:boss-ipc`, which is excluded from the Windows-ARM64 build
                // (no protoc binary for that target — see settings.gradle.kts).
                // When the class isn't on the classpath we skip the compat
                // check entirely; on Windows ARM64 there are no out-of-process
                // plugins to gate anyway.
                val minIpc = runCatching { readPluginManifest(tmpFile)?.minIpcVersion }
                    .getOrNull().orEmpty()
                val ipcReason = ipcIncompatibilityReason(minIpc)
                if (ipcReason != null) {
                    tmpFile.delete()
                    logger.warn(LogCategory.SYSTEM, "Downloaded plugin is IPC-incompatible — keeping existing JAR", mapOf(
                        "pluginId" to plugin.pluginId,
                        "minIpcVersion" to minIpc,
                        "reason" to ipcReason
                    ))
                    return@withContext false
                }

                // Atomic rename onto the final path. Overwrite if the file
                // exists (same version re-download). We move the new JAR into
                // place *before* deleting old versions and *before* updating
                // persistence so there's never a window where persistence
                // points to a JAR we've already unlinked.
                try {
                    java.nio.file.Files.move(
                        tmpFile.toPath(),
                        destFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    // Falls back to a best-effort rename on filesystems that
                    // don't support atomic moves (rare; typically cross-device).
                    java.nio.file.Files.move(
                        tmpFile.toPath(),
                        destFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }

                // Register in persistence (skip for download-only plugins like microkernel runtime).
                // Preserve the user's existing `enabled` choice and `sourceUrl` —
                // `addInstalledPlugin` does removeIf+add, so passing the defaults
                // would silently re-enable a user-disabled plugin and wipe sourceUrl
                // on every background update.
                if (!plugin.downloadOnly) {
                    val existing = PluginPersistence.getInstalledPlugins()
                        .find { it.pluginId == plugin.pluginId }
                    PluginPersistence.addInstalledPlugin(
                        pluginId = plugin.pluginId,
                        jarPath = destFile.absolutePath,
                        enabled = existing?.enabled ?: true,
                        sourceUrl = existing?.sourceUrl,
                        installedVersion = tagName.removePrefix("v")
                    )
                }

                logger.info(LogCategory.SYSTEM, "Downloaded system plugin successfully", mapOf(
                    "pluginId" to plugin.pluginId,
                    "version" to tagName,
                    "file" to destFile.name,
                    "size" to destFile.length()
                ))

                // Clean up older versioned JARs *after* the new JAR is on disk
                // and persistence is updated. Match by manifest pluginId, NOT
                // filename prefix: artifact prefixes can be prefixes of each
                // other (e.g. "boss-plugin-terminal" matches
                // "boss-plugin-terminal-tab-*.jar" and would delete the other
                // plugin's JAR). On Windows the JVM may hold a lock on a JAR
                // loaded earlier in this process; delete() will return false
                // silently. The new versioned JAR has a different filename so
                // it's unaffected; the stale file just lingers.
                _pluginDir.listFiles()?.filter {
                    it.name.endsWith(".jar") &&
                        it.name != jarFileName &&
                        readPluginManifest(it)?.pluginId == plugin.pluginId
                }?.forEach { oldFile ->
                    val deleted = oldFile.delete()
                    logger.debug(LogCategory.SYSTEM, "Removed old version", mapOf(
                        "file" to oldFile.name,
                        "deleted" to deleted
                    ))
                }

                true
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to download system plugin from GitHub", mapOf(
                    "pluginId" to plugin.pluginId,
                    "repo" to plugin.githubRepo,
                    "error" to (e.message ?: "unknown")
                ), e)
                false
            }
        }
    }

    /**
     * Load persisted plugins using the provided DynamicPluginManager.
     * This should be called during application startup after the DynamicPluginManager is initialized.
     *
     * Bundled plugins are loaded first (from bundled-plugins directory), then persisted plugins.
     * If any system plugins are missing, they are auto-downloaded from GitHub releases.
     *
     * @param dynamicPluginManager The plugin manager to use for loading
     * @return Map of plugin IDs to their load results
     */
    suspend fun loadPersistedPlugins(
        dynamicPluginManager: ai.rever.boss.components.plugin.DynamicPluginManager
    ): Map<String, Result<ai.rever.boss.components.plugin.DynamicPluginInfo>> {
        val loadBeganMs = System.currentTimeMillis()
        val results = mutableMapOf<String, Result<ai.rever.boss.components.plugin.DynamicPluginInfo>>()

        // Steps 1-4 are JAR scans, copies, downloads, and the installed.json
        // read; callers arrive on Dispatchers.Main, so keep the disk churn off
        // the UI thread. The actual plugin loading below stays on the caller:
        // loadPlugin self-dispatches to IO and register() needs Main.
        val persistedPlugins = withContext(Dispatchers.IO) {
            // 1. Copy bundled plugins to ~/.boss/plugins if not already present
            copyBundledPluginsToPluginDir(dynamicPluginManager)

            // 2. Ensure all system plugins are installed (auto-download if missing)
            ensureSystemPluginsInstalled()

            // 3. Remove stale duplicate versions of the same plugin and repoint
            //    installed.json at the kept JAR — different writers use different
            //    filename conventions, so multiple versions can accumulate and an
            //    older JAR could otherwise shadow a newer one at scan time.
            runCatching { PluginJarReconciler.reconcilePluginDir(_pluginDir) }
                .onFailure { e ->
                    logger.warn(LogCategory.SYSTEM, "Plugin dir reconcile failed", mapOf(
                        "error" to (e.message ?: "unknown")
                    ))
                }

            // 3b. Resolve the runtime API layer from the reconciled dir: the
            //     shared ApiClassLoader must parent every plugin classloader
            //     created below, so this has to precede all plugin loads.
            dynamicPluginManager.initializeApiLayer(_pluginDir)

            // 4. Read persisted plugins (including bundled ones now in plugin dir)
            PluginPersistence.getInstalledPlugins()
        }

        if (persistedPlugins.isEmpty()) {
            logger.info(LogCategory.SYSTEM, "No persisted plugins to load")
            return results
        }

        logger.info(LogCategory.SYSTEM, "Loading persisted plugins", mapOf(
            "count" to persistedPlugins.size
        ))

        val entries = persistedPlugins.map { entry ->
            ai.rever.boss.components.plugin.PersistedPluginEntry(
                pluginId = entry.pluginId,
                jarPath = entry.jarPath,
                enabled = entry.enabled
            )
        }

        val persistedResults = dynamicPluginManager.loadPersistedPlugins(entries)
        results.putAll(persistedResults)

        // Repoint installed.json at what actually loaded: a stale persisted
        // path that got re-resolved by pluginId (see DynamicPluginManager.
        // loadPersistedPlugins) would otherwise re-trigger the fallback on
        // every startup until something else re-persisted the entry. No-op
        // when paths already agree (the common case).
        withContext(Dispatchers.IO) {
            val persistedById = persistedPlugins.associateBy { it.pluginId }
            for ((pluginId, result) in persistedResults) {
                val loaded = result.getOrNull() ?: continue
                val persisted = persistedById[pluginId] ?: continue
                if (persisted.jarPath != loaded.jarPath) {
                    PluginPersistence.addInstalledPlugin(
                        pluginId = pluginId,
                        jarPath = loaded.jarPath,
                        enabled = persisted.enabled,
                        sourceUrl = persisted.sourceUrl,
                        installedVersion = loaded.manifest.version
                    )
                    logger.info(LogCategory.SYSTEM, "Repointed persisted jar path at loaded jar", mapOf(
                        "pluginId" to pluginId,
                        "jarPath" to loaded.jarPath
                    ))
                }
            }
        }

        logger.info(LogCategory.SYSTEM, "Persisted plugin load complete", mapOf(
            "count" to results.size.toString(),
            "elapsedMs" to (System.currentTimeMillis() - loadBeganMs).toString()
        ))
        return results
    }

    /**
     * Copy bundled plugins from app resources to ~/.boss/plugins directory.
     * Only copies if the plugin is not already installed or if the bundled version is newer.
     */
    private fun copyBundledPluginsToPluginDir(
        dynamicPluginManager: ai.rever.boss.components.plugin.DynamicPluginManager
    ) {
        logger.info(LogCategory.SYSTEM, "Starting bundled plugin copy check", mapOf(
            "pluginDir" to _pluginDir.absolutePath
        ))

        val bundledDir = dynamicPluginManager.getBundledPluginsDirectory()
        logger.info(LogCategory.SYSTEM, "Bundled plugins directory", mapOf(
            "path" to bundledDir.absolutePath,
            "exists" to bundledDir.exists(),
            "isDirectory" to bundledDir.isDirectory
        ))

        if (!bundledDir.exists() || !bundledDir.isDirectory) {
            logger.warn(LogCategory.SYSTEM, "No bundled plugins directory found", mapOf(
                "path" to bundledDir.absolutePath
            ))
            return
        }

        val jarFiles = bundledDir.listFiles { file ->
            file.isFile && file.extension == "jar"
        } ?: run {
            logger.warn(LogCategory.SYSTEM, "listFiles returned null for bundled dir")
            return
        }

        if (jarFiles.isEmpty()) {
            logger.warn(LogCategory.SYSTEM, "No JAR files found in bundled plugins directory", mapOf(
                "path" to bundledDir.absolutePath
            ))
            return
        }

        logger.info(LogCategory.SYSTEM, "Found bundled plugins to check", mapOf(
            "count" to jarFiles.size,
            "files" to jarFiles.map { it.name },
            "bundledDir" to bundledDir.absolutePath
        ))

        for (jarFile in jarFiles) {
            try {
                logger.debug(LogCategory.SYSTEM, "Processing bundled plugin JAR", mapOf(
                    "file" to jarFile.name,
                    "path" to jarFile.absolutePath
                ))

                // Read manifest to get plugin ID and version
                val manifest = readPluginManifest(jarFile)
                if (manifest == null) {
                    logger.warn(LogCategory.SYSTEM, "Could not read manifest from bundled plugin", mapOf(
                        "file" to jarFile.name
                    ))
                    continue
                }

                val pluginId = manifest.pluginId
                val bundledVersion = manifest.version

                logger.info(LogCategory.SYSTEM, "Read bundled plugin manifest", mapOf(
                    "pluginId" to pluginId,
                    "version" to bundledVersion
                ))

                // Check if already installed in persistence
                val installedPlugins = PluginPersistence.getInstalledPlugins()
                val existingPlugin = installedPlugins.find { it.pluginId == pluginId }

                logger.debug(LogCategory.SYSTEM, "Checking existing installation", mapOf(
                    "pluginId" to pluginId,
                    "existsInPersistence" to (existingPlugin != null),
                    "totalInstalledPlugins" to installedPlugins.size
                ))

                // Find ALL existing JARs for this plugin in the plugin directory, matched by
                // manifest pluginId — NOT filename prefix. Filename-derived prefixes collide
                // ("boss-plugin-terminal" is a prefix of "boss-plugin-terminal-tab-*.jar"),
                // which made one plugin's version checks and "remove old versions" step
                // operate on a *different* plugin's JARs.
                // This handles cases where user manually added a newer version with different filename.
                val existingJarsInPluginDir = _pluginDir.listFiles()?.filter {
                    it.name.endsWith(".jar") && readPluginManifest(it)?.pluginId == pluginId
                } ?: emptyList()

                logger.info(LogCategory.SYSTEM, "Bundled plugin installation check", mapOf(
                    "pluginId" to pluginId,
                    "existsInPersistence" to (existingPlugin != null),
                    "existingJarsInDir" to existingJarsInPluginDir.map { it.name }
                ))

                // Check if any existing JAR has same or newer version
                var shouldSkip = false
                var highestExistingVersion: String? = null

                for (existingJar in existingJarsInPluginDir) {
                    val existingManifest = readPluginManifest(existingJar)
                    if (existingManifest != null) {
                        val existingVersion = existingManifest.version
                        if (highestExistingVersion == null || isNewerVersion(existingVersion, highestExistingVersion)) {
                            highestExistingVersion = existingVersion
                        }
                        if (!isNewerVersion(bundledVersion, existingVersion)) {
                            logger.info(LogCategory.SYSTEM, "Found existing JAR with same/newer version - skipping", mapOf(
                                "pluginId" to pluginId,
                                "bundledVersion" to bundledVersion,
                                "existingVersion" to existingVersion,
                                "existingJar" to existingJar.name
                            ))
                            shouldSkip = true
                            break
                        }
                    }
                }

                if (shouldSkip) {
                    continue
                }

                // Also check persistence path if no JARs found by prefix
                if (existingJarsInPluginDir.isEmpty() && existingPlugin != null) {
                    val existingJar = File(existingPlugin.jarPath)
                    if (existingJar.exists()) {
                        val existingManifest = readPluginManifest(existingJar)
                        if (existingManifest != null && !isNewerVersion(bundledVersion, existingManifest.version)) {
                            logger.info(LogCategory.SYSTEM, "Bundled plugin already installed with same/newer version - skipping", mapOf(
                                "pluginId" to pluginId,
                                "bundledVersion" to bundledVersion,
                                "installedVersion" to existingManifest.version
                            ))
                            continue
                        }
                    }
                }

                if (highestExistingVersion != null) {
                    logger.info(LogCategory.SYSTEM, "Bundled plugin is newer - will update", mapOf(
                        "pluginId" to pluginId,
                        "bundledVersion" to bundledVersion,
                        "highestExistingVersion" to highestExistingVersion
                    ))
                } else {
                    logger.info(LogCategory.SYSTEM, "No existing version found - will copy bundled plugin", mapOf(
                        "pluginId" to pluginId
                    ))
                }

                // Remove old versions before copying
                existingJarsInPluginDir.forEach { oldJar ->
                    logger.info(LogCategory.SYSTEM, "Removing old version before copy", mapOf(
                        "oldJar" to oldJar.name
                    ))
                    oldJar.delete()
                }

                // Copy to plugin directory
                val destFile = File(_pluginDir, jarFile.name)
                logger.info(LogCategory.SYSTEM, "Copying bundled plugin", mapOf(
                    "from" to jarFile.absolutePath,
                    "to" to destFile.absolutePath
                ))

                jarFile.copyTo(destFile, overwrite = true)

                logger.info(LogCategory.SYSTEM, "Copied bundled plugin to plugin directory", mapOf(
                    "pluginId" to pluginId,
                    "version" to bundledVersion,
                    "destPath" to destFile.absolutePath,
                    "fileSize" to destFile.length()
                ))

                // Register in persistence (addInstalledPlugin handles both add and update).
                // Preserve sourceUrl in addition to enabled — addInstalledPlugin does
                // removeIf+add, so omitting these would wipe whatever the user / store
                // workflow had written previously.
                PluginPersistence.addInstalledPlugin(
                    pluginId = pluginId,
                    jarPath = destFile.absolutePath,
                    enabled = existingPlugin?.enabled ?: true,
                    sourceUrl = existingPlugin?.sourceUrl,
                    installedVersion = bundledVersion
                )

                logger.info(LogCategory.SYSTEM, "Registered bundled plugin in persistence", mapOf(
                    "pluginId" to pluginId
                ))

            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error copying bundled plugin", mapOf(
                    "file" to jarFile.name,
                    "error" to (e.message ?: "unknown")
                ), e)
            }
        }

        logger.info(LogCategory.SYSTEM, "Finished bundled plugin copy check")
    }

    /**
     * Read plugin manifest from a JAR file.
     */
    private fun readPluginManifest(jarFile: File): ai.rever.boss.plugin.api.PluginManifest? {
        return try {
            java.util.jar.JarFile(jarFile).use { jar ->
                val entry = jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                    ?: return null
                val content = jar.getInputStream(entry).bufferedReader().readText()
                manifestJson.decodeFromString<ai.rever.boss.plugin.api.PluginManifest>(content)
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to read plugin manifest", mapOf(
                "file" to jarFile.name
            ), e)
            null
        }
    }

    /**
     * Returns a human-readable reason if a plugin declaring [minIpcVersion]
     * is incompatible with this host's IPC contract, or null when it is
     * compatible / unknown / unenforceable.
     *
     * Used to gate auto-downloads of system plugins (terminal-tab, microkernel
     * runtime, …): an incompatible JAR is discarded so the user keeps a working
     * version instead of one the spawner would reject at load time.
     *
     * - Blank [minIpcVersion] (legacy JARs from before the field existed)
     *   returns null — nothing to enforce.
     * - Reflection is used because `ai.rever.boss.ipc.IpcVersion` lives in
     *   `:boss-ipc`, which is excluded from the Windows-ARM64 build (no protoc
     *   binary — see settings.gradle.kts). When the class is absent we return
     *   null; that target has no out-of-process plugins to gate. Any other
     *   reflection failure also returns null (never block an install on a
     *   check we couldn't run) but is logged.
     */
    internal fun ipcIncompatibilityReason(minIpcVersion: String): String? {
        if (minIpcVersion.isBlank()) return null
        return try {
            val ipcCls = Class.forName("ai.rever.boss.ipc.IpcVersion")
            val instance = ipcCls.getField("INSTANCE").get(null)
            // CURRENT is a `const val`, exposed as a static field — there is no
            // `getCURRENT()` getter to reflect on.
            val hostVersion = ipcCls.getField("CURRENT").get(null) as String
            val result = ipcCls
                .getMethod("isCompatible", String::class.java, String::class.java)
                .invoke(instance, minIpcVersion, hostVersion)
            // CompatResult is sealed; Incompatible carries a `reason` String.
            val incompatCls = Class.forName("ai.rever.boss.ipc.IpcVersion\$CompatResult\$Incompatible")
            if (incompatCls.isInstance(result)) {
                incompatCls.getMethod("getReason").invoke(result) as? String
            } else null
        } catch (_: ClassNotFoundException) {
            // boss-ipc not on this build's classpath (Windows ARM64).
            null
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "IPC compat check failed; allowing install", mapOf(
                "minIpcVersion" to minIpcVersion,
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    /**
     * Check if version1 is newer than version2.
     *
     * Numeric major.minor.patch comparison; a segment's non-numeric suffix
     * counts only as its numeric prefix ("0-rc1" -> 0). On a numeric tie, a
     * version WITH a pre-release suffix is OLDER than one without
     * (1.4.0-rc1 < 1.4.0) — this comparator gates whether a system plugin
     * satisfies the host's [SystemPluginInfo.minVersion], and a pre-release
     * must not pass for its release. Internal for test access.
     */
    internal fun isNewerVersion(version1: String, version2: String): Boolean {
        fun numericParts(v: String) =
            v.split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        fun hasPreReleaseSuffix(v: String) =
            v.split(".").any { seg -> seg.any { !it.isDigit() } }

        val v1Parts = numericParts(version1)
        val v2Parts = numericParts(version2)

        for (i in 0 until maxOf(v1Parts.size, v2Parts.size)) {
            val v1 = v1Parts.getOrElse(i) { 0 }
            val v2 = v2Parts.getOrElse(i) { 0 }
            if (v1 > v2) return true
            if (v1 < v2) return false
        }
        // Numeric tie: a release is newer than its own pre-release.
        return hasPreReleaseSuffix(version2) && !hasPreReleaseSuffix(version1)
    }

    /**
     * True when the host mandates a minimum plugin version and the installed
     * version is below it — or can't be determined at all (only very old JARs
     * lack a readable version). Internal for test access.
     */
    internal fun isTooOldForHost(installedVersion: String?, minVersion: String?): Boolean {
        if (minVersion == null) return false
        return installedVersion == null || isNewerVersion(minVersion, installedVersion)
    }

    /**
     * Pick the plugin JAR asset URL from a GitHub release JSON payload.
     * Skips "-thin.jar" assets — a module's default :jar output, missing
     * everything buildPluginJar bundles (editor-tab's BossEditor,
     * fluck-browser's tunnel deps, …) — which GitHub can list first.
     * Internal for test access.
     */
    internal fun pickPluginJarUrl(releaseJson: String, artifactPrefix: String): String? =
        Regex(""""browser_download_url"\s*:\s*"([^"]+${Regex.escape(artifactPrefix)}[^"]*\.jar)"""")
            .findAll(releaseJson)
            .map { it.groupValues[1] }
            .firstOrNull { !it.endsWith("-thin.jar") }
}