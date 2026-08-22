package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which layouts are worth booting Chromium for before they are applied.
 *
 * A browser tab is the only thing in a workspace that costs a process tree, and the pre-warm this
 * gates is the difference between the boot happening during the bounded wait for plugin tab types
 * and happening inside the tab, where the user watches it. The predicate has to stay narrow in the
 * other direction too: a terminal-only or editor-only layout must not start an engine nobody asked
 * for, which is the whole reason the unforced startup gate exists.
 */
class BrowserEngineWarmupTriggerTest {
    @Test
    fun `a layout with a browser tab warms the engine`() {
        assertTrue(needsBrowserEngine(setOf(FluckTabType.typeId)))
        assertTrue(needsBrowserEngine(setOf(TerminalTabType.typeId, FluckTabType.typeId)))
    }

    @Test
    fun `a layout without one does not`() {
        assertFalse(needsBrowserEngine(emptySet()))
        assertFalse(needsBrowserEngine(setOf(TerminalTabType.typeId)))
        assertFalse(needsBrowserEngine(setOf(TerminalTabType.typeId, CodeEditorTabType.typeId)))
    }
}
