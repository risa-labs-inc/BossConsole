package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
 * @param repository the store repository, null when it never initialised (offline first run,
 *   or absent store credentials) - the prompt then says so instead of hanging
 * @param pluginDir where installed jars live, so a downloaded dependency is picked up by a
 *   later directory scan exactly like any other install
 */
class StoreMissingDependencyInstaller(
    private val dynamicPluginManager: DynamicPluginManager,
    private val repository: () -> PluginRepository?,
    private val pluginDir: () -> File,
) : MissingDependencyInstaller {
    private val logger = BossLogger.forComponent("MissingDependencyInstaller")

    override suspend fun displayNameFor(pluginId: String): String? =
        runCatching { repository()?.getPlugin(pluginId)?.getOrNull()?.displayName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    override suspend fun install(pluginId: String): Result<Unit> {
        val store =
            repository() ?: return failure(
                "The plugin store is not available. Check your connection and try again.",
            )
        return installFromStore(store, pluginId)
    }

    private suspend fun installFromStore(
        store: PluginRepository,
        pluginId: String,
    ): Result<Unit> {
        val info =
            runCatching { store.getPlugin(pluginId).getOrNull() }.getOrNull()
                ?: return failure("$pluginId was not found in the plugin store.")

        return store
            .downloadPlugin(pluginId, info.version, targetJar(pluginId, info).absolutePath)
            .fold(
                onSuccess = { jarPath -> load(jarPath, info) },
                onFailure = { error ->
                    logger.error(LogCategory.SYSTEM, "Failed to download a plugin dependency", error = error)
                    failure("Could not download ${info.displayName}: ${reason(error)}")
                },
            )
    }

    /**
     * The store's own filename shape, so a later update or uninstall recognises this jar as
     * that plugin instead of leaving a stray copy behind.
     */
    private fun targetJar(
        pluginId: String,
        info: PluginInfo,
    ) = File(pluginDir(), "${pluginId.replace('.', '_')}_${info.version}.jar")

    /**
     * Loads the downloaded jar through the manager directly.
     *
     * Deliberately not back through `PluginLoaderDelegateImpl.loadPlugin`, so a dependency
     * that has dependencies of its own does not chain prompts: the user answered one
     * question and should not be handed a second dialog as its consequence. Anything still
     * missing after this shows up the next time that plugin is installed or updated.
     */
    private suspend fun load(
        jarPath: String,
        info: PluginInfo,
    ): Result<Unit> {
        val error = dynamicPluginManager.installPlugin(jarPath).exceptionOrNull()
        return if (error == null) {
            Result.success(Unit)
        } else {
            // Leave no half-installed jar: a directory scan on the next launch would load it
            // without it ever having passed the checks it just failed.
            runCatching { File(jarPath).delete() }
            logger.error(LogCategory.SYSTEM, "Failed to load a downloaded plugin dependency", error = error)
            failure("Downloaded ${info.displayName} but could not load it: ${reason(error)}")
        }
    }

    private fun reason(error: Throwable) = error.message ?: "unknown error"

    private fun failure(message: String): Result<Unit> = Result.failure(IllegalStateException(message))
}
