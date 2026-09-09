package ai.rever.boss.search

import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.mcp.McpToolRegistryImpl

/**
 * A tool the global search can offer, flattened out of a window's sidebar.
 *
 * @property panelId What activates it. NOT the plugin id - `activatePlugin` matches on this.
 * @property label The tool's own name.
 */
internal data class ToolSearchRecord(
    val panelId: String,
    val label: String,
)

/**
 * A settings row the global search can offer, **already ranked** against the query.
 *
 * A plain record rather than the index's own entry type, because that type lives in desktopMain
 * and this service does not.
 *
 * [score] comes from `SettingsSearchMatcher`, the same ranker the Settings window's own search box
 * uses, rather than from a second scoring pass here - see [SearchSources.settingsSearch]. Scores
 * are therefore on the matcher's scale and are NOT comparable with the [FuzzyMatcher] scores the
 * other sources produce. That costs nothing: `getFilteredResults` orders by category first, so
 * scores are only ever compared within one source.
 *
 * Exactly one of [section], [pluginPageId] and [panelId] is set, and `SettingsSearchEntry`'s own
 * `init` requires exactly that - it used to require only "at least one", which left every
 * consumer's three-way routing resting on an invariant nothing enforced. [panelId] is the entry
 * that navigates *out* of the Settings window; see `panelSignpost`.
 */
internal data class SettingSearchRecord(
    val label: String,
    val breadcrumb: String,
    val section: String?,
    val pluginPageId: String?,
    val panelId: String?,
    val group: String?,
    val highlightable: Boolean,
    val score: Int,
)

/**
 * An MCP tool the global search can offer.
 *
 * @property enabled Whether it is exposed to clients: permitted AND not switched off. Computed
 *   where the registry is read rather than here, so the row cannot claim a tool is live when no
 *   client can see it.
 */
internal data class McpToolSearchRecord(
    val name: String,
    val providerId: String,
    val description: String,
    val enabled: Boolean,
)

/** A recently visited page the global search can offer. */
internal data class PageSearchRecord(
    val url: String,
    val title: String,
)

/**
 * The host state [GlobalSearchService] searches, handed in rather than read out of singletons.
 *
 * Four sources arrive here, for two different kinds of reason.
 *
 * **Reachability.** Settings are indexed by `SettingsSearchIndex`, which is desktopMain, and tools
 * live on `BossDraggableComponent`, which is per WINDOW while this service is one object shared by
 * all of them. Neither is reachable from the service at all.
 *
 * **Testability.** MCP tools and recent pages *are* reachable - `McpToolRegistryImpl` and
 * `RecentBrowserPagesManager` are both commonMain - and were read directly at first. They come
 * through here because reading a singleton made two things impossible: injecting a fake, so the
 * RBAC filter on MCP tools (the one path where a regression would leak admin-only tool names and
 * descriptions to a signed-out user) had no test at all; and running a unit test without touching
 * `~/.boss`, because reaching the registry forces it to load its disabled-tools file.
 *
 * Those two therefore **default** to reading their singleton - see [defaultMcpTools] and
 * [defaultRecentPages] - with [registerMcpTools] and [registerRecentPages] as test overrides,
 * rather than requiring registration. An earlier version required it, which put the failure this
 * object exists to prevent - a source that contributes nothing and says nothing - behind two lines
 * in `main()` and a comment asking not to separate them. A default cannot be forgotten, and a fake
 * still displaces it.
 *
 * That is not a hypothetical: the fall-back was written, lost in a bad edit while the KDoc
 * describing it survived, and shipped. Both sources returned nothing in a real build while every
 * test stayed green, because each installed its own fake. `SearchSourceRegistrarTest` drives the
 * unregistered path for exactly that reason.
 *
 * Suppliers rather than snapshots: every one of these sets changes while the app runs - a plugin
 * loads, a window takes focus, a page is visited - and a list captured at registration would go
 * stale silently, which for a search index means a thing that exists and cannot be found.
 *
 * Absent means "contributes nothing", which is the correct answer during startup and in tests. A
 * missing source returns no results rather than failing the whole search.
 *
 * **`internal`, and registered rather than assigned**, so the rules the KDoc below argues for are
 * structural instead of advisory - see [clearForTests] and [registerMcpTools] for the two that
 * would otherwise be one careless call away.
 *
 * **This solves the supplier half of the two-window problem, not the results half.**
 * [GlobalSearchService] keeps one global `_searchResults`, so with two dialogs open the second
 * renders whatever the first last searched; picking a `ToolResult` there activates it against the
 * second window's component, which logs "No sidebar panel to activate" and does nothing. That is
 * pre-existing, and newly reachable now that a result carries a window-scoped panel id. Fixing it
 * means scoping the result list per window, which is a bigger change than this seam.
 */
internal object SearchSources {
    private val lock = Any()

    /**
     * Tools per window, keyed by window id.
     *
     * A map and not one slot, because a single slot could not survive two dialogs. The first
     * version cleared unconditionally on dispose, so closing one window's dialog left every other
     * window searching no tools for the rest of the session. Guarding the clear by identity fixed
     * only half of that: the *registration* still overwrote whoever was there, so while two
     * dialogs were open one window searched the other's tools, and closing the newer one still
     * emptied the older one's slot.
     *
     * Keyed, both go away. A window's dialog registers under its own id, searches under its own
     * id, and removes only its own entry - which is also the only arrangement where the tools
     * offered belong to the window whose `activatePlugin` will be asked to open them.
     */
    @Volatile
    private var toolsByWindow: Map<String, () -> List<ToolSearchRecord>> = emptyMap()

    @Volatile
    private var settingsSearch: ((String) -> List<SettingSearchRecord>)? = null

    @Volatile
    private var mcpToolsSupplier: (() -> List<McpToolSearchRecord>)? = null

    @Volatile
    private var recentPagesSupplier: (() -> List<PageSearchRecord>)? = null

    /**
     * Register the ranker for the Settings index: rank against a query, best first.
     *
     * A search function and not a list of rows, so that "what a settings match is worth" has ONE
     * definition. Registering rows meant ranking them in [GlobalSearchService], against a second
     * scorer with its own keyword penalty - and the two disagreed in a way that lost results rather
     * than merely reordering them: `FuzzyMatcher` is a strict subsequence matcher over a single
     * target, so the global search could not match "user agent" to "Browser Identity" while the
     * Settings window, which tokenises the query, could. `SettingsSearchMatcher` exists for exactly
     * that, and this is how the global search gets to use it.
     */
    fun registerSettingsSearch(search: (String) -> List<SettingSearchRecord>) {
        settingsSearch = search
    }

    /**
     * Register the MCP tools on offer: every tool this user may see, disabled ones included.
     *
     * A function rather than an assignable property, because of what this particular supplier
     * holds. It carries the `permittedTools()` filter - the one path where a regression leaks the
     * names and full descriptions of admin-only tools to a signed-out user - and as a public `var`
     * anything in the app could have swapped it for one over `allTools` and nothing would have
     * looked wrong. See [McpToolSearchRecord.enabled].
     */
    fun registerMcpTools(supplier: () -> List<McpToolSearchRecord>) {
        mcpToolsSupplier = supplier
    }

    /** Register the browser's recent pages. */
    fun registerRecentPages(supplier: () -> List<PageSearchRecord>) {
        recentPagesSupplier = supplier
    }

    /** Register [windowId]'s sidebar tools. Paired with [unregisterTools] on the same id. */
    fun registerTools(
        windowId: String,
        supplier: () -> List<ToolSearchRecord>,
    ) {
        synchronized(lock) { toolsByWindow = toolsByWindow + (windowId to supplier) }
    }

    /** Forget [windowId]'s tools. Leaves every other window's registration alone. */
    fun unregisterTools(windowId: String) {
        synchronized(lock) { toolsByWindow = toolsByWindow - windowId }
    }

    /**
     * The tools [windowId] offers, or none if that window never registered any.
     *
     * Null [windowId] - a search with no window behind it, which is every test and the state
     * before any dialog has opened - contributes nothing rather than guessing at a window.
     */
    fun tools(windowId: String?): List<ToolSearchRecord> = windowId?.let { toolsByWindow[it] }?.invoke().orEmpty()

    /** The ranked settings rows, or none before the desktop side has registered its matcher. */
    fun settings(query: String): List<SettingSearchRecord> = settingsSearch?.invoke(query).orEmpty()

    /** The MCP tools on offer, or none if the host registered none. */
    fun mcpTools(): List<McpToolSearchRecord> = (mcpToolsSupplier ?: { defaultMcpTools() })()

    /** The recent pages on offer, or none if the host registered none. */
    fun recentPages(): List<PageSearchRecord> = (recentPagesSupplier ?: { defaultRecentPages() })()

    /**
     * Drop every registration.
     *
     * **Tests only**, which is why it says so in the name. An earlier version was documented as
     * being "for a window tearing down" as well, which was an invitation to break the app: the
     * host sources are registered exactly once at startup and never again, so calling this from a
     * closing window would drop settings, MCP tools and recent pages out of the search for the
     * rest of the session, with absence as the only symptom. A window tearing down wants
     * [unregisterTools] with its own id.
     */
    fun clearForTests(useProductionDefaults: Boolean = false) {
        synchronized(lock) { toolsByWindow = emptyMap() }
        settingsSearch = null
        // Empty by default, not null: null means "use the production default", which would send
        // most tests at the real registries and their disk reads.
        //
        // [useProductionDefaults] is for the few that exist to exercise the shipped path. It has
        // to be asked for, and it also has to be POSSIBLE to ask for - a suite where every MCP and
        // pages test installed a fake is how a lost fall-back shipped green once already.
        val empty = !useProductionDefaults
        mcpToolsSupplier = if (empty) ({ emptyList() }) else null
        recentPagesSupplier = if (empty) ({ emptyList() }) else null
    }
}

/**
 * The MCP tools the search offers by default: every tool THIS user may see, disabled ones included.
 *
 * `permittedTools()`, not `allTools`. `allTools` is deliberately unfiltered for the management UI,
 * which shows every tool with its state; this is the everyday launcher, open to every user and to
 * nobody signed in yet, where a name and a full description of an admin-only tool would be
 * enumerable by typing.
 *
 * The argument stands on its own and deliberately does not lean on the settings source: built-in
 * settings entries are not RBAC-filtered at all - there is no permission check on a
 * `SettingsSection` - only plugin pages are, through `visiblePages()`.
 */
internal fun defaultMcpTools(): List<McpToolSearchRecord> {
    val disabled = McpToolRegistryImpl.disabledToolNames.value
    return McpToolRegistryImpl.permittedTools().map { registered ->
        McpToolSearchRecord(
            name = registered.definition.name,
            providerId = registered.providerId,
            description = registered.definition.description,
            // Exposure is "permitted AND not switched off", and the list is already permitted, so
            // what is left to ask is whether it was switched off. Getting this wrong showed a
            // permission-denied tool as live when no client could see it - and answering "is this
            // switched off" is the row's entire job.
            enabled = registered.definition.name !in disabled,
        )
    }
}

/**
 * The recent pages the search offers by default.
 *
 * **No permission gate, where MCP tools have one.** Not an oversight: an MCP tool's name and
 * description describe a capability of the *install*, so on a shared or signed-out machine they
 * enumerate what the operator can be made to run. Recent pages are the browsing history of whoever
 * is sitting at the machine, shown to that same person on the surface they just browsed with - the
 * dashboard already lists them unfiltered, and gating them here but not there would read as a bug
 * rather than a boundary. Worth revisiting together if BOSS ever gets real multi-user profiles on
 * one install.
 */
internal fun defaultRecentPages(): List<PageSearchRecord> =
    RecentBrowserPagesManager.recentPages.value.map { PageSearchRecord(url = it.url, title = it.title) }
