package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.requireDeferredVersion
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything [StoreVersionInstaller] needs from outside itself, with production defaults.
 *
 * Same shape as `InstallerHooks`, and for the same reason: without it the download, promotion,
 * rollback and cleanup rules can only be reasoned about, never tested. The two installers now differ
 * in what they do, not in how they are reached.
 */
class StoreVersionHooks(
    val readManifest: (jarPath: String) -> PluginManifest? = { jarPath ->
        runCatching { PluginManifestReader.readFromJar(jarPath) }.getOrNull()
    },
    val promoteFiles: (downloaded: String, target: File) -> Unit = { downloaded, target ->
        target.atomicMoveFrom(File(downloaded))
        PluginSignatureSidecar.persist(target.absolutePath, PluginSignatureSidecar.read(downloaded))
        PluginSignatureSidecar.delete(downloaded)
    },
    val discardFiles: (jarPath: String) -> Unit = { jarPath ->
        runCatching { File(jarPath).delete() }
        runCatching { PluginSignatureSidecar.delete(jarPath) }
    },
    val exists: (jarPath: String) -> Boolean = { File(it).isFile },
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
    /**
     * The passive notice a deferred (not-hot-reloadable, BossConsole#71) install shows instead
     * of the running instance simply picking up the new version.
     */
    val notifyDeferred: (displayName: String) -> Unit = { displayName ->
        StatusMessageManager.showMessage("$displayName was updated - restart BOSS to apply it", durationMs = 5000)
    },
)

/**
 * One "put the store's build in place of what is running" request.
 *
 * @param runningJarPath the jar currently loaded for this plugin, which the swap must neither
 *   overwrite while it is open nor lose if the new one fails to load.
 */
data class StoreVersionRequest(
    val pluginId: String,
    val version: String,
    val sourceUrl: String?,
    val runningJarPath: String?,
    val hasLiveInstance: Boolean,
)

/**
 * Replaces a running plugin with the version the store publishes.
 *
 * Split out of [PluginStoreVersionBridge] so the interesting paths - a target that collides with the
 * running jar, a half-promoted download, a swap that unloads and then fails to load - are reachable
 * from a test without a repository, a classloader or a plugins directory.
 *
 * The download rules are the ones `StoreMissingDependencyInstaller` documents at length, because the
 * same traps apply: stream into a `.part` sibling rather than onto the target, move the jar and its
 * `.sig` together, vet the declared manifest before loading, and leave nothing half-installed at a
 * name the next directory scan would pick up.
 */
internal class StoreVersionInstaller(
    private val pluginDir: () -> File,
    private val hooks: StoreVersionHooks = StoreVersionHooks(),
) {
    private val logger = BossLogger.forComponent("StoreVersionInstaller")

    suspend fun install(
        store: PluginRepository,
        request: StoreVersionRequest,
        unload: suspend (String) -> Result<Unit>,
        load: suspend (String) -> Result<Boolean>,
        onProgress: ((Float) -> Unit)? = null,
        onInstalling: (() -> Unit)? = null,
    ): Result<String> {
        val pluginId = request.pluginId
        val version = request.version
        val target = targetFor(pluginId, version, request.runningJarPath)
        val part = File("${target.absolutePath}.part")

        val downloaded =
            try {
                store
                    .downloadPlugin(pluginId, version, part.absolutePath, onProgress)
                    .getOrElse { error ->
                        hooks.discardFiles(part.absolutePath)
                        logger.error(LogCategory.SYSTEM, "Could not download the store version", error = error)
                        return failure("Could not download v$version: ${error.message ?: "unknown error"}")
                    }
            } catch (e: CancellationException) {
                // A cancelled download leaves a part file. It is ignored by the directory
                // scan (the suffix is deliberately not .jar), so this is tidiness rather
                // than safety - but a stale part file is what the next attempt truncates,
                // and leaving it behind makes the plugin directory unreadable over time.
                hooks.discardFiles(part.absolutePath)
                throw e
            }

        // Past the point of no return: the swap can no longer be abandoned safely, so a
        // caller offering Cancel withdraws it here rather than after the unload.
        onInstalling?.invoke()

        // And the work stops being cancellable, not just the button. Cancellation is
        // cooperative: a Cancel pressed between the last progress tick and this line
        // would otherwise throw out of `activate` - between the unload and the load -
        // and leave the plugin gone with nothing in its place, which is what the
        // detached scope and the withdrawn button both exist to prevent.
        return withContext(NonCancellable) { promoteAndActivate(request, downloaded, target, unload, load) }
    }

    /**
     * Move the verified download into place and swap the running plugin for it.
     *
     * Split from the download half so the whole swap runs under `NonCancellable` in
     * one expression; see the call site for why it must.
     */
    private suspend fun promoteAndActivate(
        request: StoreVersionRequest,
        downloaded: String,
        target: File,
        unload: suspend (String) -> Result<Unit>,
        load: suspend (String) -> Result<Boolean>,
    ): Result<String> {
        val version = request.version

        val moved = runCatching { hooks.promoteFiles(downloaded, target) }
        if (moved.isFailure) {
            // Both paths, because the move may already have succeeded: a sidecar step that then threw
            // would leave an unvetted, never-loaded jar at a scannable name for the next launch.
            hooks.discardFiles(downloaded)
            hooks.discardFiles(target.absolutePath)
            logger.error(
                LogCategory.SYSTEM,
                "Could not move the downloaded store version into place",
                error = moved.exceptionOrNull(),
            )
            return failure("Downloaded v$version but could not put it in place.")
        }

        return activate(request, target, unload, load)
    }

    private fun stageForRestart(
        request: StoreVersionRequest,
        target: File,
        declared: PluginManifest,
    ): Result<String> {
        val valid =
            runCatching {
                requireDeferredVersion(
                    declared,
                    pluginDir()
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.extension == "jar" }
                        .mapNotNull { hooks.readManifest(it.absolutePath) },
                )
            }
        if (valid.isFailure) {
            hooks.discardFiles(target.absolutePath)
            return failure(valid.exceptionOrNull()?.message ?: "Cannot stage this version for restart.")
        }
        return persistDeferred(request, target, declared)
    }

    private fun persistDeferred(
        request: StoreVersionRequest,
        target: File,
        declared: PluginManifest,
    ): Result<String> {
        val pluginId = request.pluginId
        val version = request.version
        val persisted =
            runCatching { hooks.persist(pluginId, target.absolutePath, declared.version, request.sourceUrl) }
        if (persisted.isFailure) {
            hooks.discardFiles(target.absolutePath)
            logger.warn(
                LogCategory.SYSTEM,
                "Could not record a deferred plugin update",
                mapOf("pluginId" to pluginId, "error" to (persisted.exceptionOrNull()?.message ?: "unknown")),
            )
            return failure("Downloaded v$version but could not record it for the next restart.")
        }
        logger.info(
            LogCategory.SYSTEM,
            "Deferred a store install to the next restart - this plugin owns a native surface",
            mapOf("pluginId" to pluginId, "version" to version),
        )
        hooks.notifyDeferred(declared.displayName)
        return Result.success(declared.version)
    }

    /**
     * Vet the downloaded jar, drop the running build and put the new one in its place.
     *
     * Split from the download half so neither is long enough to hide a step, and so a test can reach
     * this side without standing up a repository.
     */
    private suspend fun activate(
        request: StoreVersionRequest,
        target: File,
        unload: suspend (String) -> Result<Unit>,
        load: suspend (String) -> Result<Boolean>,
    ): Result<String> {
        val pluginId = request.pluginId
        val version = request.version
        val runningJarPath = request.runningJarPath
        // Vet before loading, for the same reason the dependency installer does: nothing binds a
        // store row to the plugin id its jar declares, and `installPlugin` acts on the incoming
        // manifest - a newer api jar starts a whole api hot swap, which is exactly what
        // NOT_USER_INSTALLABLE exists to keep out of a two-button dialog.
        val declared = hooks.readManifest(target.absolutePath)
        val declaredId = declared?.pluginId
        if (declaredId != pluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
            hooks.discardFiles(target.absolutePath)
            logger.warn(
                LogCategory.SYSTEM,
                "Refusing a store jar that declares a different plugin",
                mapOf("expected" to pluginId, "declared" to (declaredId ?: "unreadable")),
            )
            return failure("The store copy did not install as $pluginId. The store entry may be wrong.")
        }

        // This plugin owns a native OS peer bound to the classloader that created it
        // (BossConsole#71) - force-unloading that loader to swap in the update leaves every
        // open (and every future) surface unable to attach a view. Stage the new jar for the
        // next restart instead of touching the running instance. The old jar is deliberately
        // left in place too (unlike the normal path below, which lets PluginJarReconciler clean
        // it up next launch): the still-running classloader still has it open.
        if (request.hasLiveInstance && HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)) {
            return stageForRestart(request, target, declared)
        }

        // Force, because this is a deliberate replacement: the point is to drop the local build.
        val unloaded = unload(pluginId)
        if (unloaded.isFailure) {
            hooks.discardFiles(target.absolutePath)
            return failure(
                "Could not unload the running build: ${unloaded.exceptionOrNull()?.message ?: "unknown error"}",
            )
        }

        val loaded = load(target.absolutePath)
        val started = loaded.getOrNull() == true
        if (!started) {
            // Past the unload, so a bare failure would leave the user with no plugin at all and a
            // jar on disk that the next directory scan would try again. Put back what was running.
            hooks.discardFiles(target.absolutePath)
            val restored = restore(pluginId, runningJarPath, load)
            val why = loaded.exceptionOrNull()?.message ?: "it did not start"
            return failure(
                if (restored) {
                    "Could not install v$version ($why). Kept the build you were running."
                } else {
                    "Could not install v$version ($why), and the previous build could not be restored. " +
                        "Reinstall the plugin from the Toolbox."
                },
            )
        }

        // Record the new jar so the next launch loads it, WITH its store source: that is what stops a
        // store row whose signature has not been backfilled from reading as a local build.
        runCatching { hooks.persist(pluginId, target.absolutePath, declared.version, request.sourceUrl) }
            .onFailure { error ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Installed the store version but could not record it",
                    mapOf("pluginId" to pluginId, "error" to (error.message ?: "unknown")),
                )
            }

        // The local build's jar is deliberately left on disk. It is what the user was working on, and
        // PluginJarReconciler keeps one jar per plugin id at the next launch anyway; deleting
        // someone's build as a side effect of "show me the released one" would be its own bug.
        logger.info(
            LogCategory.SYSTEM,
            "Installed the store version of a plugin",
            mapOf("pluginId" to pluginId, "version" to declared.version),
        )
        return Result.success(declared.version)
    }

    /**
     * A name that is never the jar currently open in a classloader.
     *
     * The obvious name is deterministic (`<id>_<version>.jar`), which means the second store install
     * of one plugin resolves to the file the first one left running - and this promotes BEFORE it
     * unloads, so it would move onto a jar held open by a live loader (a hard failure on Windows) and
     * then `discardFiles` the running plugin's own jar on the way out. Reachable in one sitting:
     * install the store version, hot reload over it, click again.
     */
    private fun targetFor(
        pluginId: String,
        version: String,
        runningJarPath: String?,
    ): File {
        val dir = pluginDir()
        val base = "${safe(pluginId.replace('.', '_'))}_${safe(version)}"
        var candidate = File(dir, "$base.jar")
        var suffix = 1
        while (runningJarPath != null && candidate.absolutePath == File(runningJarPath).absolutePath) {
            candidate = File(dir, "$base-store$suffix.jar")
            suffix++
        }
        return candidate
    }

    /** Put the previous build back after a failed swap. False when there is nothing to put back. */
    private suspend fun restore(
        pluginId: String,
        runningJarPath: String?,
        load: suspend (String) -> Result<Boolean>,
    ): Boolean {
        if (runningJarPath == null || !hooks.exists(runningJarPath)) return false
        val restored = runCatching { load(runningJarPath).getOrNull() == true }.getOrDefault(false)
        if (!restored) {
            logger.error(
                LogCategory.SYSTEM,
                "Could not restore the previous plugin build after a failed store install",
                mapOf("pluginId" to pluginId, "jarPath" to runningJarPath),
            )
        }
        return restored
    }

    private fun failure(message: String): Result<String> = Result.failure(IllegalStateException(message))

    /** Keeps only what a jar name needs, so no store value can name a path. */
    private fun safe(part: String) = part.replace(Regex("[^A-Za-z0-9.-]"), "_")
}
