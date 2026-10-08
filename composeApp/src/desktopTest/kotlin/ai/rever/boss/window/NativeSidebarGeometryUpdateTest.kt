package ai.rever.boss.window

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSidebarGeometryUpdateTest {
    private val click: () -> Unit = {}
    private val sidebar = NativeTitleBarAction("sidebar", "Sidebar", sidebarWidth = 240f, onClick = click)
    private val search = NativeTitleBarAction("search", "Search", onClick = click)

    @Test
    fun `animation geometry preserves all other toolbar actions`() {
        val before = listOf(sidebar, search)
        val after = listOf(sidebar.copy(sidebarWidth = 120f, sidebarLeading = 20f, active = true), search)
        assertTrue(sidebarGeometryOnlyChange(before, after))
    }

    @Test
    fun `action changes require a complete toolbar update`() {
        val before = listOf(sidebar, search)
        val changed =
            listOf(
                listOf(sidebar.copy(enabled = false), search),
                listOf(sidebar.copy(localOnly = true), search),
                listOf(sidebar.copy(onClick = { error("replacement callback") }), search),
                listOf(sidebar, search.copy(label = "Updated search")),
                listOf(sidebar),
                listOf(search, sidebar),
            )
        changed.forEach { assertFalse(sidebarGeometryOnlyChange(before, it)) }
    }
}
