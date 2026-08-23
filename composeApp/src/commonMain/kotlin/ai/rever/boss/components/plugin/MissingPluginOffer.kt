package ai.rever.boss.components.plugin

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val logger = BossLogger.forComponent("MissingPluginOffer")

/**
 * Offer to install a plugin that a host control needs but the user does not have.
 *
 * The machinery this leans on is split across source sets: `DynamicPluginManager` and
 * `PluginDependencyEventBus` are commonMain, but `MissingDependencyReporter.installerFor` - the
 * one definition of "installed" that the Install button also uses - is desktopMain, because
 * resolving and downloading a plugin is a desktop concern. So the builder is injected at startup
 * rather than called directly, the same shape `BrokeredCredentialAccess` uses and for the same
 * reason.
 *
 * Callers are host UI that draws a control for something a plugin provides. `PerformanceState`
 * does this for the performance panel; the tab bar's Favorites section does it for bookmarks.
 * The failure it exists to prevent is the one recorded there: a control that emits an event
 * nothing is listening for, so pressing it produces no panel, no dialog and no log line, and is
 * indistinguishable from a broken button.
 */
object MissingPluginOffer {
    /**
     * Builds the installer for a manager. Injected by `main.kt`; null before startup finishes,
     * and in tests that never wire it.
     */
    var installerFactory: ((DynamicPluginManager) -> MissingDependencyInstaller)? = null

    /**
     * Offer [pluginId] if it is absent, returning whether an offer was raised.
     *
     * **Fails open**: with no manager or no injected factory there is nothing to ask and nothing
     * to install, so this returns false and the caller proceeds as if the plugin were present.
     * A wiring gap must not be able to make a control unusable on an install where the plugin IS
     * there - the same rule `PerformanceState.offerToInstallPanel` states.
     *
     * @param dependentDisplayName what to name as needing it, in the dialog's copy.
     */
    fun offerIfMissing(
        pluginId: String,
        dependentDisplayName: String = "BOSS",
    ): Boolean {
        // One exit for "nothing to offer", covering all three reasons: no injected factory, no
        // active manager, or the plugin is already here.
        val installer =
            installerFactory
                ?.let { factory -> DynamicPluginManager.anyActiveManager()?.let(factory) }
                ?.takeIf { !it.isInstalled(pluginId) }
                ?: return false

        logger.info(
            LogCategory.UI,
            "Control needs a plugin that is not installed",
            mapOf("pluginId" to pluginId),
        )
        PluginDependencyEventBus.report(
            MissingDependencyPrompt(
                missing =
                    MissingPluginDependency(
                        dependentPluginId = HOST_DEPENDENT_ID,
                        dependentDisplayName = dependentDisplayName,
                        missingPluginId = pluginId,
                        // True, and it is what makes the copy honest: BOSS works without this,
                        // one section of one bar does not. The alternative phrasing claims BOSS
                        // needs it, which would be a lie told in a consent dialog for
                        // downloading code.
                        optional = true,
                    ),
                installer = installer,
                // A click, so it is asked again even after the offer was dismissed once.
                userInitiated = true,
            ),
        )
        return true
    }

    /** What the host calls itself when it is the thing missing a dependency. */
    private const val HOST_DEPENDENT_ID = "ai.rever.boss"
}
