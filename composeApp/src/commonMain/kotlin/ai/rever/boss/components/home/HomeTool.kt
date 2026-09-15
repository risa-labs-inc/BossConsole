package ai.rever.boss.components.home

import ai.rever.boss.plugin.api.PanelId

/**
 * One tile in the home screen's tool grid.
 *
 * The grid deliberately mixes two populations - tools that are installed and ready, and plugins
 * from the store that are not installed yet - so the same screen answers "what can I do" and
 * "what else is there". [launch] is what distinguishes them, not a separate list.
 *
 * There is no category or tag field. The store has no `tags` column at all (the client models one
 * the database does not have, so it is always empty) and nothing else in the data supports a
 * Developer / Automation / Admin taxonomy. Grouping is therefore by the one distinction the data
 * does support and the user acts on: ready versus installable. See [HomeToolFilter].
 */
data class HomeTool(
    /**
     * Stable identity for keying the grid: a tab type id, a panel id, or a plugin id.
     *
     * Not necessarily the plugin id - one plugin can register several tab types - so [pluginId]
     * is carried separately for the dedupe and RBAC decisions.
     */
    val id: String,
    val label: String,
    val icon: HomeToolIcon,
    val launch: HomeToolLaunch,
    /** The plugin that owns this tool, or null for a host action that needs no plugin. */
    val pluginId: String? = null,
) {
    /** Whether this tile does something now, as opposed to offering an install. */
    val isReady: Boolean get() = launch !is HomeToolLaunch.Install && launch !is HomeToolLaunch.Recover

    val isInstalled: Boolean get() = launch !is HomeToolLaunch.Install
}

/**
 * The grid's filter, over the only grouping the data supports.
 *
 * Replaces a chip row built from a hardcoded plugin-id-to-category table. That table was stale by
 * construction - every new plugin needed an edit, and anything unlisted fell into "Other".
 */
enum class HomeToolFilter(
    val label: String,
) {
    ALL("All"),
    READY("Installed"),
    AVAILABLE("Available"),
    ;

    fun accepts(tool: HomeTool): Boolean =
        when (this) {
            ALL -> true
            READY -> tool.isInstalled
            AVAILABLE -> !tool.isInstalled
        }
}

/** What clicking a tile does. */
sealed interface HomeToolLaunch {
    /** Installed but off: opens the host's existing, revalidated recovery controls. */
    data class Recover(
        val pluginId: String,
    ) : HomeToolLaunch

    /**
     * Open a plugin-registered tab type.
     *
     * [needsInput] mirrors the New Tab dialog's own decision: a type whose `NewTabSpec` declares
     * no input opens instantly, anything else has to collect the input first, so the tile hands
     * off to the dialog rather than guessing an empty string the plugin will reject.
     */
    data class OpenTab(
        val typeId: String,
        /**
         * The `pluginId` half of the registry's `TabTypeId`.
         *
         * Both halves are carried because `TabTypeId` is a data class over the pair, so a handler
         * that rebuilt the key from `typeId` alone would not match what the plugin registered.
         */
        val typePluginId: String,
        val needsInput: Boolean,
    ) : HomeToolLaunch

    /** Toggle a sidebar panel. */
    data class OpenPanel(
        val panelId: PanelId,
    ) : HomeToolLaunch

    /**
     * Install this plugin from the store, after which it becomes one of the above.
     *
     * Carries no version: `HomeCatalogProvider.install` takes only an id and resolves the latest
     * itself, and a version nothing reads is the dead-field pattern the rest of this screen
     * removes. Same reason there is no `description` on [HomeTool] - if either is ever shown, a
     * tooltip is where they belong.
     */
    data class Install(
        val pluginId: String,
    ) : HomeToolLaunch

    /** A host action that owns no plugin: new terminal, open project, settings. */
    data class HostAction(
        val action: HomeHostAction,
    ) : HomeToolLaunch
}

/**
 * The host's own actions, as an enum rather than lambdas.
 *
 * Lambdas are what the old screen used and what made it possible to mount it with eleven empty
 * ones. A closed enum cannot be passed inertly: `HomeActions` maps every member onto
 * `DashboardEventBus`, and `HomeActionRoutingTest` fails if a member is added without one.
 */
enum class HomeHostAction {
    NEW_TAB,
    NEW_TERMINAL,
    NEW_WINDOW,
    OPEN_FILE,
    OPEN_PROJECT,
    NEW_PROJECT,
    SETTINGS,
    SEARCH,
}
