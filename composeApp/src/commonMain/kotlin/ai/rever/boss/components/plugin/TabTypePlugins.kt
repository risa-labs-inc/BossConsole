package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.TabTypeId

/**
 * Which plugin provides which tab type.
 *
 * The host needs this to answer "the OS just handed me a markdown file and there
 * is no editor tab type - what do I offer to install?". It cannot be derived: the
 * mapping lives in each plugin's `plugin.json` (`tab.typeId`), and when the
 * plugin is absent there is no manifest to read. `TabTypeId` carries a `pluginId`
 * field, but the constants the host opens tabs with (`FluckTabType.typeId` and
 * friends, in the api artifact) leave it at the default, so it cannot be used
 * either.
 *
 * A literal table, for the same reason `PluginDependencyResolution.NOT_USER_INSTALLABLE`
 * holds the api plugin id as a literal: the value lives in another repository and
 * nothing links the two at compile time. Getting an entry wrong is visible
 * immediately - the dialog offers to install the wrong plugin - rather than
 * silent.
 *
 * Only the tab types the host itself opens are listed - six, of which `diff`
 * and `composer` both resolve to editor-tab (the host opens them, the
 * plugin renders them). A plugin that opens its own tab type does not come
 * through here, because it is loaded by definition.
 */
object TabTypePlugins {
    /** Browser tabs: `fluck-browser`. */
    const val FLUCK_BROWSER = "ai.rever.boss.plugin.dynamic.fluckbrowser"

    /** Code editor tabs: `editor-tab`. */
    const val EDITOR_TAB = "ai.rever.boss.plugin.dynamic.editortab"

    /** Terminal tabs: `terminal-tab`. */
    const val TERMINAL_TAB = "ai.rever.boss.plugin.dynamic.terminaltab"

    /** Notebook tabs: `jupyter-notebook`. */
    const val JUPYTER_NOTEBOOK = "ai.rever.boss.plugin.dynamic.jupyternotebook"

    private val byTypeId =
        mapOf(
            "fluck" to FLUCK_BROWSER,
            "editor" to EDITOR_TAB,
            // diff and composer are editor-tab's too: the host opens both (a diff via
            // GitDataProvider.openDiff, a composer tab), so without these a request with
            // editor-tab absent fell through to a 15s wait and a silent no-op instead of
            // the install prompt.
            "diff" to EDITOR_TAB,
            "composer" to EDITOR_TAB,
            "terminal" to TERMINAL_TAB,
            "jupyter" to JUPYTER_NOTEBOOK,
        )

    /**
     * The plugin that provides [typeId], or null for a type the host does not
     * open itself.
     *
     * Keyed on the string, not the whole [TabTypeId]: `TabTypeId` is a data class
     * whose equality includes `pluginId` and `defaultOrder`, and keying on it
     * made a lookup miss for a type that was plainly registered - the same trap
     * `panelid-defaultorder-silent-miss` records for panels.
     */
    fun pluginFor(typeId: TabTypeId): String? = byTypeId[typeId.typeId]

    /** Human name for the thing a missing plugin would have opened, for the prompt's copy. */
    fun describe(typeId: TabTypeId): String =
        when (typeId.typeId) {
            "fluck" -> "web pages"
            "editor" -> "files in the editor"
            "diff" -> "git diffs"
            "composer" -> "the AI composer"
            "terminal" -> "terminals"
            "jupyter" -> "notebooks"
            else -> typeId.typeId
        }
}
