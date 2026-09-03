package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.home.HomeCatalogAccess
import ai.rever.boss.crash.PluginCrashRecovery
import ai.rever.boss.crash.PluginCrashRecoveryCoordinator
import ai.rever.boss.crash.PluginRecoverySteps
import ai.rever.boss.crash.displayPluginId
import ai.rever.boss.plugin.MissingDependencyReporter
import ai.rever.boss.plugin.PluginBuildProbe
import ai.rever.boss.plugin.PluginLoaderDelegateImpl
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginRemoval
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.ProbedPlugin
import ai.rever.boss.plugin.StoreHomeCatalogProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop implementation of PluginLoaderDelegateSetup.
 *
 * Registers the PluginLoaderDelegateImpl so that dynamic plugins
 * (like plugin-manager) can interact with the plugin system.
 */
actual object PluginLoaderDelegateSetup {
    private val logger = BossLogger.forComponent("PluginLoaderDelegateSetup")

    /**
     * Register the PluginLoaderDelegate with the plugin context.
     *
     * @param context Plugin context for registration
     * @param dynamicPluginManager The dynamic plugin manager
     */
    actual fun register(
        context: PluginContext,
        dynamicPluginManager: DynamicPluginManager,
    ) {
        logger.info(LogCategory.SYSTEM, "Registering PluginLoaderDelegate for dynamic plugins")

        val delegate = PluginLoaderDelegateImpl(dynamicPluginManager)
        context.registerPluginAPI(delegate)

        // Re-enable and RBAC un-hide never go through the install reporters, so
        // a required dependency removed while the plugin sat disabled used to
        // come back with no prompt (#180). Per-manager: reporting must read
        // this window's pluginStates, and capturing the first window would
        // leave every other window offering installs into a disposed manager.
        // Bound once, outside the callback: forManager allocates the states
        // lambda and the installer, and every activation would pay for a
        // reporter that never changes for this manager.
        val missingDependencyReporter = MissingDependencyReporter.forManager(dynamicPluginManager)
        dynamicPluginManager.onPluginActivated = { manifest ->
            missingDependencyReporter.report(manifest)
        }

        // The home screen's store access: what can be installed, and how. Registered here with
        // the other process-wide wiring rather than from `PluginLoaderDelegateImpl`'s constructor,
        // where a process-global set up as a side effect of constructing an object was easy to
        // miss. First-wins is enforced inside `initialize`, matching the `== null` guards below.
        //
        // The installer resolves a manager per install rather than capturing this window's.
        // `register` runs per window, so capturing would mean closing that one window left every
        // other window's Install tile loading into a disposed manager - reporting success having
        // put the plugin somewhere nothing renders.
        HomeCatalogAccess.initialize(
            StoreHomeCatalogProvider(
                repository = { PluginStoreSetup.remoteRepository },
                installer = {
                    DynamicPluginManager.anyActiveManager()?.let { MissingDependencyReporter.installerFor(it) }
                },
            ),
        )

        // Give the API-layer hot swap a way to tear down plugin-hosting UI
        // before it closes any classloader (avoids NoClassDefFoundError from
        // Compose disposing a plugin's UI against a closed loader). Process-
        // wide + spans all windows, so set once; register() runs per window.
        if (DynamicPluginManager.pluginUiTeardown == null) {
            DynamicPluginManager.pluginUiTeardown = { delegate.teardownAllPluginTabs() }
        }
        // Per-plugin teardown for the shared uninstall path, so plugin-manager
        // updates and update notifications reload tab-hosting plugins cleanly.
        if (DynamicPluginManager.pluginTabsTeardown == null) {
            DynamicPluginManager.pluginTabsTeardown = { id -> delegate.teardownPluginTabs(id) }
        }
        // Panel counterpart, on the (re)register side: after a plugin's panel
        // factories are re-registered (reload/update/enable), reset its open
        // sidebar panel slots so they pick up the new build instead of keeping
        // the pre-reload component (#856).
        if (DynamicPluginManager.pluginPanelsRefresh == null) {
            DynamicPluginManager.pluginPanelsRefresh = { id, panelIds -> delegate.refreshPluginPanels(id, panelIds) }
        }
        // Restarting a plugin that depends on one being updated or removed, after the user
        // agreed to it. Closing its tabs is EDT work and reloading it needs the persisted jar
        // path, neither of which commonMain can reach - hence the hook rather than a direct call
        // from the manager.
        if (DependentRestartCoordinator.restartPlugin == null) {
            DependentRestartCoordinator.restartPlugin = { id -> delegate.resetPluginInstances(id) }
        }
        if (DependentRestartCoordinator.instanceCount == null) {
            DependentRestartCoordinator.instanceCount = { id -> delegate.getRunningInstanceCount(id) }
        }
        // Which build each plugin is running. The signals (signature sidecar, jar mtime) are on
        // disk, so the answer comes from here rather than from commonMain.
        if (DynamicPluginManager.pluginBuildProbe == null) {
            DynamicPluginManager.pluginBuildProbe = { id, displayName, version, jarPath, systemPlugin ->
                PluginBuildProbe.probe(ProbedPlugin(id, displayName, version, jarPath, systemPlugin))
            }
        }
        // Unloading a plugin and deleting its jar, sidecar and installed.json row. Invoked only by
        // the deliberate "Uninstall Plugin" flow, never by the shared unload path.
        if (DynamicPluginManager.pluginRemoval == null) {
            DynamicPluginManager.pluginRemoval = { id, jarPath, mgr -> PluginRemoval.remove(id, jarPath, mgr) }
        }
        if (DynamicPluginManager.pluginRemovalVeto == null) {
            DynamicPluginManager.pluginRemovalVeto = { id ->
                PluginRemoval.removalVeto(id, dynamicPluginManager.getBundledPluginsDirectory())
            }
        }
        // Lets the crash handler take a crashed plugin out instead of taking the
        // app down. Until this is wired, a plugin crash classifies as fatal and
        // terminates as it always did - which is the honest behaviour for a run
        // with no plugin layer (headless, or a crash before this point).
        // register() runs per window, so this captures the FIRST window's delegate for
        // the process lifetime. Safe because the two things the coordinator uses it
        // for are window-independent: teardownPluginTabs goes through
        // SplitViewStateRegistry.getAllStates(), and disableEverywhere iterates every
        // live manager. That is a property of the delegate, not of this seam, so it
        // is worth stating rather than re-deriving.
        // installIfAbsent, not check-then-set: register() runs per window and two
        // windows opening together could both build a coordinator. The loser was
        // harmless (they are equivalent), but a compare-and-set says so.
        PluginCrashRecovery.installIfAbsent { createCrashRecovery(delegate) }

        logger.debug(LogCategory.SYSTEM, "PluginLoaderDelegate registered successfully")
    }

    /**
     * Assemble the recovery steps from pieces that already exist.
     *
     * Nothing here is new machinery: quarantine is what the render-fault path
     * already uses, tab teardown is the same call an update/reload makes, and the
     * disable is the normal one, only applied to every window.
     *
     * [PluginCrashRegistry.recordRenderFault] rather than `recordCrash`: the
     * latter closes the plugin's tab and then *clears* the crash state, which
     * would put a plugin we are about to disable back on screen for a frame.
     * `notify = false` because the message below names the plugin and says how to
     * get it back, and the registry's generic one goes through `invokeLater` -
     * it would land second and overwrite the useful wording in a single-slot
     * status bar (the same collision `PluginRenderRecovery` documents).
     */
    private fun createCrashRecovery(delegate: PluginLoaderDelegateImpl) =
        PluginCrashRecoveryCoordinator(
            scope = recoveryScope,
            steps =
                object : PluginRecoverySteps {
                    override fun isKnown(pluginId: String) = DynamicPluginManager.isPluginKnown(pluginId)

                    override fun quarantine(
                        pluginId: String,
                        error: Throwable,
                    ) = PluginCrashRegistry.recordRenderFault(pluginId, error, notify = false)

                    override suspend fun closeTabs(pluginId: String) {
                        // detachPanels = false: this is quarantine, not an unload. `disable`
                        // below leaves the classloader open, so there is nothing to race - and
                        // the plugin's panel is on screen showing the crash fallback, which is
                        // where the user's Restart button lives. Detaching would blank it.
                        delegate.teardownPluginTabs(pluginId, detachPanels = false)
                    }

                    override suspend fun disable(pluginId: String) = DynamicPluginManager.disableEverywhere(pluginId)

                    override fun persistDisabled(pluginId: String) = persistCrashDisable(pluginId)

                    // Present tense: nothing has been disabled yet when this fires.
                    // The unload runs in the background, and the past-tense wording
                    // this replaced made a claim about work that had not started -
                    // which was wrong precisely when it mattered, since a disable
                    // that finds no live manager leaves the plugin back and enabled
                    // at the next launch.
                    override fun notifyDisabling(pluginId: String) =
                        StatusMessageManager.showMessage(
                            "Plugin '${displayPluginId(pluginId)}' crashed and is being disabled. " +
                                "Re-enable it from Toolbox.",
                            durationMs = CRASH_NOTICE_MILLIS,
                        )

                    override fun notifyDisableIncomplete(pluginId: String) =
                        StatusMessageManager.showMessage(
                            "Plugin '${displayPluginId(pluginId)}' could not be fully disabled and may return " +
                                "on restart. Disable it from Toolbox.",
                            durationMs = CRASH_NOTICE_MILLIS,
                        )
                },
        )

    /**
     * Record the crash-disable so the plugin does not come back and crash again on
     * the next launch.
     *
     * `setPluginEnabled` alone is not enough, and fails silently where it matters:
     * it updates an existing `installed.json` entry and does nothing at all when
     * there is none. A jar dropped into the plugins directory by hand has no entry
     * (the directory scan installs it without writing one), so a plugin that
     * crashes on load would be disabled, produce a crash dialog, and be back at the
     * next launch - the exact loop persisting is meant to break. Verified on a
     * real crash: the call ran and `installed.json` was unchanged.
     *
     * Adding the entry also stops the directory scan re-installing it, since the
     * persisted pass registers a disabled plugin's jar as tracked.
     */
    internal fun persistCrashDisable(
        pluginId: String,
        isInstalled: (String) -> Boolean = PluginPersistence::isInstalled,
        jarPathOf: (String) -> String? = DynamicPluginManager::jarPathOf,
        setEnabled: (String, Boolean) -> Unit = PluginPersistence::setPluginEnabled,
        addInstalled: (String, String, Boolean) -> Unit = { id, jar, enabled ->
            PluginPersistence.addInstalledPlugin(pluginId = id, jarPath = jar, enabled = enabled)
        },
    ): Boolean {
        if (isInstalled(pluginId)) {
            setEnabled(pluginId, false)
            // True because an entry existed to update. setPluginEnabled returns Unit,
            // so this cannot observe the write itself - the branch below is the one
            // that can fail, and does.
            return true
        }
        // No entry to update. A jar dropped into the plugins directory by hand has
        // none, and that is the case this branch exists for.
        val jarPath = jarPathOf(pluginId)
        jarPath?.let { addInstalled(pluginId, it, false) }
            ?: logger.warn(
                LogCategory.SYSTEM,
                "Cannot persist the crash-disable - no installed entry and no known jar",
                mapOf("pluginId" to pluginId),
            )
        return jarPath != null
    }

    /**
     * Owner of the background half of crash recovery (tab teardown, unload,
     * persistence). Process-lifetime and deliberately never cancelled: it is
     * started from a crash dialog whose window is already gone, so there is no
     * caller left whose cancellation should abort the unload. SupervisorJob so
     * one failed recovery does not poison the next.
     */
    private val recoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Long enough to read a sentence naming a plugin and where to re-enable it. */
    private const val CRASH_NOTICE_MILLIS = 12_000L
}
