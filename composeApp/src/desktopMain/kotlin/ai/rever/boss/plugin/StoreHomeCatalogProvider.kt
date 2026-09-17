package ai.rever.boss.plugin

import ai.rever.boss.components.home.HomeCatalogProvider
import ai.rever.boss.components.home.HomeStorePluginInput
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.updater.satisfiesVersionFloor
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException

/**
 * Answers the home screen's two store questions: what could be installed, and install it.
 *
 * **The install leg is delegated, not reimplemented.** [installer] is the same
 * [StoreMissingDependencyInstaller] the missing-dependency prompt uses, and its `install` is
 * already generic in the plugin id. That matters more than it looks: AGENTS.md records five
 * separate defects found in review of that one method - downloading onto the final filename
 * truncates an existing jar the moment the connection opens, a surviving `.sig` sidecar
 * hard-fails the next load, a missing `installed.json` row makes the plugin impossible to
 * disable persistently, a store row can point at a jar declaring some other plugin, and a
 * promotion that half-succeeds leaves an unvetted jar at a scannable name. A second download
 * path here would have to get all five right again.
 *
 * @param repository the remote store, null when it never initialised (offline first run, or
 *   absent store credentials) - discovery is then simply empty
 * @param installer resolves the shared store installer **per call**, not once at construction.
 *   Late-bound because the installer loads a jar into a `DynamicPluginManager`, there is one per
 *   window, and this provider is registered once for the process: capturing the first window's
 *   meant that closing that window left every Install tile targeting a disposed manager, where the
 *   install reports success having loaded into something nothing renders. Null when no manager is
 *   live, which is the honest answer in a torn-down process.
 */
class StoreHomeCatalogProvider(
    private val repository: () -> PluginRepository?,
    private val installer: () -> MissingDependencyInstaller?,
    private val hostBossVersion: () -> String = { AppVersion.currentVersionString() },
    private val hostApiVersion: () -> String = { System.getProperty("boss.api.version") ?: "" },
    private val isIpcInstallable: (String) -> Boolean = { IpcCompatibility.isInstallable(it) },
) : HomeCatalogProvider {
    private val logger = BossLogger.forComponent("StoreHomeCatalogProvider")

    /**
     * The store listing, held for the session after one successful fetch.
     *
     * The home screen mounts in every empty split panel and remounts whenever a panel's last tab
     * closes, so a per-mount fetch turned ordinary tab closing into store traffic. The catalogue
     * changes far more slowly than that. Stale until relaunch is the trade, and it only affects
     * which not-yet-installed plugins are offered.
     *
     * `@Volatile` rather than a mutex: two concurrent first mounts may both fetch, which costs one
     * redundant request and cannot produce a wrong answer.
     */
    @Volatile
    private var cached: List<HomeStorePluginInput>? = null

    /**
     * The remote store's rows, reduced to what the grid asks.
     *
     * Deliberately the remote repository rather than `repositoryManager.listAllPlugins()`, which
     * the first-run wizard uses: this list exists to show plugins the user does **not** have, and
     * every row the local repository contributes is by definition a jar already in the plugins
     * directory.
     *
     * Degrades to empty on any failure. A home screen that cannot reach the network should show
     * the tools that are installed, not an error - the grid is still strictly more than the
     * hardcoded twelve cards it replaces.
     */
    // Two early returns for the two ways there is nothing to show (no store, store failed) plus
    // the result. Collapsing them would hide which happened, and they log differently.
    @Suppress("ReturnCount")
    override suspend fun discoverable(): List<HomeStorePluginInput> {
        cached?.let { return it }

        val store = repository() ?: return emptyList()
        val listing = runCatching { store.listPlugins().getOrThrow() }
        // Cancellation is not a store failure. This runs in a LaunchedEffect that is cancelled
        // whenever the screen leaves composition, and `runCatching` around a suspending call also
        // catches CancellationException - so without this a routine cancellation is logged as the
        // store having failed, which is misleading in a support log, and swallowing it breaks
        // structured concurrency.
        listing.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        listing.exceptionOrNull()?.let { error ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not list the plugin store for the home screen; showing installed tools only",
                error = error,
            )
            // Deliberately not cached: a failure is usually transient (the store was not up yet,
            // the network was down), and the next time the screen mounts is a reasonable moment to
            // try again.
            return emptyList()
        }
        return listing
            .getOrNull()
            .orEmpty()
            .map(::toInput)
            .also { cached = it }
    }

    override suspend fun install(pluginId: String): Result<Unit> {
        val target =
            installer() ?: return Result.failure(
                IllegalStateException("No window is available to install into. Try again in a moment."),
            )
        return target.install(pluginId)
    }

    private fun toInput(row: PluginInfo): HomeStorePluginInput =
        HomeStorePluginInput(
            pluginId = row.pluginId,
            displayName = row.displayName,
            // Straight through from `plugins.icon_url`. The host holds no icon table for
            // not-yet-installed plugins: this column is the source, and a blank one renders as the
            // plugin's initials. Populating the column is all it takes for real icons to appear -
            // no client release.
            iconUrl = row.iconUrl,
            requiresAdmin = row.requiresAdmin,
            isCompatible = isCompatible(row),
            // Same reason the wizard drops these: a service plugin ships through the store but
            // is not user-installable, and loading one as a regular plugin fails
            // BinaryCompatibilityValidator with a cross-classloader IllegalAccessError.
            isService = row.type == PluginType.SERVICE,
            description = row.description,
        )

    /**
     * Whether this build could actually load [row], across all three floors the store records.
     *
     * Checked here rather than in the pure catalogue because the comparison needs the host's own
     * versions. All three fail open on a blank or unparseable value, matching the updater: the
     * loader's checks are the backstop, and gating a plugin because a store row omitted a field
     * would hide it for no reason.
     */
    private fun isCompatible(row: PluginInfo): Boolean =
        satisfiesVersionFloor(required = row.minBossVersion, installed = hostBossVersion()) &&
            satisfiesVersionFloor(required = row.minApiVersion, installed = hostApiVersion()) &&
            isIpcInstallable(row.minIpcVersion)
}
