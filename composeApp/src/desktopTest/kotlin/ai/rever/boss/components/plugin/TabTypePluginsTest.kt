package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [TabTypePlugins], the table that answers "which plugin renders this
 * tab type" when the plugin is absent and there is no manifest to read.
 */
class TabTypePluginsTest {
    @Test
    fun `every tab type the host opens itself resolves to a plugin`() {
        // If one of these returns null, the missing-plugin dialog cannot be
        // offered for it and the tab is dropped silently again.
        assertEquals(TabTypePlugins.FLUCK_BROWSER, TabTypePlugins.pluginFor(FluckTabType.typeId))
        assertEquals(TabTypePlugins.EDITOR_TAB, TabTypePlugins.pluginFor(CodeEditorTabType.typeId))
        // diff and composer are editor-tab's too, and the host opens both - without
        // them a diff-open with editor-tab absent hangs 15s and silently drops.
        assertEquals(TabTypePlugins.EDITOR_TAB, TabTypePlugins.pluginFor(DiffTabType.typeId))
        assertEquals(TabTypePlugins.EDITOR_TAB, TabTypePlugins.pluginFor(ComposerTabType.typeId))
        assertEquals(TabTypePlugins.TERMINAL_TAB, TabTypePlugins.pluginFor(TerminalTabType.typeId))
        assertEquals(TabTypePlugins.JUPYTER_NOTEBOOK, TabTypePlugins.pluginFor(JupyterTabInfo.TYPE_ID))
    }

    @Test
    fun `the lookup keys on the type string, not the whole TabTypeId`() {
        // TabTypeId is a data class whose equality includes pluginId and
        // defaultOrder. Keying on the whole value made a lookup miss for a type
        // that was plainly registered - the trap panelid-defaultorder-silent-miss
        // records for panels. A TabTypeId built by a plugin with its own pluginId
        // must still resolve.
        val fromElsewhere = TabTypeId(typeId = "editor", pluginId = "some.other.plugin")
        assertEquals(TabTypePlugins.EDITOR_TAB, TabTypePlugins.pluginFor(fromElsewhere))
    }

    @Test
    fun `an unknown type resolves to nothing rather than a guess`() {
        // A plugin's own tab type. Offering to install something would offer the
        // wrong plugin.
        assertNull(TabTypePlugins.pluginFor(TabTypeId("some-plugin-tab")))
    }

    @Test
    fun `each type has copy for the dialog`() {
        listOf(FluckTabType.typeId, CodeEditorTabType.typeId, TerminalTabType.typeId, JupyterTabInfo.TYPE_ID)
            .forEach { typeId ->
                val described = TabTypePlugins.describe(typeId)
                assertNotNull(described)
                // Not the raw type id: the sentence is "needs X, the plugin that
                // opens <this>", and "fluck" is not something to say to a user.
                assertEquals(false, described == typeId.typeId, "describe(${typeId.typeId}) returned the raw id")
            }
    }

    @Test
    fun `an unknown type falls back to its id rather than throwing`() {
        assertEquals("weird-tab", TabTypePlugins.describe(TabTypeId("weird-tab")))
    }
}
