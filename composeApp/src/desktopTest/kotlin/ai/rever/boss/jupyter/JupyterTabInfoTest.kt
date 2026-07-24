package ai.rever.boss.jupyter

import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [JupyterTabInfo.create] title derivation and [JupyterTabInfo.updateTitle].
 * These are pure and lock the host-side contract the jupyter-notebook plugin relies on
 * (fresh ids, cross-platform basename, retitle-by-copy).
 */
class JupyterTabInfoTest {
    @Test
    fun `create derives title from unix path basename`() {
        val tab = JupyterTabInfo.create("/home/me/analysis.ipynb")
        assertEquals("analysis.ipynb", tab.title)
        assertEquals("/home/me/analysis.ipynb", tab.filePath)
    }

    @Test
    fun `create derives title from windows path basename`() {
        // substringAfterLast('/') leaves the whole string when there's no '/', so the
        // '\\' split must also run — otherwise the title is the full path.
        val tab = JupyterTabInfo.create("C:\\Users\\me\\report.ipynb")
        assertEquals("report.ipynb", tab.title)
    }

    @Test
    fun `create with blank path yields Notebook title`() {
        assertEquals("Notebook", JupyterTabInfo.create("").title)
        assertEquals("Notebook", JupyterTabInfo.create("   ").title)
    }

    @Test
    fun `create honours explicit title override`() {
        val tab = JupyterTabInfo.create("/tmp/x.ipynb", title = "My Notebook")
        assertEquals("My Notebook", tab.title)
    }

    @Test
    fun `create falls back to derived title when override is blank`() {
        assertEquals("x.ipynb", JupyterTabInfo.create("/tmp/x.ipynb", title = "").title)
        assertEquals("Notebook", JupyterTabInfo.create("", title = "  ").title)
    }

    @Test
    fun `createUntitled trims the name and defaults to Notebook`() {
        assertEquals("Scratch", JupyterTabInfo.createUntitled(" Scratch ").title)
        assertEquals("Notebook", JupyterTabInfo.createUntitled("   ").title)
        assertEquals("", JupyterTabInfo.createUntitled("Scratch").filePath)
    }

    @Test
    fun `create generates unique jupyter-prefixed ids`() {
        val a = JupyterTabInfo.create("/tmp/a.ipynb")
        val b = JupyterTabInfo.create("/tmp/a.ipynb")
        assertNotEquals(a.id, b.id)
        assertTrue(a.id.startsWith("jupyter-"), "id should be namespaced: ${a.id}")
    }

    @Test
    fun `updateTitle returns a retitled copy preserving id and path`() {
        val tab = JupyterTabInfo.create("/tmp/a.ipynb")
        val renamed = tab.updateTitle("Renamed")
        assertEquals("Renamed", renamed.title)
        assertEquals(tab.id, renamed.id)
        assertEquals(tab.filePath, renamed.filePath)
    }

    @Test
    fun `create uses the shared jupyter type id`() {
        assertEquals(JupyterTabInfo.TYPE_ID, JupyterTabInfo.create("/tmp/a.ipynb").typeId)
    }
}
