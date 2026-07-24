package ai.rever.boss.plugin.tab.jupyter

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.random.Random

/**
 * Tab info for the native Jupyter Notebook tab (provided by the
 * `ai.rever.boss.plugin.dynamic.jupyternotebook` plugin).
 *
 * The host constructs this to open a notebook tab; the plugin's registered
 * factory renders it (it reads only the [TabInfo] surface, so no dependency on
 * the plugin's own tab-data class is required). [filePath] backs the tab with a
 * `.ipynb` file (empty = a new untitled notebook).
 */
data class JupyterTabInfo(
    override val id: String,
    override val typeId: TabTypeId = TYPE_ID,
    override val title: String = "Notebook",
    override val icon: ImageVector = Icons.Outlined.Code,
    override val tabIcon: TabIcon? = null,
    val filePath: String = "",
) : TabInfo {
    /**
     * Return a copy retitled to [newTitle]. The host's `BossTabUpdateProvider` resolves
     * this reflectively (like `EditorTabInfo`/`TerminalTabInfo`), so the plugin can rename
     * a notebook tab from its own UI; without it title updates are a silent no-op.
     */
    fun updateTitle(newTitle: String): JupyterTabInfo = copy(title = newTitle)

    companion object {
        /** Single source of truth for the jupyter tab type id (matches the plugin's `JupyterTabType`). */
        val TYPE_ID = TabTypeId("jupyter")

        /** Fresh collision-safe tab id — one place for the format (same scheme as terminal/editor tabs). */
        fun newId(): String = "jupyter-${Random.nextLong()}"

        /**
         * Build a notebook tab for [filePath] (`""` = a new untitled notebook) with a
         * fresh, collision-safe id. [title] overrides the derived file basename; a
         * blank [title] falls back to the derived name, never a blank tab.
         *
         * The derived title is intentionally the full basename — extension included,
         * `analysis.ipynb` not `analysis` — matching how editor tabs title themselves.
         */
        fun create(
            filePath: String,
            title: String? = null,
        ): JupyterTabInfo {
            val path = filePath.trim()
            return JupyterTabInfo(
                id = newId(),
                title =
                    title?.ifBlank { null }
                        ?: path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Notebook" },
                filePath = path,
            )
        }

        /**
         * New Tab → Jupyter: build an untitled notebook. [name] is an optional display
         * name (blank = "Notebook"), not a file path.
         */
        fun createUntitled(name: String): JupyterTabInfo = create("", title = name.trim())
    }
}
