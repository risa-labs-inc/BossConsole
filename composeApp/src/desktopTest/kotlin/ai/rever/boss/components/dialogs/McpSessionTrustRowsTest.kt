package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpSessionTrust
import kotlin.test.Test
import kotlin.test.assertEquals

class McpSessionTrustRowsTest {
    @Test
    fun `rows sort by tool, then provider for same-named tools`() {
        val rows =
            sessionTrustRows(
                setOf(
                    McpSessionTrust("p.zeta", "tab_open_url"),
                    McpSessionTrust("p.alpha", "tab_open_url"),
                    McpSessionTrust("p.alpha", "bookmark_add"),
                ),
            )
        assertEquals(
            listOf("p.alpha/bookmark_add", "p.alpha/tab_open_url", "p.zeta/tab_open_url"),
            rows.map { it.toString() },
        )
    }
}
