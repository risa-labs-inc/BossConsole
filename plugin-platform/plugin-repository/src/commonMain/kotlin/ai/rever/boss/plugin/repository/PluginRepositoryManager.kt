package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple plugin repositories and provides aggregated access.
 *
 * This allows searching across local and remote repositories, prioritizing
 * local sources when the same plugin exists in multiple repositories.
 */
class PluginRepositoryManager {
    private val logger = BossLogger.forComponent("PluginRepositoryManager")

    /**
     * Registered repositories by ID.
     */
    private val repositories = ConcurrentHashMap<String, PluginRepository>()

    /**
     * Add a repository.
     *
     * @param repository The repository to add
     */
    fun addRepository(repository: PluginRepository) {
        repositories[repository.id] = repository
        logger.info(
            LogCategory.SYSTEM,
            "Added repository",
            mapOf(
                "id" to repository.id,
                "name" to repository.name,
                "isLocal" to repository.isLocal,
            ),
        )
    }

    /**
     * Remove a repository.
     *
     * @param repositoryId The repository ID to remove
     */
    fun removeRepository(repositoryId: String) {
        repositories.remove(repositoryId)
        logger.info(
            LogCategory.SYSTEM,
            "Removed repository",
            mapOf(
                "id" to repositoryId,
            ),
        )
    }

    /**
     * Get a repository by ID.
     *
     * @param repositoryId The repository ID
     * @return The repository, or null if not found
     */
    fun getRepository(repositoryId: String): PluginRepository? = repositories[repositoryId]

    /**
     * Get all registered repositories.
     */
    fun getAllRepositories(): List<PluginRepository> = repositories.values.toList()

    /**
     * Get all local repositories.
     */
    fun getLocalRepositories(): List<PluginRepository> = repositories.values.filter { it.isLocal }

    /**
     * Get all remote repositories.
     */
    fun getRemoteRepositories(): List<PluginRepository> = repositories.values.filter { !it.isLocal }

    /**
     * List all plugins from all repositories.
     *
     * @return List of plugins with their source information
     */
    suspend fun listAllPlugins(): Result<List<PluginWithSource>> =
        coroutineScope {
            runCatching {
                val results =
                    repositories.values
                        .map { repo ->
                            async {
                                repo.listPlugins().getOrNull()?.map { plugin ->
                                    PluginWithSource(
                                        plugin = plugin,
                                        source =
                                            PluginSource(
                                                repositoryId = repo.id,
                                                repositoryName = repo.name,
                                                isLocal = repo.isLocal,
                                            ),
                                    )
                                } ?: emptyList()
                            }
                        }.awaitAll()

                // Flatten and deduplicate (prefer local sources)
                val pluginMap = mutableMapOf<String, PluginWithSource>()
                results.flatten().forEach { pluginWithSource ->
                    val existing = pluginMap[pluginWithSource.plugin.pluginId]
                    if (existing == null || pluginWithSource.source.isLocal) {
                        pluginMap[pluginWithSource.plugin.pluginId] = pluginWithSource
                    }
                }

                pluginMap.values.toList()
            }
        }

    /**
     * Search for plugins across all repositories.
     *
     * @param filter Search filter
     * @return Aggregated search results
     */
    suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        coroutineScope {
            runCatching {
                val results =
                    repositories.values
                        .map { repo ->
                            async {
                                repo.searchPlugins(filter).getOrNull()?.plugins?.map { plugin ->
                                    PluginWithSource(
                                        plugin = plugin,
                                        source =
                                            PluginSource(
                                                repositoryId = repo.id,
                                                repositoryName = repo.name,
                                                isLocal = repo.isLocal,
                                            ),
                                    )
                                } ?: emptyList()
                            }
                        }.awaitAll()

                // Flatten and deduplicate
                val pluginMap = mutableMapOf<String, PluginWithSource>()
                results.flatten().forEach { pluginWithSource ->
                    val existing = pluginMap[pluginWithSource.plugin.pluginId]
                    if (existing == null || pluginWithSource.source.isLocal) {
                        pluginMap[pluginWithSource.plugin.pluginId] = pluginWithSource
                    }
                }

                val plugins = pluginMap.values.map { it.plugin }

                PluginSearchResult(
                    plugins = plugins,
                    totalCount = plugins.size,
                    page = filter.page,
                    pageSize = filter.pageSize,
                )
            }
        }

    /**
     * Get a plugin from any repository.
     *
     * Searches local repositories first, then remote repositories.
     *
     * A repository that FAILS to answer is not the same as one that does not have the plugin, and the
     * two are no longer reported alike. `success(null)` now means every repository was asked and none
     * had it - the ordinary case for a plugin built locally and never published. A repository that
     * threw yields `failure(PluginLookupException)` **only when nothing was found**, so one broken
     * repository still cannot hide a hit from another.
     *
     * This mattered: swallowing the failure here is what made the first-run wizard report "Tool not
     * found in repository" for a plugin whose store row was present and valid, sending anyone
     * diagnosing it after the wrong thing entirely.
     *
     * **A failure is ALWAYS a [PluginLookupException], never a repository's own throwable.** That is
     * what bounds the message: `PluginLookupException` clips it and keeps the detail as `cause`, and
     * callers put the message in front of a user. A raw kotlinx malformed-input error appends the
     * entire offending document, which is not something to render in a wizard row.
     *
     * @param pluginId The plugin ID
     * @return Plugin with source information, `null` if no repository has it, or a failure if some
     *   repository could not be queried and no other repository had it
     */
    suspend fun getPlugin(pluginId: String): Result<PluginWithSource?> =
        coroutineScope {
            // Every repository that could not be asked, kept rather than discarded. Not reported on the
            // spot: a failing local repository must not stop the remote one from answering.
            val failures = mutableListOf<Throwable>()

            // A repository may THROW rather than return Result.failure - PluginRepository permits
            // either, and RemotePluginRepository happens to return while a third-party one need not -
            // so both are funnelled into one shape here instead of only one of them being handled.
            suspend fun ask(repo: PluginRepository): PluginInfo? {
                val result = runCatching { repo.getPlugin(pluginId) }.getOrElse { Result.failure(it) }
                result.exceptionOrNull()?.let { failures.add(it) }
                return result.getOrNull()
            }

            // Local repositories first, then remote.
            val ordered =
                repositories.values.filter { it.isLocal } + repositories.values.filter { !it.isLocal }
            for (repo in ordered) {
                val plugin = ask(repo) ?: continue
                return@coroutineScope Result.success(
                    PluginWithSource(
                        plugin = plugin,
                        source =
                            PluginSource(
                                repositoryId = repo.id,
                                repositoryName = repo.name,
                                isLocal = repo.isLocal,
                            ),
                    ),
                )
            }

            // Nothing had it. Report WHY if any repository could not be asked, since "absent" and
            // "unanswerable" call for different reactions from the user.
            val first = failures.firstOrNull() ?: return@coroutineScope Result.success(null)
            // Identity filter, not just drop(1): addSuppressed(itself) throws "Self-suppression not
            // permitted", and that IllegalArgumentException would then replace the diagnosis it was
            // meant to carry. Two repositories sharing one Throwable instance is all it takes.
            failures.drop(1).filter { it !== first }.forEach { first.addSuppressed(it) }
            Result.failure(PluginLookupException(pluginId, first))
        }

    /**
     * Get the full version history of a plugin from its source repository.
     *
     * Resolves the source the same way [getPlugin] does (local first, then
     * remote). Each returned [PluginInfo] carries that version's metadata,
     * including `minIpcVersion`, so callers can render compatibility per
     * version and offer downgrades.
     *
     * @param pluginId The plugin ID
     * @return Result with the list of versions (newest first), or empty if not found
     */
    suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        coroutineScope {
            runCatching {
                val orderedRepos =
                    repositories.values.filter { it.isLocal } +
                        repositories.values.filter { !it.isLocal }
                for (repo in orderedRepos) {
                    val versions = repo.getPluginVersions(pluginId).getOrNull()
                    if (!versions.isNullOrEmpty()) {
                        return@runCatching versions
                    }
                }
                emptyList()
            }
        }

    /**
     * Download a plugin from its source repository.
     *
     * @param pluginId The plugin ID
     * @param version The version to download (null for latest)
     * @param targetPath Path to save the downloaded JAR
     * @param onProgress Called with the download fraction (0.0 to 1.0) as bytes arrive
     * @return Result with the path to the downloaded file
     */
    suspend fun downloadPlugin(
        pluginId: String,
        version: String? = null,
        targetPath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> {
        // Find the plugin and its source. A lookup that FAILED is propagated as itself: reporting
        // "not found" here would re-create, one method along, exactly the wrong diagnosis that
        // getPlugin was changed to stop giving.
        val lookup = getPlugin(pluginId)
        val pluginWithSource =
            lookup.getOrNull()
                ?: return Result.failure(
                    lookup.exceptionOrNull() ?: PluginNotFoundException(pluginId, "any"),
                )

        val repository =
            repositories[pluginWithSource.source.repositoryId]
                ?: return Result.failure(
                    RepositoryException("Repository not found", pluginWithSource.source.repositoryId),
                )

        return repository.downloadPlugin(pluginId, version, targetPath, onProgress)
    }

    /**
     * Get download progress for a plugin.
     *
     * @param pluginId The plugin ID being downloaded
     * @return Flow of download progress, or null if not downloading
     */
    fun getDownloadProgress(pluginId: String): Flow<Float>? {
        for (repo in repositories.values) {
            val progress = repo.getDownloadProgress(pluginId)
            if (progress != null) {
                return progress
            }
        }
        return null
    }

    /**
     * Refresh all repositories.
     */
    suspend fun refreshAll(): Result<Unit> =
        coroutineScope {
            runCatching {
                repositories.values
                    .map { repo ->
                        async { repo.refresh() }
                    }.awaitAll()

                logger.info(
                    LogCategory.SYSTEM,
                    "Refreshed all repositories",
                    mapOf(
                        "count" to repositories.size,
                    ),
                )
            }
        }

    /**
     * Check if any updates are available for installed plugins.
     *
     * No production caller today: the Toolbox's update path goes
     * PluginUpdateBridge -> PluginUpdateManager.checkForUpdates, which uses this
     * manager only for lookups and downloads. It is kept because the repository
     * layer should answer the same question the updater does if the paths are
     * ever merged, and PluginRepositoryVersionTest drives it end to end so the
     * offer/refusal branches cannot rot untested.
     *
     * @param installedPlugins Map of plugin ID to installed version
     * @return List of plugins with available updates
     */
    suspend fun checkForUpdates(installedPlugins: Map<String, String>): Result<List<PluginWithSource>> =
        coroutineScope {
            runCatching {
                val updates = mutableListOf<PluginWithSource>()

                for ((pluginId, installedVersion) in installedPlugins) {
                    val lookup = getPlugin(pluginId)
                    val latestPlugin = lookup.getOrNull()
                    // Deliberately NOT propagated, unlike downloadPlugin: this is a batch over every
                    // installed plugin, and failing all of it because one lookup broke would hide
                    // updates that are perfectly available. It is logged instead of silently skipped,
                    // because "no update offered" and "we could not ask" are indistinguishable to a
                    // user staring at a Toolbox that shows nothing.
                    lookup.exceptionOrNull()?.let { failure ->
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Skipping update check for a plugin whose repository could not be queried",
                            mapOf("pluginId" to pluginId),
                            error = failure,
                        )
                    }
                    if (latestPlugin != null) {
                        offerUpdateIfNewer(pluginId, installedVersion, latestPlugin, updates)
                    }
                }

                updates
            }
        }

    /**
     * Append [latestPlugin] to [updates] when it is a genuine upgrade over the
     * installed version, and leave a log trace when the refusal came from an
     * unparseable store version rather than from an actual comparison.
     */
    private fun offerUpdateIfNewer(
        pluginId: String,
        installedVersion: String,
        latestPlugin: PluginWithSource,
        updates: MutableList<PluginWithSource>,
    ) {
        val candidateVersion = latestPlugin.plugin.version
        if (isNewerVersion(candidateVersion, installedVersion)) {
            updates.add(latestPlugin)
        } else if (SemanticVersion.parse(candidateVersion) == null) {
            // The old comparison made a guess here; the new one refuses. The refusal is
            // the right answer - you cannot order what you cannot read - but it must not
            // vanish without a trace, for the same reason the lookup failure above is
            // logged: "no update offered" and "we could not tell" must stay
            // distinguishable in the log.
            logger.debug(
                LogCategory.SYSTEM,
                "Update check did not offer a candidate whose store version is not parseable",
                mapOf("pluginId" to pluginId, "version" to candidateVersion),
            )
        }
    }

    /**
     * True when [candidate] is a genuine upgrade over [installed].
     *
     * Delegates to [SemanticVersion], which documents itself as the sole
     * version-comparison primitive for plugin update and floor checks, and which
     * PluginUpdateManager already uses. The hand-rolled comparison this
     * replaced split on "." and dropped any segment that was not a bare integer,
     * which shifted every later segment into the wrong position: "1.0.0+build.7"
     * became [1, 0, 7] - the "0+build" segment dropped, the 7 landing in the
     * PATCH slot - and so read as newer than an installed "1.0.0", offering an
     * update to the version already installed on every check.
     *
     * An unparseable [candidate] is never offered, because nothing can be said
     * about it. An unparseable [installed] with a parseable [candidate] IS
     * offered: that is a plugin whose recorded version is already broken, and
     * withholding the update would strand it there permanently.
     *
     * The live-path counterpart, `PluginUpdateManager.isNewerVersion` in
     * plugin-updater, fails CLOSED on an unparseable installed version instead
     * of open. The divergence is deliberate on both pages (see its KDoc) and
     * belongs in its own change to settle, but until then a plugin with an
     * unreadable installed record is offered an update by one path and withheld
     * by the other.
     *
     * Internal for test access.
     */
    internal fun isNewerVersion(
        candidate: String,
        installed: String,
    ): Boolean {
        val candidateVersion = SemanticVersion.parse(candidate)
        val installedVersion = SemanticVersion.parse(installed)
        // Single expression rather than early returns: detekt caps this function at
        // two, and widening the rule to keep a guard-clause shape would be the wrong
        // trade for three mutually exclusive cases.
        return when {
            candidateVersion == null -> false
            installedVersion == null -> true
            else -> candidateVersion > installedVersion
        }
    }
}
