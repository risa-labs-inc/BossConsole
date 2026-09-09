package ai.rever.boss.components.dialogs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** Pins the UI wiring that registry and search-index tests cannot exercise. */
class McpSearchNavigationWiringTest {
    @Test
    fun `mouse and keyboard MCP selection reach the host navigation callback`() {
        val source = File("src/commonMain/kotlin/ai/rever/boss/components/dialogs/GlobalSearchDialog.kt").readText()
        assertContains(source, "selectResult(filteredResults[selectedIndex])")
        assertContains(source, "onResultClick = { result -> selectResult(result) }")
        val mcpBranch = source.substringAfter("is SearchResult.McpToolResult -> {").substringBefore("\n        }")
        assertContains(mcpBranch, "dispatch(\"tool\", result.name, result, onMcpToolSelect)")
        assertFalse(mcpBranch.contains("onDismiss()"), "MCP picks must not revert to dismiss-only behavior")
    }

    @Test
    fun `host reveals Toolbox without toggling its visibility or executing a tool`() {
        val source = File("src/commonMain/kotlin/ai/rever/boss/app/BossAppDialogs.kt").readText()
        val callback = source.substringAfter("onMcpToolSelect = { mcp ->").substringBefore("\n            },")
        assertContains(callback, "state.showGlobalSearchDialog = false")
        assertContains(callback, "revealPlugin(PanelIds.PLUGIN_MANAGER.panelId)")
        assertContains(callback, "StatusMessageManager.showMessage(")
        assertFalse(callback.contains("activatePlugin("), "An already-open Toolbox must stay visible")
        assertFalse(callback.contains("invoke("), "Search must never execute the selected tool")
        assertFalse(callback.contains("setToolEnabled("), "Search must not change the kill-switch itself")
    }
}
