package ai.rever.boss.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.updater.satisfiesVersionFloor
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Uninstalls a plugin whose job another plugin has taken over.
 *
 * A retired plugin cannot uninstall itself, and leaving it installed means the user keeps a
 * panel that no longer does anything. The store listing can be unlisted so nobody *new*
 * installs it, but that does nothing for the machines that already have it - which is every
 * machine, when the retired plugin was a first-run default.
 *
 * The sweep runs once per launch, before any plugin is loaded, so a retired plugin never
 * registers a panel or an MCP tool on a machine where its replacement is present. No
 * "already done" flag is needed: the sweep keys on the plugin being installed, and removing
 * it is what makes the next launch a no-op.
 */
object RetiredPlugins {
    private val logger = BossLogger.forComponent("RetiredPlugins")

    /** How long the "X is now part of Y" line sits in the status bar. */
    private const val NOTICE_MS = 8_000L

    /**
     * A plugin that has been folded into another one.
     *
     * [minReplacementVersion] is the release of the replacement that actually absorbed the
     * work. Without it this would uninstall the old plugin as soon as the *name* of the new one
     * appeared in `installed.json`, including a version predating the absorption - so the user
     * would lose both halves at once.
     */
    data class Retirement(
        val pluginId: String,
        val displayName: String,
        val replacementId: String,
        val replacementDisplayName: String,
        val minReplacementVersion: String,
    )

    val ALL =
        listOf(
            // "My Secrets" and Secret Manager sat next to each other in the right sidebar
            // reading the same vault, and both listed the caller's own secrets. Secret Manager
            // now has two sections that partition it - what you can manage, and what other
            // people shared with you - so this plugin ships a notice panel and nothing else.
            //
            // The version is the secret-manager release that has those sections. It has to
            // name a real published release: too low and this deletes the user's only secrets
            // panel on a machine whose Secret Manager predates the merge.
            Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.usersecretlist",
                displayName = "My Secrets",
                replacementId = "ai.rever.boss.plugin.dynamic.secretmanager",
                replacementDisplayName = "Secret Manager",
                minReplacementVersion = "1.2.17",
            ),
            // The codebase panel grew a FILES/SEARCH/GIT tab row (IDE batch P7):
            // global search/replace, the changes accordion, the commit graph and
            // the git_* / project_* MCP tools all live there now. 1.6.0 is the
            // release that absorbed both - lower would sweep the old plugins
            // while their tools and UI are still only in them.
            //
            // Search & Replace is deliberately NOT here. It was written during this
            // batch and superseded by the SEARCH tab before it was ever published,
            // so it is installed on no machine: a retirement entry would be a sweep
            // that can never match, carrying a version floor nobody has to maintain.
            Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.gitstatus",
                displayName = "Git Status (Dynamic)",
                replacementId = "ai.rever.boss.plugin.dynamic.codebase",
                replacementDisplayName = "Codebase (Dynamic)",
                minReplacementVersion = "1.6.0",
            ),
            Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.gitlog",
                displayName = "Git Log (Dynamic)",
                replacementId = "ai.rever.boss.plugin.dynamic.codebase",
                replacementDisplayName = "Codebase (Dynamic)",
                minReplacementVersion = "1.6.0",
            ),
        )

    /**
     * Seams with production defaults, so the decision is testable without deleting a
     * developer's own plugins or rewriting their `installed.json`. Mirrors
     * [PluginArtifactCleanup.Hooks], which this delegates the actual removal to.
     */
    class Hooks(
        val installed: (String) -> PluginPersistence.InstalledPluginEntry? = { id ->
            PluginPersistence.getInstalledPlugin(id)
        },
        val jarExists: (String) -> Boolean = { path -> path.isNotBlank() && File(path).isFile },
        /**
         * Drops the `installed.json` row (and the sidecar for the recorded path). Called only
         * after `purgeArtifacts` confirmed the jars are gone - see [retire].
         */
        val remove: (String, String) -> Unit = { id, jarPath -> PluginArtifactCleanup.remove(id, jarPath) },
        /** Called once per sweep, with every removal in one message. See [noticeFor]. */
        val announce: (String) -> Unit = { message -> StatusMessageManager.showMessage(message, NOTICE_MS) },
    )

    /**
     * Uninstalls every retired plugin whose replacement is installed and new enough.
     *
     * **Startup only, before any plugin is loaded.** [PluginArtifactCleanup.remove] deletes the
     * jar without unloading anything, which is safe at step 3c of
     * `PluginStoreSetup.loadPersistedPlugins` and nowhere else: pulling a jar out from under a
     * live classloader is how you get `NoClassDefFoundError` from code that is still running.
     * `internal` so the only caller stays inside this module.
     *
     * @param restoredAtNextLaunch why [pluginId] would come back on its own, or null if it
     *   would not. A bundled or system plugin is re-copied or re-downloaded before this sweep
     *   runs (steps 1 and 2), so uninstalling one turns into a copy-then-delete loop on every
     *   launch, with the status-bar notice firing each time. See `PluginRemoval.removalVeto`,
     *   which exists for the same hazard on the interactive path.
     * @return the plugin ids actually removed, for the caller to log.
     */
    internal fun sweep(
        restoredAtNextLaunch: (String) -> String?,
        purgeArtifacts: (String) -> Boolean,
        retirements: List<Retirement> = ALL,
        hooks: Hooks = Hooks(),
    ): List<String> {
        // Per retirement, so one entry whose removal throws cannot drop the rest - or lose the
        // ids already removed, which the caller logs.
        val removed =
            retirements.filter { retirement ->
                runCatching { retire(retirement, restoredAtNextLaunch, purgeArtifacts, hooks) }
                    .onFailure { error ->
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Could not uninstall a retired plugin",
                            mapOf(
                                "pluginId" to retirement.pluginId,
                                "error" to (error.message ?: "unknown"),
                            ),
                        )
                    }.getOrDefault(false)
            }

        // One message for the whole sweep, not one per retirement: showMessage cancels the
        // previous one, so announcing in the loop would show only the last.
        if (removed.isNotEmpty()) {
            hooks.announce(noticeFor(removed))
        }
        return removed.map { it.pluginId }
    }

    /**
     * One line for the whole sweep: "X is now part of Y", or "X and Y are now part of Z".
     *
     * **Grouped by replacement.** The function only exists for the multi-removal case, and that
     * is exactly the case where two retirements can point at *different* replacements - joining
     * the names and taking `first().replacementDisplayName` told the user their panel moved
     * somewhere it did not. Latent while `ALL` has one entry, which is why it needs a test
     * rather than a reading.
     */
    private fun noticeFor(removed: List<Retirement>): String =
        removed
            .groupBy { it.replacementDisplayName }
            .entries
            .joinToString("; ") { (replacement, group) ->
                val names = group.map { it.displayName }
                val joined =
                    if (names.size == 1) {
                        names.first()
                    } else {
                        // "A, B and C" rather than "A and B and C".
                        names.dropLast(1).joinToString(", ") + " and " + names.last()
                    }
                "$joined ${if (group.size == 1) "is" else "are"} now part of $replacement"
            }

    // Guard clauses, each logging a different reason. Same call as replacementIsReady below and
    // as VersionFloor.kt itself: a reader asking "why is the old panel still there" needs to know
    // which check fired, and folding them into one condition collapses three answers into one.
    @Suppress("ReturnCount")
    private fun retire(
        retirement: Retirement,
        restoredAtNextLaunch: (String) -> String?,
        purgeArtifacts: (String) -> Boolean,
        hooks: Hooks,
    ): Boolean {
        val entry = hooks.installed(retirement.pluginId) ?: return false

        val wouldComeBack = restoredAtNextLaunch(retirement.pluginId)
        if (wouldComeBack != null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: it would be restored anyway",
                mapOf("pluginId" to retirement.pluginId, "reason" to wouldComeBack),
            )
            return false
        }
        if (!replacementIsReady(retirement, hooks)) return false

        // The jars go first, and the row is only dropped once they are actually gone. Dropping
        // the row while a jar survives is worse than doing nothing: DefaultPlugin's directory
        // scan runs later in the *same* launch and installs any jar the manager does not know
        // about, so the panel comes back in the session the user was just told it left, a fresh
        // row is written on load, and every launch after that sweeps and announces again. See
        // purgeJarsFor.
        if (!purgeArtifacts(retirement.pluginId)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: its jar could not be removed, so its row is left alone",
                mapOf("pluginId" to retirement.pluginId, "jarPath" to entry.jarPath),
            )
            return false
        }

        hooks.remove(retirement.pluginId, entry.jarPath)
        logger.info(
            LogCategory.SYSTEM,
            "Uninstalled a retired plugin",
            mapOf(
                "pluginId" to retirement.pluginId,
                "replacedBy" to retirement.replacementId,
            ),
        )
        return true
    }

    /**
     * Whether the replacement can actually stand in for the retired plugin.
     *
     * **Fails closed at every step**, which is the opposite of [satisfiesVersionFloor]'s own
     * default: that helper answers "true" for a blank or unparseable version because a *gated
     * update* is worse than an ungated one. Here the consequence runs the other way - a wrong
     * "yes" deletes the panel the user reads their shared credentials in - so an unknown
     * version means "not ready" and the retirement waits for a launch that can prove it.
     *
     * The jar check is not paranoia: `installPlugin` records a DISABLED entry for a plugin it
     * then rejected and deleted, so an entry alone does not mean the replacement can run. Same
     * definition of "installed" as `PluginDependencyResolution.installedAndOnDisk`.
     */
    // Guard clauses: each refusal logs a different reason, and a reader debugging "why is the
    // old panel still there" needs to know which one fired. Folding them into one condition
    // would collapse three distinct answers into one. Same call as VersionFloor.kt's.
    @Suppress("ReturnCount")
    private fun replacementIsReady(
        retirement: Retirement,
        hooks: Hooks,
    ): Boolean {
        val replacement = hooks.installed(retirement.replacementId)
        if (replacement == null) {
            logger.info(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: its replacement is not installed",
                mapOf("pluginId" to retirement.pluginId, "replacementId" to retirement.replacementId),
            )
            return false
        }
        if (!replacement.enabled) {
            // The user can disable Secret Manager from the Toolbox, and installPlugin also
            // records a DISABLED entry for a plugin hidden for lack of access - both leave the
            // row and the jar in place. `enabled` is the only signal available here, since
            // nothing has loaded yet.
            logger.info(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: its replacement is installed but disabled",
                mapOf("pluginId" to retirement.pluginId, "replacementId" to retirement.replacementId),
            )
            return false
        }
        if (!hooks.jarExists(replacement.jarPath)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: its replacement has an installed.json entry but no jar",
                mapOf("pluginId" to retirement.pluginId, "jarPath" to replacement.jarPath),
            )
            return false
        }
        val version = replacement.installedVersion
        // Parsed explicitly, because satisfiesVersionFloor answers TRUE for anything
        // SemanticVersion cannot read - "dev", "v1.2.17", "1.2.x", a trailing "-" or "+" - by
        // design: for update gating an ungated update beats a wrongly gated one. Here the
        // consequence runs the other way, and a locally built or side-loaded jar whose manifest
        // version is not strict semver is exactly the case that would lose both panels.
        val newEnough =
            version != null &&
                SemanticVersion.parse(version) != null &&
                satisfiesVersionFloor(required = retirement.minReplacementVersion, installed = version)
        if (!newEnough) {
            logger.info(
                LogCategory.SYSTEM,
                "Keeping a retired plugin: its replacement predates the version that absorbed it",
                mapOf(
                    "pluginId" to retirement.pluginId,
                    "replacementVersion" to (version ?: "unknown"),
                    "required" to retirement.minReplacementVersion,
                ),
            )
            return false
        }
        return true
    }
}
