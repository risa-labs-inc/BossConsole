package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthRow
import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.components.plugin.PluginHealthStatus
import ai.rever.boss.utils.logging.BossLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceHealthSourcesTest {
    @BeforeTest
    fun setUp() {
        WorkspaceHealthSources.clear()
    }

    @AfterTest
    fun tearDown() {
        WorkspaceHealthSources.clear()
    }

    @Test
    fun `every registered window contributes a snapshot`() {
        WorkspaceHealthSources.registerPlugins("window-a") { snapshotOf("notes") }
        WorkspaceHealthSources.registerPlugins("window-b") { snapshotOf("tasks") }

        assertEquals(setOf("notes", "tasks"), pluginIds())
    }

    @Test
    fun `unregistering a replaced source keeps the registration that replaced it`() {
        val first: () -> PluginHealthSnapshot = { snapshotOf("first") }
        val second: () -> PluginHealthSnapshot = { snapshotOf("second") }
        WorkspaceHealthSources.registerPlugins("window", first)
        WorkspaceHealthSources.registerPlugins("window", second)

        WorkspaceHealthSources.unregisterPlugins("window", first)
        assertEquals(setOf("second"), pluginIds())

        WorkspaceHealthSources.unregisterPlugins("window", second)
        assertTrue(WorkspaceHealthSources.pluginSnapshots().snapshots.isEmpty())
    }

    @Test
    fun `a window source that throws is counted while every other window is still read`() {
        WorkspaceHealthSources.registerPlugins("window-a") { snapshotOf("notes") }
        WorkspaceHealthSources.registerPlugins("window-b") { error("manager already disposed") }
        WorkspaceHealthSources.registerPlugins("window-c") { throw NoClassDefFoundError("ai/rever/boss/Plugin") }

        val read = WorkspaceHealthSources.pluginSnapshots()

        assertEquals(setOf("notes"), read.snapshots.flatMap { s -> s.rows.map { it.pluginId } }.toSet())
        assertEquals(2, read.failedSources)
    }

    @Test
    fun `a failing window source does not hide a stopped plugin in another window`() {
        WorkspaceHealthSources.registerPlugins("window-a") { snapshotOf("terminaltab", stopped = true) }
        WorkspaceHealthSources.registerPlugins("window-b") { error("manager already disposed") }

        val report =
            WorkspaceHealthCollector(
                pluginSnapshots = WorkspaceHealthSources::pluginSnapshots,
                browserHealth = { BrowserEngineHealth.Healthy },
                mcpFaults = { McpFaults(killSwitch = null, policy = null) },
            ).collect()

        val finding = report.findings.single()
        assertEquals(HealthCodes.PLUGIN_STOPPED, finding.code)
        assertEquals("terminaltab", finding.subject)
        assertTrue(report.unchecked.isEmpty(), "the window that answered was read, so plugins is not unchecked")
        assertEquals(setOf(HealthArea.PLUGINS), report.partial, "partial coverage must be declared, not hidden")
    }

    @Test
    fun `a window source that keeps failing is logged once until it recovers`() {
        // The status bar reads this every few seconds in every window, so a warning per read would
        // repeat for as long as the window stays broken.
        val windowId = "window-flaky-${System.nanoTime()}"
        var broken = true
        WorkspaceHealthSources.registerPlugins(windowId) {
            if (broken) error("manager disposed") else snapshotOf("notes")
        }

        repeat(3) { WorkspaceHealthSources.pluginSnapshots() }
        assertEquals(1, warningsFor(windowId), "a source failing on every read must be logged once, not per read")

        broken = false
        WorkspaceHealthSources.pluginSnapshots()
        broken = true
        WorkspaceHealthSources.pluginSnapshots()
        assertEquals(2, warningsFor(windowId), "failing again after a recovery must be logged again")
    }

    private fun warningsFor(windowId: String): Int =
        BossLogger.getRecentLogs(limit = 1000).count {
            it.data?.get("windowId") == windowId && it.message.startsWith("Plugin health source could not be read")
        }

    private fun pluginIds(): Set<String> =
        WorkspaceHealthSources
            .pluginSnapshots()
            .snapshots
            .flatMap { snapshot -> snapshot.rows.map { it.pluginId } }
            .toSet()

    private fun snapshotOf(
        pluginId: String,
        stopped: Boolean = false,
    ): PluginHealthSnapshot {
        val status = if (stopped) PluginHealthStatus.NEEDS_ATTENTION else PluginHealthStatus.HEALTHY
        val detail = if (stopped) "The plugin stopped and needs recovery." else "Running normally."
        return PluginHealthSnapshot(
            rows = listOf(PluginHealthRow(pluginId, pluginId, status, detail)),
            sandboxDisabledPluginIds = if (stopped) setOf(pluginId) else emptySet(),
        )
    }
}
