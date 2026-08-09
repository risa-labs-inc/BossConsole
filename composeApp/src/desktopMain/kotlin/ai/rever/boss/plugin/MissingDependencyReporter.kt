package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.MissingDependencyPrompt
import ai.rever.boss.components.plugin.PluginDependencyBus
import ai.rever.boss.components.plugin.PluginDependencyEventBus
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Raises the install-time prompt for dependencies a plugin declares but which are absent.
 *
 * A class, and not the private method it started as, because more than one host path installs
 * on a user's behalf and each of them should report:
 *
 * - `PluginLoaderDelegateImpl.loadPlugin`, which the plugin-manager's install and update flows
 *   reach directly;
 * - `PluginInstallService`, the first-run wizard - the path where several plugins are chosen at
 *   once, so the one where an unmet dependency is *most* likely;
 * - `PluginUpdateBridge`, where an update can add a dependency the installed version never had.
 *
 * What must not report is a **reload**: `resetPluginInstances`, the Toolbox's reload and the
 * evolver's hot reload all end in a load, and none of them is a user asking to install
 * anything. Startup restore, the bundled-plugin load and the api hot-swap's reload-all go
 * through `DynamicPluginManager.installPlugin` directly and so never reach here at all - which
 * is the point, since a prompt on those paths would be one dialog per plugin on every launch.
 *
 * Takes the two things it needs rather than a `DynamicPluginManager`, so what it decides is
 * testable without standing up a loader, a sandbox and two registries. [forManager] is the
 * shape every real call site wants.
 */
class MissingDependencyReporter(
    private val states: () -> Map<String, DynamicPluginInfo>,
    private val installer: MissingDependencyInstaller,
    private val bus: PluginDependencyBus = PluginDependencyEventBus,
    private val jarExists: (String) -> Boolean = { File(it).isFile },
) {
    private val logger = BossLogger.forComponent("MissingDependencyReporter")

    /**
     * The plugins that count as installed, shared by the report set and the Install guard.
     *
     * See `PluginDependencyResolution.installedAndOnDisk` for why one definition matters.
     */
    private fun installedPluginIds() = PluginDependencyResolution.installedAndOnDisk(states(), jarExists)

    /**
     * Report whatever [manifest] declares and does not have.
     *
     * Fire-and-forget. A missing dependency is worth mentioning but never worth failing an
     * install the user asked for, or making them wait on a window that may not exist yet.
     */
    fun report(manifest: PluginManifest) {
        runCatching {
            val installed = installedPluginIds()
            logUnofferable(manifest, installed)

            PluginDependencyResolution
                .missingFor(manifest, installed)
                .forEach { missing ->
                    logger.info(
                        LogCategory.SYSTEM,
                        "Installed plugin declares a dependency that is not present",
                        mapOf(
                            "plugin" to missing.dependentPluginId,
                            "missing" to missing.missingPluginId,
                            "optional" to missing.optional,
                        ),
                    )
                    bus.report(MissingDependencyPrompt(missing, installer))
                }
        }.onFailure { error ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not check a plugin's dependencies",
                mapOf("plugin" to manifest.pluginId, "error" to (error.message ?: "unknown")),
            )
        }
    }

    /**
     * Log the dependencies that are missing but cannot be offered.
     *
     * `missingFor` filters system components out, so without this a plugin genuinely lacking the
     * api plugin or the microkernel runtime produced no prompt *and* no log line - the same
     * silence this feature exists to remove, in the case where a support log matters most.
     */
    private fun logUnofferable(
        manifest: PluginManifest,
        installed: Set<String>,
    ) {
        manifest.dependencies
            .filter { it.pluginId in PluginDependencyResolution.NOT_USER_INSTALLABLE && it.pluginId !in installed }
            .forEach { dependency ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Plugin declares a missing system component, which cannot be offered for install",
                    mapOf("plugin" to manifest.pluginId, "missing" to dependency.pluginId),
                )
            }
    }

    companion object {
        /**
         * The reporter every real install path wants: bound to one window's manager.
         *
         * Bound so the dependency loads into the same manager that was missing it, and reaching
         * the store lazily because `PluginStoreSetup` initialises during startup while a
         * reporter can outlive a store that never came up at all.
         */
        fun forManager(manager: DynamicPluginManager): MissingDependencyReporter {
            val installedNow: (String) -> Boolean = { pluginId ->
                pluginId in
                    PluginDependencyResolution.installedAndOnDisk(manager.pluginStates.value) { File(it).isFile }
            }
            return MissingDependencyReporter(
                states = { manager.pluginStates.value },
                installer =
                    StoreMissingDependencyInstaller(
                        repository = { PluginStoreSetup.remoteRepository },
                        pluginDir = { PluginStoreSetup.getPluginDir() },
                        installedNow = installedNow,
                        load = { jarPath -> manager.installPlugin(jarPath) },
                    ),
            )
        }
    }
}
