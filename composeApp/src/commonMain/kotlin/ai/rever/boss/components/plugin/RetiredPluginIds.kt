package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.dependency.SemanticVersion
import ai.rever.boss.plugin.updater.satisfiesVersionFloor

/**
 * Plugins whose job another plugin has taken over, and which must therefore never be *offered*
 * for install again.
 *
 * Separate from `RetiredPlugins` (desktopMain), which owns the uninstall of one already on disk.
 * The ids live here because the surfaces that offer plugins are split across source sets: the
 * first-run wizard is desktopMain, the home screen's tool grid is commonMain, and both filter
 * `PluginDependencyResolution.NOT_USER_INSTALLABLE` in the same spirit.
 *
 * **Why the host filters at all, when the fix is a database row.** Unlisting the store row is a
 * manual action outside this repo and outside CI. Until it happens - and it may never happen on a
 * self-hosted store - the store still returns the plugin, so a user can install it, have it swept
 * away with a notice at the next launch, install it again, and so on. Filtering here makes the
 * retirement hold regardless of what the store says, which is what stops it being a one-way door
 * in the wrong direction.
 *
 * `RetiredPluginsTest` pins this against `RetiredPlugins.ALL` (ids, replacements AND floors),
 * so a retirement added there without a matching entry here fails rather than quietly staying
 * installable.
 *
 * **The floors are part of the contract, not decoration.** The sweep
 * (`RetiredPlugins.sweep`) only removes a retired plugin once its replacement is installed at
 * [Retired.minReplacementVersion] or newer. The OFFER filter below must use the same floor:
 * hiding a retired plugin unconditionally from a fresh install while the sweep is still floored
 * leaves that machine with no panel for what the retired one did - exactly the gap between this
 * host release and the replacement's absorbing release. Hiding only when the sweep would be
 * able to act closes it regardless of release choreography.
 */
object RetiredPluginIds {
    /**
     * One retired plugin, and the version of its replacement that actually absorbed the work.
     * Must stay equal to [ai.rever.boss.plugin.RetiredPlugins.Retirement] entry for entry -
     * `RetiredPluginsTest` checks that.
     */
    data class Retired(
        val pluginId: String,
        val replacementId: String,
        val minReplacementVersion: String,
    )

    val ALL: List<Retired> =
        listOf(
            Retired(
                pluginId = "ai.rever.boss.plugin.dynamic.usersecretlist",
                replacementId = "ai.rever.boss.plugin.dynamic.secretmanager",
                minReplacementVersion = "1.2.17",
            ),
            Retired(
                pluginId = "ai.rever.boss.plugin.dynamic.gitstatus",
                replacementId = "ai.rever.boss.plugin.dynamic.codebase",
                minReplacementVersion = "1.6.0",
            ),
            Retired(
                pluginId = "ai.rever.boss.plugin.dynamic.gitlog",
                replacementId = "ai.rever.boss.plugin.dynamic.codebase",
                minReplacementVersion = "1.6.0",
            ),
        )

    val ALL_IDS: Set<String> = ALL.map { it.pluginId }.toSet()

    /**
     * Whether [pluginId] should be hidden from what is offered for install.
     *
     * The answer is the sweep's own gate, evaluated against [installedVersionOf]: the retired
     * plugin disappears from the offer the moment the sweep would be able to remove it, and
     * stays offered until then - so a fresh install never loses the panel before the
     * replacement can take over.
     *
     * Fails closed like [ai.rever.boss.plugin.RetiredPlugins.replacementIsReady]: the version is
     * parsed explicitly (not through `satisfiesVersionFloor` alone, which answers true for
     * anything unparseable), and "replacement not installed or unreadable" keeps the retired
     * plugin OFFERED - the conservative side, since offering an installable plugin is
     * recoverable and hiding the only panel for a feature is not.
     */
    fun hiddenFromOffers(
        pluginId: String,
        installedVersionOf: (String) -> String?,
    ): Boolean {
        val retirement = ALL.firstOrNull { it.pluginId == pluginId } ?: return false
        val version = installedVersionOf(retirement.replacementId)
        return version != null &&
            version.isNotBlank() &&
            SemanticVersion.parse(version) != null &&
            satisfiesVersionFloor(required = retirement.minReplacementVersion, installed = version)
    }
}
