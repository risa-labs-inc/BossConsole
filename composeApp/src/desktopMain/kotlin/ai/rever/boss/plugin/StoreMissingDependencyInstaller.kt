package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Installs a missing plugin dependency from the plugin store.
 *
 * This is the part a plugin cannot do for itself, and the reason the prompt belongs in the
 * host at all: a plugin that finds `AiGatewayAPI == null` can only send the user to the
 * Toolbox to search for something by name. The host resolves an id against the store,
 * downloads the jar and loads it.
 *
 * Every failure message here is shown to the user, so each says what happened and what it
 * means for them rather than surfacing a transport error.
 *
 * The plugin manager is reached through lambdas rather than held as an object: they are the only
 * things wanted from it, and passing them makes the download, cleanup and persistence rules
 * testable without standing up a loader, a sandbox and two registries.
 *
 * @param repository the store repository, null when it never initialised (offline first run,
 *   or absent store credentials) - the prompt then says so instead of hanging
 * @param pluginDir where installed jars live, so a downloaded dependency is picked up by a
 *   later directory scan exactly like any other install
 * @param hooks everything this needs from outside itself; see [InstallerHooks]
 */
class StoreMissingDependencyInstaller(
    private val repository: () -> PluginRepository?,
    private val pluginDir: () -> File,
    private val hooks: InstallerHooks,
) : MissingDependencyInstaller {
    private val logger = BossLogger.forComponent("MissingDependencyInstaller")

    override fun isInstalled(pluginId: String): Boolean = hooks.installedNow(pluginId)

    override suspend fun displayNameFor(pluginId: String): String? =
        runCatching { repository()?.getPlugin(pluginId)?.getOrNull()?.displayName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    override suspend fun install(pluginId: String): Result<Unit> =
        DETACHED_INSTALLS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                // The window closed while this ran, so nothing is left to show the failure to.
                logger.error(LogCategory.SYSTEM, "Detached dependency install failed", error = error)
            },
        ) {
            // A second prompt for the same dependency can outlive the install that satisfied it.
            val store = repository()
            when {
                isInstalled(pluginId) -> {
                    Result.success(Unit)
                }

                store == null -> {
                    failure("The plugin store is not available. Check your connection and try again.")
                }

                else -> {
                    installFromStore(store, pluginId)
                }
            }
        }

    private suspend fun installFromStore(
        store: PluginRepository,
        pluginId: String,
    ): Result<Unit> {
        val info =
            runCatching { store.getPlugin(pluginId).getOrNull() }.getOrNull()
                ?: return failure("$pluginId was not found in the plugin store.")

        // `<id_with_underscores>_<version>.jar` in the plugins directory, so a later directory
        // scan picks it up like any other install. Both parts are sanitised because both come
        // from a store row. Note `PluginInstallService` writes `<id>-<version>.jar` and
        // `PluginUpdateBridge` a third shape; `PluginJarReconciler` knows all three and dedupes
        // by manifest id, so this is untidy rather than broken.
        val target = File(pluginDir(), "${safe(pluginId.replace('.', '_'))}_${safe(info.version)}.jar")
        // Download beside the target, never onto it. `downloadPlugin` streams into whatever path
        // it is given and `outputStream()` truncates on open, so downloading straight to the
        // final name destroys any jar already there the moment the connection opens - and a
        // download that then dies mid-stream leaves a truncated file at a name every subsequent
        // launch will try to load. Guarding with "was a file already here" does not help: by
        // then its contents are gone either way.
        //
        // The suffix deliberately does not end in `.jar`, so a part file left behind by a kill
        // is ignored by the directory scan rather than loaded. (`PluginInstallService` uses
        // `-downloading.jar`, which is scannable, and moves the jar without its sidecar.)
        val part = File("${target.absolutePath}.part")
        return store
            .downloadPlugin(pluginId, info.version, part.absolutePath)
            .fold(
                onSuccess = { downloaded -> promoteAndLoad(pluginId, downloaded, target, info) },
                onFailure = { error ->
                    discard(part.absolutePath)
                    logger.error(LogCategory.SYSTEM, "Failed to download a plugin dependency", error = error)
                    failure("Could not download ${info.displayName}: ${error.message ?: "unknown error"}")
                },
            )
    }

    /**
     * Move the completed download onto its final name, sidecar included, then load it.
     *
     * Both files move together: `downloadPlugin` writes the signature next to the path it was
     * given, so promoting the jar alone would leave the `.sig` under the part name and the jar
     * would load as unsigned.
     */
    private suspend fun promoteAndLoad(
        pluginId: String,
        downloaded: String,
        target: File,
        info: PluginInfo,
    ): Result<Unit> {
        val error = promote(downloaded, target).exceptionOrNull()
        if (error != null) {
            // Both paths, because the move may already have succeeded: a sidecar step that then
            // throws would otherwise leave an unvetted, never-loaded jar at a scannable name for
            // the next launch to pick up - the thing the `.part` suffix exists to prevent.
            discard(downloaded)
            discard(target.absolutePath)
            logger.error(LogCategory.SYSTEM, "Could not move a downloaded dependency into place", error = error)
            return failure("Downloaded ${info.displayName} but could not put it in place.")
        }
        return vetAndLoad(pluginId, target.absolutePath, info)
    }

    /**
     * Loads the downloaded jar and records it.
     *
     * Deliberately not back through [PluginLoaderDelegateImpl.loadPlugin], so a dependency
     * that has dependencies of its own does not chain prompts: the user answered one question
     * and should not be handed a second dialog as its consequence. Anything still missing
     * shows up the next time that plugin is installed or updated.
     */
    private suspend fun vetAndLoad(
        pluginId: String,
        jarPath: String,
        info: PluginInfo,
    ): Result<Unit> {
        // Vet the bytes BEFORE loading them. The id filter in `missingFor` only covers the id a
        // manifest *names*; nothing binds a store row to the id its jar *declares* (the
        // signature binds the hash to the row and the row to the store's key, not the plugin
        // identity), so an admin uploading the wrong jar is enough. Loading first and noticing
        // after is too late twice over: `installPlugin` inspects the incoming manifest and
        // starts a full api hot swap for a newer `ai.rever.boss.plugin.api` jar - the exact
        // thing NOT_USER_INSTALLABLE exists to keep out of a two-button dialog - and a jar that
        // declares some *other* installed plugin would be loaded, re-pointed at this path, and
        // then have that path deleted underneath it.
        val declared = hooks.readManifest(jarPath)
        val declaredId = declared?.pluginId
        if (declaredId != pluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
            discard(jarPath)
            logger.warn(
                LogCategory.SYSTEM,
                "Refusing a dependency jar that declares a different plugin",
                mapOf("expected" to pluginId, "declared" to (declaredId ?: "unreadable")),
            )
            return failure(
                "${info.displayName} did not install as $pluginId. The store entry may be wrong.",
            )
        }
        return loadAndRecord(pluginId, jarPath, info, declared.version)
    }

    private suspend fun loadAndRecord(
        pluginId: String,
        jarPath: String,
        info: PluginInfo,
        version: String,
    ): Result<Unit> {
        val error = hooks.load(jarPath).exceptionOrNull()
        // Belt and braces after the pre-load check: the manager registers what the jar declares,
        // so if the requested id still is not present the load did not do what it claimed.
        val wrongPlugin = error == null && !isInstalled(pluginId)
        if (error != null || wrongPlugin) {
            // Leave nothing half-installed: a directory scan on the next launch would find
            // this jar and load it without it ever having passed the checks it just failed.
            discard(jarPath)
            return if (wrongPlugin) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "A dependency jar loaded as a different plugin",
                    mapOf("expected" to pluginId, "jarPath" to jarPath),
                )
                // Not "the store entry may be wrong": the manifest was vetted before the load,
                // so by far the likeliest cause here is a plugin that loaded and then failed to
                // register - binary-incompatible against this host.
                failure(
                    "${info.displayName} downloaded but did not start. It may not be compatible with this " +
                        "version of BOSS.",
                )
            } else {
                logger.error(LogCategory.SYSTEM, "Failed to load a downloaded plugin dependency", error = error)
                failure("Downloaded ${info.displayName} but could not load it: ${error?.message ?: "unknown error"}")
            }
        }
        // Write the `installed.json` entry, as every other install path does. Not optional
        // bookkeeping: `setPluginEnabled` updates an existing entry and does nothing when there
        // is none, so a plugin known only by its presence on disk cannot be disabled
        // persistently - it would come back enabled on the next launch.
        //
        // The version comes from the jar's own manifest, not the store row: update checking
        // compares against it, and a row whose version string disagrees with its jar would make
        // every future comparison wrong. Same reason `PluginInstallService` reads the manifest.
        // Null rather than the empty string: the store's detail response never populates
        // `downloadUrl`, and recording "" would look like a known-empty source instead of an
        // absent one - and would fight the preserve-existing-sourceUrl logic later.
        runCatching { hooks.persist(pluginId, jarPath, version, info.downloadUrl.ifBlank { null }) }
            .onFailure { error ->
                // The plugin is loaded and usable; only the record failed, so this is not worth
                // turning a successful install into an error the user sees.
                logger.warn(
                    LogCategory.SYSTEM,
                    "Installed a dependency but could not record it",
                    mapOf("pluginId" to pluginId, "error" to (error.message ?: "unknown")),
                )
            }
        return Result.success(Unit)
    }

    /**
     * Removes a jar and its signature sidecar together.
     *
     * The pair matters: reinstalling the same version reuses the filename, so a surviving
     * `.sig` would meet fresh bytes and hard-fail at load - worse than being unsigned.
     */
    private fun discard(jarPath: String) {
        runCatching { File(jarPath).delete() }
        runCatching { PluginSignatureSidecar.delete(jarPath) }
    }

    private fun failure(message: String): Result<Unit> = Result.failure(IllegalStateException(message))

    /**
     * Move the downloaded jar and its signature onto the final name.
     *
     * `persist` rather than `write`, because `target` can already exist - reinstalling the same
     * version reuses the filename - and `persist` is the one that clears a stale sidecar when the
     * new download is unsigned. A leftover `.sig` beside fresh bytes is a hard load failure,
     * which is worse than being unsigned.
     */
    private fun promote(
        downloaded: String,
        target: File,
    ) = runCatching {
        hooks.promoteFiles(downloaded, target)
    }

    private companion object {
        /**
         * Owner of detached installs - deliberately never cancelled, like the delegate's
         * `reloadScope`. The prompt runs on a window's coroutine scope, and closing that window
         * mid-download would otherwise abort the install and leave the partial jar behind.
         */
        private val INSTALL_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /**
         * Detaches installs from the window that asked and coalesces them per plugin id.
         *
         * **Process-wide, not per instance.** One installer exists per window, so a per-instance
         * guard would not have covered the case that most needs it: two windows each prompting
         * for the same missing plugin would run two `downloadPlugin` calls writing the same
         * `<id>_<version>.jar` at once. Coalescing here means the second Install joins the
         * first job rather than racing it - and so loads into the manager that started it,
         * which is the multi-window limitation already documented on `MissingDependencyPrompt`.
         */
        private val DETACHED_INSTALLS = KeyedDetachedJobs<String, Result<Unit>>(INSTALL_SCOPE)
    }
}

/**
 * Everything [StoreMissingDependencyInstaller] needs from outside itself.
 *
 * Grouped rather than seven constructor parameters, which detekt was right to flag: these are one
 * concept - "how this talks to the plugin manager and the filesystem" - and the three with
 * defaults exist purely so tests can drive the paths that would otherwise need a real loader, a
 * real jar and a real `installed.json`.
 *
 * @param installedNow whether a plugin id is present and usable in the manager that reported
 * @param load the load leg, i.e. `DynamicPluginManager.installPlugin`
 * @param readManifest reads the downloaded jar's own manifest, so a store row that points at the
 *   wrong plugin is refused before `installPlugin` can act on the bytes
 * @param promoteFiles moves the completed download and its signature onto the final name;
 *   injected so a promotion that half-succeeds (jar moved, sidecar failed) is testable
 * @param persist records the install the way every other install path does
 */
class InstallerHooks(
    val installedNow: (pluginId: String) -> Boolean,
    val load: suspend (jarPath: String) -> Result<*>,
    val readManifest: (jarPath: String) -> PluginManifest? = { jarPath ->
        runCatching { PluginManifestReader.readFromJar(jarPath) }.getOrNull()
    },
    val promoteFiles: (downloaded: String, target: File) -> Unit = { downloaded, target ->
        target.atomicMoveFrom(File(downloaded))
        PluginSignatureSidecar.persist(target.absolutePath, PluginSignatureSidecar.read(downloaded))
        PluginSignatureSidecar.delete(downloaded)
    },
    val persist: (pluginId: String, jarPath: String, version: String, sourceUrl: String?) -> Unit =
        { pluginId, jarPath, version, sourceUrl ->
            PluginPersistence.addInstalledPlugin(
                pluginId = pluginId,
                jarPath = jarPath,
                enabled = true,
                sourceUrl = sourceUrl,
                installedVersion = version,
            )
        },
)

/**
 * Keeps only what a plugin jar name needs, so no store value can name a path.
 *
 * Path separators go, which is what stops a `version` of `../x` escaping the directory; dots
 * survive because versions are full of them and, with separators gone, cannot traverse.
 */
private fun safe(part: String) = part.replace(Regex("[^A-Za-z0-9.-]"), "_")
