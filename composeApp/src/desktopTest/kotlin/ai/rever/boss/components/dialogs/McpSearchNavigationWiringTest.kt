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
        val mcpBranch =
            source
                .substringAfter("is SearchResult.McpToolResult -> {", missingDelimiterValue = "")
                .substringBefore("\n        }", missingDelimiterValue = "")
        assertContains(mcpBranch, "dispatch(\"tool\", result.name, result, onMcpToolSelect)")
        assertFalse(mcpBranch.contains("onDismiss()"), "MCP picks must not revert to dismiss-only behavior")
    }

    @Test
    fun `host uses reveal and handles unavailable Toolbox without executing a tool`() {
        val source = File("src/commonMain/kotlin/ai/rever/boss/app/BossAppDialogs.kt").readText()
        val callback =
            source
                .substringAfter("onMcpToolSelect = { mcp ->", missingDelimiterValue = "")
                .substringBefore("\n            },", missingDelimiterValue = "")
        assertContains(callback, "state.showGlobalSearchDialog = false")
        assertContains(callback, "revealPlugin(PanelIds.PLUGIN_MANAGER.panelId)")
        assertContains(callback, "StatusMessageManager.showMessage(")
        val availableBranch =
            callback.substringAfter("if (state.draggablePanelComponent.toolboxSidebarItem() != null) {", "")
        assertContains(availableBranch.substringBefore("} else {", ""), "revealPlugin(")
        val unavailableBranch = availableBranch.substringAfter("} else {", "").substringBefore("}", "")
        assertContains(unavailableBranch, "Toolbox is unavailable in this window")
        assertFalse(unavailableBranch.contains("revealPlugin("))
        assertFalse(callback.contains("activatePlugin("), "The host must not use the toggling entry point directly")
        assertFalse(callback.contains("invoke("), "Search must never execute the selected tool")
        assertFalse(callback.contains("setToolEnabled("), "Search must not change the kill-switch itself")
    }
}
