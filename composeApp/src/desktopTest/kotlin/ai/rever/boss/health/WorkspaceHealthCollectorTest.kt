package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspaceHealthCollectorTest {
    @Test
    fun `a source that throws is reported as unchecked while the others are still read`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { error("manager already disposed") },
                browserHealth = { BrowserEngineHealth.Unresponsive },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.PLUGINS), report.unchecked)
        assertEquals(listOf(HealthCodes.BROWSER_ENGINE_UNRESPONSIVE), report.findings.map { it.code })
    }

    @Test
    fun `a class missing from a health path is contained and never reported as healthy`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { listOf(ONE_WINDOW) },
                browserHealth = { throw NoClassDefFoundError("com/teamdev/jxbrowser/engine/Engine") },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.BROWSER), report.unchecked)
        assertFalse(report.degraded)
    }

    @Test
    fun `with no window open plugin health is unchecked rather than healthy`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { emptyList() },
                browserHealth = { BrowserEngineHealth.Healthy },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.PLUGINS), report.unchecked)
        assertFalse(report.degraded)
    }

    private companion object {
        val ONE_WINDOW = PluginHealthSnapshot(rows = emptyList(), sandboxDisabledPluginIds = emptySet())
    }
}
