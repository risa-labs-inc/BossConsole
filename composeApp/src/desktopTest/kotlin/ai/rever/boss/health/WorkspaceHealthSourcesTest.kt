package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthRow
import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.components.plugin.PluginHealthStatus
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
        assertTrue(WorkspaceHealthSources.pluginSnapshots().isEmpty())
    }

    private fun pluginIds(): Set<String> =
        WorkspaceHealthSources
            .pluginSnapshots()
            .flatMap { snapshot -> snapshot.rows.map { it.pluginId } }
            .toSet()

    private fun snapshotOf(pluginId: String) =
        PluginHealthSnapshot(
            rows = listOf(PluginHealthRow(pluginId, pluginId, PluginHealthStatus.HEALTHY, "Running normally.")),
            sandboxDisabledPluginIds = emptySet(),
        )
}
