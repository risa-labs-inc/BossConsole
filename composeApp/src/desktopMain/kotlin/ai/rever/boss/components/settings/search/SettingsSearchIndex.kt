package ai.rever.boss.components.settings.search

import ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.search.SearchSources
import ai.rever.boss.search.SettingSearchRecord

/**
 * One searchable thing in the Settings window.
 *
 * Identity is ([section], [group], [label]) and **never [label] alone**. Performance carries
 * "Warning Threshold" and "Critical Threshold" twice each - once under "Memory Thresholds" and once
 * under "CPU Thresholds" - so a label-only key would send the user to the wrong control half the
 * time and light up both when it got there.
 *
 * @param label the control's `label =`, or the group's `title =` for a group-header entry.
 * @param section the built-in section that owns it, or null when this is a plugin-contributed page.
 * @param group the enclosing `SettingsSection(title = ...)`, or null for a group header itself.
 * @param keywords words a user might search that the label does not contain ("passkey" for
 *   "Platform Authenticator"). Deliberately scored below a label hit - see [SettingsSearchMatcher].
 * @param pluginPageId set instead of [section] for a plugin page, which navigates by page id.
 * @param panel set instead of both for something that is not in this window at all: picking it
 *   opens that sidebar panel in the main window. The one target that navigates *out* - see
 *   `panelSignpost`, which is the only thing that builds one.
 * @param highlightable false when landing on the section is all this entry can do - either the page
 *   belongs to another module, or the control is built from a local composable that carries no
 *   search target (the Shortcuts tab-switching chips live in commonMain, which cannot reach the
 *   desktop-only highlight modifier at all).
 * @param curated true when this entry was written by hand rather than read off a `label =` literal,
 *   so the drift test's staleness check must skip it - there is no source line for it to find. Only
 *   for section-level catch-alls and for sections the scanner cannot see.
 * @param context a heading the control sits under on screen that is not a `SettingsSection`, used
 *   for the breadcrumb only. Shortcuts groups its tab-switching chips under a plain `Text`, so
 *   [group] must stay null to match what the scanner computes - without this, the result row reads
 *   "Positional / Shortcuts", which tells the reader nothing about what it does.
 */
data class SettingsSearchEntry(
    val label: String,
    val section: SettingsSection? = null,
    val group: String? = null,
    val keywords: List<String> = emptyList(),
    val pluginPageId: String? = null,
    val panel: PanelId? = null,
    val highlightable: Boolean = true,
    val curated: Boolean = false,
    val context: String? = null,
) {
    init {
        // EXACTLY one, not at least one. Every consumer routes on a three-way branch - the global
        // search's `onSettingSelect` checks panelId first, the window's `applyHit` checks panel
        // first - so an entry naming two targets would be silently routed by whichever branch came
        // first, and the two surfaces would only agree by coincidence. Requiring one makes the
        // record's documented invariant true rather than merely observed.
        require(listOfNotNull(section, pluginPageId, panel).size == 1) {
            "a search entry must name exactly one of a built-in section, a plugin page or a panel: $label"
        }
    }

    /**
     * "Browser > User Agent", or just "Browser" for a group header. Shown under the result.
     *
     * A [panel] entry has no section, so it falls to [PLUGIN_BREADCRUMB] and leans on [context] to
     * name the panel - "Plugins > Secret Manager panel". That second half is the whole point of the
     * row: the thing is not in this window, and the breadcrumb is what says so before the click.
     */
    val breadcrumb: String
        get() =
            listOfNotNull(section?.displayName ?: PLUGIN_BREADCRUMB, group ?: context)
                .joinToString(" > ")

    /**
     * Stable identity for a `LazyColumn` key.
     *
     * Spelled out rather than left to the data class's own `hashCode`, because a list key has to
     * stay stable across recompositions and a collision silently reuses the wrong row's state. It
     * is the same triple the highlight matches on, so if two entries ever collide here they would
     * also have highlighted each other.
     */
    val resultKey: String
        get() = "${section?.name ?: pluginPageId ?: panel?.panelId}|${group.orEmpty()}|$label"

    companion object {
        const val PLUGIN_BREADCRUMB = "Plugins"
    }
}

/**
 * Everything the Settings window can be searched for.
 *
 * Hand-declared rather than harvested, and held honest by `SettingsSearchIndexDriftTest`, which
 * scans `settings/sections/` in both directions: it fails when a section gains a label this file
 * does not list, and again when this file names a label the sources no longer contain. The second
 * catches the worse failure, which is what a rename produces - a result that still appears, still
 * navigates, and then highlights nothing.
 *
 * Plugin pages are deliberately absent. They are merged in at query time from
 * `SettingsPageRegistryImpl.visiblePages()`, which is the only way to respect RBAC and plugin
 * lifecycle - see [pluginPageEntry].
 */
object SettingsSearchIndex {
    /**
     * Every built-in entry, declared one section at a time in `SettingsSearchEntries.kt`.
     *
     * The declarations live in a sibling file so that this one stays the model and the contract,
     * and a diff that adds a setting touches only the data.
     */
    val builtIn: List<SettingsSearchEntry> get() = builtInEntries

    /**
     * Hand this index to the global (double-shift) search.
     *
     * Called once at desktop startup. The global search lives in commonMain and cannot see
     * [SettingsSearchEntry] at all, so it gets plain records through [SearchSources].
     *
     * **A ranking function, not a list of rows.** Handing over rows meant the global search had to
     * score them itself, with a second scorer that disagreed with this one: `FuzzyMatcher` matches
     * a subsequence of a single target, so "user agent" reached "Browser Identity" in the Settings
     * window and matched nothing in the global search. [SettingsSearchMatcher] is what fixes that,
     * by tokenising the query, and this is how both surfaces come to share it - one definition of
     * what a settings match is worth, and no second keyword penalty to keep in step.
     *
     * A function rather than a snapshot for the original reason too: plugin pages appear and
     * disappear with their plugins, and a list captured at startup would go stale into a search
     * index, which is the one place staleness is invisible.
     *
     * Built-in entries plus the plugin pages currently visible, which is the same union
     * `SettingsWindow` feeds its own search box - with one deliberate difference: signpost
     * reachability is applied by the caller, not here. `withReachableSignposts` needs the panel
     * registry and is `@Composable`; `GlobalSearchService` filters on the searching window's
     * registered tools instead, which is the same predicate `activatePlugin` uses. See
     * `GlobalSearchService.searchSettings`.
     */
    fun registerWithGlobalSearch() {
        // Force the lazy index HERE, at startup, rather than leaving it to the first query.
        //
        // `builtInEntries` is `by lazy` and `SettingsSearchEntry.init` now requires exactly one
        // target, so a malformed entry throws. Reached first from inside `isolated("settings")`,
        // that throw would be caught, logged at WARN, and cost the whole Settings category - and
        // `by lazy` does not cache a failed initialiser, so it would re-throw and re-warn on every
        // keystroke instead of failing once. Startup is where a bad constant should stop the app,
        // and silence per keystroke is exactly the failure mode `SearchSources` argues against.
        require(builtIn.isNotEmpty()) { "the settings index is empty; nothing would be searchable" }

        SearchSources.registerSettingsSearch { query ->
            val pages =
                SettingsPageRegistryImpl.visiblePages().map {
                    pluginPageEntry(it.pageId, it.displayName, it.description)
                }
            SettingsSearchMatcher
                .search(query, builtIn + pages)
                .map { hit ->
                    SettingSearchRecord(
                        label = hit.entry.label,
                        breadcrumb = hit.entry.breadcrumb,
                        section = hit.entry.section?.name,
                        pluginPageId = hit.entry.pluginPageId,
                        panelId = hit.entry.panel?.panelId,
                        group = hit.entry.group,
                        highlightable = hit.entry.highlightable,
                        score = hit.score,
                    )
                }
        }
    }
}

/** Builds a plugin-page entry. Plugins supply only a name and a description, so that is all we index. */
fun pluginPageEntry(
    pageId: String,
    displayName: String,
    description: String,
): SettingsSearchEntry =
    SettingsSearchEntry(
        label = displayName,
        pluginPageId = pageId,
        // Lowercased and de-punctuated to match the convention the built-in keywords follow, and
        // short words dropped: a description is prose, so "the" and "and" would match everything.
        keywords =
            description
                .split(" ")
                .map { it.lowercase().trim(',', '.', '(', ')') }
                .filter { it.length > 3 },
        highlightable = false,
    )
