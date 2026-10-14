package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthRow
import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.components.plugin.PluginHealthStatus
import ai.rever.boss.utils.logging.BossLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceHealthCollectorTest {
    @Test
    fun `a source that keeps failing is logged once, not on every read`() {
        HealthSourceWarnings.clear()
        val collector =
            WorkspaceHealthCollector(
                pluginSnapshots = { PluginSnapshotRead(listOf(ONE_WINDOW)) },
                browserHealth = { error("engine probe failed ${System.nanoTime()}") },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            )
        val before = browserWarnings()

        repeat(3) { assertEquals(setOf(HealthArea.BROWSER), collector.collect().unchecked) }

        assertEquals(1, browserWarnings() - before, "the status bar re-reads every few seconds; one warning is enough")
        HealthSourceWarnings.clear()
    }

    private fun browserWarnings(): Int =
        BossLogger.getRecentLogs(limit = 1000).count {
            it.data?.get("area") == HealthArea.BROWSER.wireName &&
                it.message == "Workspace health source could not be read"
        }

    @Test
    fun `a source that throws is reported as unchecked while the others are still read`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { error("manager already disposed") },
                browserHealth = { BrowserEngineHealth.Unresponsive },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.PLUGINS), report.unchecked)
        assertTrue(report.partial.isEmpty(), "nothing was read, so the area is unchecked rather than partial")
        assertEquals(listOf(HealthCodes.BROWSER_ENGINE_UNRESPONSIVE), report.findings.map { it.code })
    }

    @Test
    fun `a class missing from a health path is contained and never reported as healthy`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { PluginSnapshotRead(listOf(ONE_WINDOW)) },
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
                pluginSnapshots = { PluginSnapshotRead(emptyList()) },
                browserHealth = { BrowserEngineHealth.Healthy },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.PLUGINS), report.unchecked)
        assertFalse(report.degraded)
    }

    @Test
    fun `snapshots that were read survive a failing source and the area is reported partial`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { PluginSnapshotRead(listOf(STOPPED_WINDOW), failedSources = 1) },
                browserHealth = { BrowserEngineHealth.Healthy },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(listOf(HealthCodes.PLUGIN_STOPPED), report.findings.map { it.code })
        assertTrue(report.unchecked.isEmpty())
        assertEquals(setOf(HealthArea.PLUGINS), report.partial)
    }

    @Test
    fun `plugins are unchecked rather than partial when every window source failed`() {
        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = { PluginSnapshotRead(emptyList(), failedSources = 2) },
                browserHealth = { BrowserEngineHealth.Healthy },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        assertEquals(setOf(HealthArea.PLUGINS), report.unchecked)
        assertTrue(report.partial.isEmpty())
        assertFalse(report.degraded)
    }

    private companion object {
        val ONE_WINDOW = PluginHealthSnapshot(rows = emptyList(), sandboxDisabledPluginIds = emptySet())

        val STOPPED_WINDOW =
            PluginHealthSnapshot(
                rows =
                    listOf(
                        PluginHealthRow(
                            "terminaltab",
                            "Terminal Tab",
                            PluginHealthStatus.NEEDS_ATTENTION,
                            "The plugin stopped and needs recovery.",
                        ),
                    ),
                sandboxDisabledPluginIds = setOf("terminaltab"),
            )
    }
}
