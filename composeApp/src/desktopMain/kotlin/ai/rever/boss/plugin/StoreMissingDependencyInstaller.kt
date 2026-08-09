package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
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
 * The plugin manager is reached through two lambdas rather than held as an object: those are
 * the only two things wanted from it, and passing them makes the download, cleanup and
 * persistence rules testable without standing up a loader.
 *
 * @param repository the store repository, null when it never initialised (offline first run,
 *   or absent store credentials) - the prompt then says so instead of hanging
 * @param pluginDir where installed jars live, so a downloaded dependency is picked up by a
 *   later directory scan exactly like any other install
 * @param installedNow whether a plugin id is loaded in the manager that reported
 * @param load the load leg, i.e. `DynamicPluginManager.installPlugin`
 * @param persist records the install the way every other install path does; see [record]
 */
class StoreMissingDependencyInstaller(
    private val repository: () -> PluginRepository?,
    private val pluginDir: () -> File,
    private val installedNow: (pluginId: String) -> Boolean,
    private val load: suspend (jarPath: String) -> Result<*>,
    private val readManifest: (jarPath: String) -> PluginManifest? = { jarPath ->
        runCatching { PluginManifestReader.readFromJar(jarPath) }.getOrNull()
    },
    private val persist: (pluginId: String, jarPath: String, version: String, sourceUrl: String) -> Unit =
        { pluginId, jarPath, version, sourceUrl ->
            PluginPersistence.addInstalledPlugin(
                pluginId = pluginId,
                jarPath = jarPath,
                enabled = true,
                sourceUrl = sourceUrl,
                installedVersion = version,
            )
        },
) : MissingDependencyInstaller {
    private val logger = BossLogger.forComponent("MissingDependencyInstaller")

    override fun isInstalled(pluginId: String): Boolean = installedNow(pluginId)

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

        val target = targetJar(pluginId, info)
        // A jar already at this exact path is not ours to delete: a plugin can be on disk
        // without the manager having registered it (a load that failed transiently at
        // startup), and a failed download must not take the user's file with it.
        val preexisting = target.exists()
        return store
            .downloadPlugin(pluginId, info.version, target.absolutePath)
            .fold(
                onSuccess = { jarPath -> vetAndLoad(pluginId, jarPath, info) },
                onFailure = { error ->
                    // A download that dies mid-stream leaves a truncated jar at the final
                    // name, and nothing upstream removes it: `downloadPlugin` writes straight
                    // into the target and only deletes on a hash or signature rejection. Left
                    // there, every launch would find it, fail to read it and log the failure
                    // again.
                    if (!preexisting) discard(target.absolutePath)
                    logger.error(LogCategory.SYSTEM, "Failed to download a plugin dependency", error = error)
                    failure("Could not download ${info.displayName}: ${error.message ?: "unknown error"}")
                },
            )
    }

    /**
     * Where the downloaded jar goes: `<id_with_underscores>_<version>.jar` in the plugins
     * directory, so a later directory scan picks it up like any other install.
     *
     * Downloaded straight to its final name rather than through a `-downloading.jar` rename:
     * `downloadPlugin` writes the signature sidecar next to whatever path it was given, and a
     * rename afterwards would leave the `.sig` behind under the temporary name - so the jar
     * would load as unsigned. Partial files are handled by deleting on failure instead.
     *
     * Both parts are sanitised because both come from a store row, and a `version` of `../x`
     * would otherwise write outside the plugins directory. Note this scheme differs from
     * `PluginInstallService`'s `<id>-<version>.jar`; neither is canonical, and the loader finds
     * either, but a shared helper would stop them drifting further.
     */
    private fun targetJar(
        pluginId: String,
        info: PluginInfo,
    ) = File(pluginDir(), "${safe(pluginId.replace('.', '_'))}_${safe(info.version)}.jar")

    /**
     * Keeps only what a plugin jar name needs, so no store value can name a path.
     *
     * Path separators go, which is what stops a `version` of `../x` escaping the directory;
     * dots survive because versions are full of them and, with separators gone, cannot
     * traverse.
     */
    private fun safe(part: String) = part.replace(Regex("[^A-Za-z0-9.-]"), "_")

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
        val declared = readManifest(jarPath)
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
        val error = load(jarPath).exceptionOrNull()
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
                failure("${info.displayName} did not install as $pluginId. The store entry may be wrong.")
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
        runCatching { persist(pluginId, jarPath, version, info.downloadUrl) }
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
