package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.loader.FileHashing
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import ai.rever.boss.plugin.loader.SignatureVerificationResult
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.repository.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository that connects to the remote plugin store (Supabase Edge Function).
 *
 * Features:
 * - List and search plugins from the remote store
 * - Download plugins with progress tracking
 * - SHA-256 verification of downloaded JARs
 * - Local caching of downloaded JARs
 *
 * @param downloadCache Cache for downloaded plugin JARs
 */
class RemotePluginRepository(
    private val downloadCache: PluginDownloadCache = PluginDownloadCache(),
    private val storeVerifier: PluginSignatureVerifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS),
    // Injectable for tests so the downloadPlugin wiring (verify on cache
    // hits, verify-before-cache on fresh downloads) is exercisable without
    // the real store.
    private val downloadInfoProvider: suspend (pluginId: String, version: String?) -> DownloadInfoResponse = { pluginId, version ->
        if (version != null) {
            PluginStoreClient.getDownloadUrl(pluginId, version)
        } else {
            PluginStoreClient.getDownloadUrl(pluginId)
        }
    },
) : PluginRepository {
    private val logger = BossLogger.forComponent("RemotePluginRepository")

    /**
     * Enforce the store's anchor signature for a JAR whose SHA-256 has
     * already been verified to equal [sha256]. The signature must cover the
     * canonical anchor `pluginId|version|sha256` — binding identity and
     * version so that one legitimately signed store artifact can't be
     * substituted for another by rewriting the version row.
     *
     * When the caller requested a specific version, [requestedVersion] must
     * also equal the store-reported [versionLabel] — a mismatch fails before
     * any signature math. Precision on the guarantee: downgrade protection
     * therefore holds for EXPLICIT-version installs only. A "latest" install
     * ([requestedVersion] = null) has no independent notion of what the
     * newest version should be — a DB-write attacker who bumps an older,
     * legitimately signed version's published_at can serve it as latest and
     * its anchor verifies fine. Accepted residual risk under the same
     * DB-write threat model tracked in BossConsole#872.
     *
     * A rejected artifact triggers [onVerificationFailure] (cleanup: delete
     * the downloaded file, purge the cache entry, …) before throwing; a
     * missing signature currently warns and allows (rollout phase — flips to
     * hard-fail once the store backfill is complete and enforcement is
     * enabled).
     */
    internal fun enforceStoreSignature(
        sha256: String,
        signature: String?,
        pluginId: String,
        versionLabel: String,
        requestedVersion: String?,
        onVerificationFailure: () -> Unit,
    ) {
        if (requestedVersion != null && requestedVersion != versionLabel) {
            onVerificationFailure()
            throw DownloadException(
                "Store returned version $versionLabel but $requestedVersion was requested",
                pluginId,
                id,
            )
        }
        if (signature == null) {
            if (PluginSignatureEnforcement.enforceUnsigned) {
                onVerificationFailure()
                throw DownloadException(
                    "Store plugin has no signature and signature enforcement is enabled",
                    pluginId,
                    id,
                )
            }
            logger.warn(
                LogCategory.NETWORK,
                "Store plugin is unsigned — allowing for now, will be rejected once signature enforcement is enabled",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to versionLabel,
                ),
            )
            return
        }
        val anchor = PluginStoreTrust.versionAnchor(pluginId, versionLabel, sha256)
        val result = storeVerifier.verifySignedMessage(anchor, signature)
        if (!result.isVerified) {
            onVerificationFailure()
            val failure = result as? SignatureVerificationResult.Failed
            logger.error(
                LogCategory.NETWORK,
                "Plugin signature verification failed",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to versionLabel,
                ),
                failure?.error,
            )
            throw DownloadException(
                "Plugin signature verification failed: ${failure?.reason ?: "unknown"}",
                pluginId,
                id,
            )
        }
        logger.info(
            LogCategory.NETWORK,
            "Plugin signature verified",
            mapOf(
                "pluginId" to pluginId,
                "version" to versionLabel,
            ),
        )
    }

    /**
     * Delete a rejected/stale artifact, logging when the delete doesn't take
     * (e.g. a Windows file lock) so leftover artifacts are observable — the
     * install has already failed, so a survivor can't load, but it shouldn't
     * linger silently either.
     */
    private fun deleteOrWarn(
        file: File,
        context: String,
    ) {
        if (!file.delete() && file.exists()) {
            logger.warn(
                LogCategory.NETWORK,
                "Failed to delete $context — leftover file remains",
                mapOf(
                    "path" to file.absolutePath,
                ),
            )
        }
    }

    private val downloadHttpClient =
        HttpClient(CIO) {
            engine {
                requestTimeout = 300_000 // 5 minutes for large downloads
            }
        }

    /**
     * Cached plugin list from last refresh.
     */
    private var cachedPlugins: List<PluginInfo> = emptyList()

    /**
     * Active download progress flows by plugin ID.
     */
    private val downloadProgress = ConcurrentHashMap<String, MutableStateFlow<Float>>()

    override val id: String = "supabase-store"
    override val name: String = "BOSS Plugin Store"
    override val isLocal: Boolean = false

    override val isAvailable: Boolean
        get() = PluginStoreConfig.isInitialized && checkAvailability()

    private fun checkAvailability(): Boolean {
        // Check if config is initialized - actual health check is async
        return PluginStoreConfig.isInitialized
    }

    override suspend fun listPlugins(): Result<List<PluginInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    logger.warn(LogCategory.NETWORK, "Plugin store not initialized")
                    return@runCatching emptyList()
                }

                val response =
                    PluginStoreClient.listPlugins(
                        page = 1,
                        pageSize = 100, // Get first 100 plugins
                        sortBy = "downloads",
                    )

                val plugins = response.plugins.map { it.toPluginInfo() }
                cachedPlugins = plugins

                logger.info(
                    LogCategory.NETWORK,
                    "Listed remote plugins",
                    mapOf(
                        "count" to plugins.size,
                        "totalCount" to response.totalCount,
                    ),
                )

                plugins
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to list remote plugins", error = e)
            }
        }

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching PluginSearchResult(
                        plugins = emptyList(),
                        totalCount = 0,
                        page = filter.page,
                        pageSize = filter.pageSize,
                    )
                }

                val response = PluginStoreClient.searchPlugins(filter)
                val plugins = response.plugins.map { it.toPluginInfo() }

                logger.debug(
                    LogCategory.NETWORK,
                    "Searched remote plugins",
                    mapOf(
                        "query" to filter.query,
                        "resultCount" to plugins.size,
                        "totalCount" to response.totalCount,
                    ),
                )

                PluginSearchResult(
                    plugins = plugins,
                    totalCount = response.totalCount,
                    page = response.page,
                    pageSize = response.pageSize,
                )
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to search remote plugins", error = e)
            }
        }

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching null
                }

                val response = PluginStoreClient.getPlugin(pluginId)
                response?.toPluginInfo()
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to get remote plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching emptyList()
                }

                val response =
                    PluginStoreClient.getPlugin(pluginId)
                        ?: return@runCatching emptyList()

                // Convert each version to PluginInfo
                response.versions.map { version ->
                    PluginInfo(
                        pluginId = response.pluginId,
                        displayName = response.displayName,
                        version = version.version,
                        description = response.description,
                        author = response.authorName,
                        url = response.homepageUrl,
                        type = parsePluginType(response.type),
                        apiVersion = response.apiVersion,
                        minBossVersion = version.minBossVersion,
                        minIpcVersion = version.minIpcVersion,
                        size = version.jarSize,
                        sha256 = version.sha256,
                        dependencies = version.dependencies.map { it.pluginId },
                        changelog = version.changelog,
                        verified = response.verified,
                    )
                }
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to get plugin versions", mapOf("pluginId" to pluginId), e)
            }
        }

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    throw DownloadException("Plugin store not initialized", pluginId, id)
                }

                // Get download info
                val downloadInfo = downloadInfoProvider(pluginId, version)

                // Check cache first. getCachedJar only returns a file whose
                // SHA-256 equals downloadInfo.sha256, so the signature check
                // below binds the cached bytes to the store key too — a JAR
                // cached during the warn-and-allow window doesn't dodge
                // enforcement through the cache path.
                val cachedFile = downloadCache.getCachedJar(pluginId, downloadInfo.version, downloadInfo.sha256)
                if (cachedFile != null) {
                    logger.info(
                        LogCategory.NETWORK,
                        "Using cached JAR",
                        mapOf(
                            "pluginId" to pluginId,
                            "version" to downloadInfo.version,
                        ),
                    )
                    // Verify BEFORE copying so a rejected artifact never lands at
                    // targetPath; on failure the poisoned entry is purged through
                    // the cache's own API.
                    enforceStoreSignature(
                        sha256 = downloadInfo.sha256,
                        signature = downloadInfo.signature,
                        pluginId = pluginId,
                        versionLabel = downloadInfo.version,
                        requestedVersion = version,
                        onVerificationFailure = { downloadCache.removeCachedJar(pluginId, downloadInfo.version) },
                    )
                    cachedFile.copyTo(File(targetPath), overwrite = true)
                    PluginSignatureSidecar.persist(targetPath, downloadInfo.signature)
                    return@runCatching targetPath
                }

                // Initialize progress tracking
                val progressFlow = MutableStateFlow(0f)
                downloadProgress[pluginId] = progressFlow

                try {
                    logger.info(
                        LogCategory.NETWORK,
                        "Downloading plugin",
                        mapOf(
                            "pluginId" to pluginId,
                            "version" to downloadInfo.version,
                            "size" to downloadInfo.size,
                        ),
                    )

                    // Download with progress tracking
                    downloadHttpClient.prepareGet(downloadInfo.downloadUrl).execute { response ->
                        val channel = response.bodyAsChannel()
                        val totalBytes = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull() ?: downloadInfo.size
                        var downloadedBytes = 0L

                        File(targetPath).outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (!channel.isClosedForRead) {
                                val bytes = channel.readAvailable(buffer)
                                if (bytes > 0) {
                                    output.write(buffer, 0, bytes)
                                    downloadedBytes += bytes
                                    if (totalBytes > 0) {
                                        progressFlow.value = downloadedBytes.toFloat() / totalBytes
                                    }
                                }
                            }
                        }
                    }

                    // Verify SHA-256 — every published version must have a real
                    // hash. A blank or placeholder value is treated as a mismatch
                    // so tampered or unhashed JARs never load.
                    val actualSha256 = FileHashing.sha256(File(targetPath))
                    if (!actualSha256.equals(downloadInfo.sha256, ignoreCase = true)) {
                        deleteOrWarn(File(targetPath), "hash-mismatched download")
                        throw DownloadException(
                            "SHA-256 mismatch. Expected: ${downloadInfo.sha256}, Got: $actualSha256",
                            pluginId,
                            id,
                        )
                    }

                    // Verify the store's signature over that hash. The checksum
                    // above binds the local bytes to the hash; the signature binds
                    // the hash to the store's signing key, so a rewritten DB row
                    // or storage object can't smuggle a different JAR through.
                    enforceStoreSignature(
                        sha256 = actualSha256,
                        signature = downloadInfo.signature,
                        pluginId = pluginId,
                        versionLabel = downloadInfo.version,
                        requestedVersion = version,
                        onVerificationFailure = { deleteOrWarn(File(targetPath), "rejected download") },
                    )

                    // Persist the signature beside the JAR so load-time
                    // verification (which every install path funnels through) can
                    // re-check it independently of this download path.
                    PluginSignatureSidecar.persist(targetPath, downloadInfo.signature)

                    // Cache the downloaded JAR
                    downloadCache.cacheJar(pluginId, downloadInfo.version, File(targetPath))

                    progressFlow.value = 1f

                    logger.info(
                        LogCategory.NETWORK,
                        "Plugin downloaded successfully",
                        mapOf(
                            "pluginId" to pluginId,
                            "version" to downloadInfo.version,
                            "path" to targetPath,
                        ),
                    )

                    targetPath
                } finally {
                    // Two-arg remove: if a concurrent download of another version
                    // of the same plugin replaced our entry (progress is keyed by
                    // pluginId alone — pre-existing), don't yank its flow out.
                    downloadProgress.remove(pluginId, progressFlow)
                }
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to download plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = downloadProgress[pluginId]?.asStateFlow()

    override suspend fun refresh(): Result<Unit> = listPlugins().map { }

    /**
     * Rate a plugin in the remote store.
     *
     * Requires authentication (access token set in PluginStoreConfig).
     *
     * @param pluginId The plugin ID to rate
     * @param rating Rating from 1-5
     * @param review Optional review text
     * @return Result indicating success or failure
     */
    suspend fun ratePlugin(
        pluginId: String,
        rating: Int,
        review: String = "",
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = PluginStoreClient.ratePlugin(pluginId, rating, review)
                if (!response.success) {
                    throw PluginStoreException(response.error ?: "Failed to rate plugin")
                }
                logger.info(
                    LogCategory.NETWORK,
                    "Plugin rated",
                    mapOf(
                        "pluginId" to pluginId,
                        "rating" to rating,
                    ),
                )
            }.onFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to rate plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    /**
     * Check if the remote store is healthy.
     */
    suspend fun checkHealth(): Boolean =
        withContext(Dispatchers.IO) {
            if (!PluginStoreConfig.isInitialized) return@withContext false
            PluginStoreClient.checkHealth()
        }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun parsePluginType(type: String): ai.rever.boss.plugin.api.PluginType =
        when (type.lowercase()) {
            "tab" -> ai.rever.boss.plugin.api.PluginType.TAB
            "hybrid", "mixed" -> ai.rever.boss.plugin.api.PluginType.MIXED
            else -> ai.rever.boss.plugin.api.PluginType.PANEL
        }
}
