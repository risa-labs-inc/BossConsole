package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisabledHomeToolsTest {
    @Test
    fun `disabled tile is installed but not ready and navigates to its own recovery`() =
        runTest {
            val tool = catalog(listOf(row("notes")), setOf("notes"), setOf("notes")).single()
            assertEquals(HomeToolLaunch.Recover("notes"), tool.launch)
            assertTrue(tool.isInstalled)
            assertFalse(tool.isReady)
            assertTrue(HomeToolFilter.READY.accepts(tool))
            assertFalse(HomeToolFilter.AVAILABLE.accepts(tool))
            val event =
                async(start = CoroutineStart.UNDISPATCHED) {
                    MenuActionsHandler.showPluginHealthCenterEvents.first()
                }
            HomeActions("window-a", this).launch(tool, mutableStateMapOf())
            assertEquals("window-a", event.await().windowId)
            assertEquals("notes", event.await().target?.pluginId)
        }

    @Test
    fun `service incompatible protected and access restricted entries have no disabled tile`() {
        val rows =
            listOf(
                row("service").copy(isService = true),
                row("incompatible").copy(isCompatible = false),
                row("admin").copy(requiresAdmin = true),
                row("ai.rever.boss.plugin.api"),
            )
        val ids = rows.map { it.pluginId }.toSet()
        assertEquals(emptyList(), catalog(rows, ids, ids))
    }

    @Test
    fun `missing jar never turns a stale disabled record into a recovery tile`() {
        val result = catalog(listOf(row("notes")), emptySet(), setOf("notes")).single()
        assertEquals(HomeToolLaunch.Install("notes"), result.launch)
    }

    @Test
    fun `candidate eligibility respects explicit disable and full manifest permission gate`() {
        val notes = plugin("notes")
        val states =
            mapOf(
                "notes" to notes,
                "failed" to plugin("failed").copy(state = PluginState.ERROR),
                "automatic" to plugin("automatic").copy(enabled = true),
                "missing" to plugin("missing"),
                "restricted" to
                    plugin("restricted").let {
                        it.copy(manifest = it.manifest.copy(requiredPermissions = listOf("private-tool")))
                    },
                "system" to plugin("system").let { it.copy(manifest = it.manifest.copy(systemPlugin = true)) },
            )
        val installed = states.keys - "missing"
        assertEquals(setOf("notes"), disabledHomePluginIds(states, installed, RegistryAccess()))
        assertEquals(
            setOf("notes", "restricted"),
            disabledHomePluginIds(states, installed, RegistryAccess(permissions = setOf("private-tool"))),
        )
    }

    private fun row(id: String) = HomeStorePluginInput(id, id, "", false, true, false)

    private fun catalog(
        rows: List<HomeStorePluginInput>,
        installed: Set<String>,
        disabled: Set<String>,
    ) = HomeToolCatalog
        .build(emptyList(), emptyList(), rows, installed, RegistryAccess(), disabledPluginIds = disabled)
        .filterNot { it.launch is HomeToolLaunch.HostAction }

    private fun plugin(id: String) =
        DynamicPluginInfo(
            PluginManifest(
                pluginId = id,
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "example.Main",
                displayName = id,
            ),
            "/plugins/$id.jar",
            PluginState.DISABLED,
            0L,
            false,
        )
}
