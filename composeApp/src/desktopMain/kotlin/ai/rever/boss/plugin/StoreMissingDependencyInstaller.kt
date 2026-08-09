package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.MissingDependencyInstaller
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
    private val persist: (pluginId: String, jarPath: String, info: PluginInfo) -> Unit =
        { pluginId, jarPath, info ->
            PluginPersistence.addInstalledPlugin(
                pluginId = pluginId,
                jarPath = jarPath,
                enabled = true,
                sourceUrl = info.downloadUrl,
                installedVersion = info.version,
            )
        },
) : MissingDependencyInstaller {
    private val logger = BossLogger.forComponent("MissingDependencyInstaller")

    /**
     * Owner of detached installs - deliberately never cancelled, like the delegate's
     * `reloadScope`. The prompt is driven from a window's coroutine scope, and closing that
     * window mid-download would otherwise abort the install and leave the partial jar behind.
     */
    private val installScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Detaches installs from the window that asked and coalesces them per plugin id.
     *
     * Coalescing is not a nicety here: two dependents can each raise a prompt for the same
     * missing plugin, so without it two Installs could download to the same path at once.
     */
    private val detachedInstalls = KeyedDetachedJobs<String, Result<Unit>>(installScope)

    override fun isInstalled(pluginId: String): Boolean = installedNow(pluginId)

    override suspend fun displayNameFor(pluginId: String): String? =
        runCatching { repository()?.getPlugin(pluginId)?.getOrNull()?.displayName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    override suspend fun install(pluginId: String): Result<Unit> =
        detachedInstalls.run(
            key = pluginId,
            onDetachedFailure = { error ->
                // The window closed while this ran, so nothing is left to show the failure to.
                logger.error(LogCategory.SYSTEM, "Detached dependency install failed", error = error)
            },
        ) {
            installOnce(pluginId)
        }

    private suspend fun installOnce(pluginId: String): Result<Unit> {
        // A second prompt for the same dependency can outlive the install that satisfied it.
        if (isInstalled(pluginId)) return Result.success(Unit)

        val store = repository()
        return if (store == null) {
            failure("The plugin store is not available. Check your connection and try again.")
        } else {
            installFromStore(store, pluginId)
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
        return store
            .downloadPlugin(pluginId, info.version, target.absolutePath)
            .fold(
                onSuccess = { jarPath -> loadAndRecord(pluginId, jarPath, info) },
                onFailure = { error ->
                    // A download that dies mid-stream leaves a truncated jar at the final
                    // name, and nothing upstream removes it: `downloadPlugin` writes straight
                    // into the target and only deletes on a hash or signature rejection. Left
                    // there, every launch would find it, fail to read it and log the failure
                    // again.
                    discard(target.absolutePath)
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
    private suspend fun loadAndRecord(
        pluginId: String,
        jarPath: String,
        info: PluginInfo,
    ): Result<Unit> {
        val error = load(jarPath).exceptionOrNull()
        if (error != null) {
            // Leave nothing half-installed: a directory scan on the next launch would find
            // this jar and load it without it ever having passed the checks it just failed.
            discard(jarPath)
            logger.error(LogCategory.SYSTEM, "Failed to load a downloaded plugin dependency", error = error)
            return failure("Downloaded ${info.displayName} but could not load it: ${error.message ?: "unknown error"}")
        }
        // Write the `installed.json` entry, as every other install path does. Not optional
        // bookkeeping: `setPluginEnabled` updates an existing entry and does nothing when there
        // is none, so a plugin known only by its presence on disk cannot be disabled
        // persistently - it would come back enabled on the next launch. The entry also carries
        // the version and source that update checking reads.
        runCatching { persist(pluginId, jarPath, info) }
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
}
