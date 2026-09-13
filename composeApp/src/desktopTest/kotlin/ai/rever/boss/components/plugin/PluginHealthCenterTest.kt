package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PluginHealthCenterTest {
    @Test
    fun `maps disabled plugin to existing enable action`() {
        val row =
            pluginHealthRows(
                pluginStates = mapOf("notes" to plugin("notes", "Notes", PluginState.DISABLED)),
                loadGates = emptyMap(),
                crashedPluginIds = emptySet(),
                inaccessiblePluginIds = emptySet(),
                incompatiblePluginIds = emptySet(),
            ).single()

        assertEquals(PluginHealthStatus.UNAVAILABLE, row.status)
        assertEquals(PluginHealthAction.ENABLE, row.action)
    }

    @Test
    fun `load gate takes precedence over a manager state`() {
        val row =
            pluginHealthRows(
                pluginStates = mapOf("notes" to plugin("notes", "Notes", PluginState.LOADED)),
                loadGates = mapOf("notes" to PluginLoadGate.NeedsNewerHost("notes", "Notes", "9.5.0", "9.4.0")),
                crashedPluginIds = emptySet(),
                inaccessiblePluginIds = emptySet(),
                incompatiblePluginIds = emptySet(),
            ).single()

        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, row.status)
        assertNull(row.action)
        assertEquals("This plugin requires a newer BOSS version.", row.detail)
    }

    @Test
    fun `inaccessible or incompatible plugin never receives a recovery action`() {
        val state = mapOf("notes" to plugin("notes", "Notes", PluginState.DISABLED))

        val inaccessible = pluginHealthRows(state, emptyMap(), emptySet(), setOf("notes"), emptySet()).single()
        val incompatible = pluginHealthRows(state, emptyMap(), setOf("notes"), emptySet(), setOf("notes")).single()

        assertEquals(PluginHealthStatus.UNAVAILABLE, inaccessible.status)
        assertNull(inaccessible.action)
        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, incompatible.status)
        assertNull(incompatible.action)
    }

    @Test
    fun `crash state is classified without retaining or displaying its throwable`() {
        val row =
            pluginHealthRows(
                mapOf("notes" to plugin("notes", "Notes", PluginState.LOADED)),
                emptyMap(),
                setOf("notes"),
                emptySet(),
                emptySet(),
            ).single()

        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, row.status)
        assertEquals(PluginHealthAction.RELOAD, row.action)
        assertEquals("The plugin needs recovery after an unexpected failure.", row.detail)
    }

    @Test
    fun `disabled plugin marked enabled is not offered a second enable`() {
        val row =
            pluginHealthRows(
                mapOf("notes" to plugin("notes", "Notes", PluginState.DISABLED, enabled = true)),
                emptyMap(),
                emptySet(),
                emptySet(),
                emptySet(),
            ).single()

        assertEquals(PluginHealthStatus.UNAVAILABLE, row.status)
        assertNull(row.action)
    }

    @Test
    fun `manager errors are presented as a safe summary`() {
        val row =
            pluginHealthRows(
                mapOf(
                    "notes" to
                        plugin(
                            "notes",
                            "Notes",
                            PluginState.LOADED,
                            errorMessage = "token=secret at C:/Users/Ayush/.boss",
                        ),
                ),
                emptyMap(),
                emptySet(),
                emptySet(),
                emptySet(),
            ).single()

        assertEquals("The plugin reported a manager error.", row.detail)
        assertFalse(row.detail.contains("secret"))
        assertFalse(row.detail.contains("C:/"))
        assertEquals(PluginHealthAction.RELOAD, row.action)
    }

    @Test
    fun `hot reload exclusions never receive a reload action`() {
        val pluginId = TabTypePlugins.FLUCK_BROWSER
        val row =
            pluginHealthRows(
                mapOf(
                    pluginId to
                        plugin(
                            pluginId,
                            "Browser",
                            PluginState.LOADED,
                            errorMessage = "failure",
                        ),
                ),
                emptyMap(),
                emptySet(),
                emptySet(),
                emptySet(),
            ).single()

        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, row.status)
        assertNull(row.action)
    }

    @Test
    fun `signature gate keeps its internal reason out of the health center`() {
        val row =
            pluginHealthRows(
                emptyMap(),
                mapOf("notes" to PluginLoadGate.SignatureRejected("notes", "Notes", "signature path C:/private/token")),
                emptySet(),
                emptySet(),
                emptySet(),
            ).single()

        assertEquals("The plugin signature could not be verified.", row.detail)
        assertFalse(row.detail.contains("C:/"))
    }

    @Test
    fun `transitional and unloaded states are not healthy and cannot reload`() {
        for (state in PluginState.entries.filter { it != PluginState.LOADED && it != PluginState.DISABLED }) {
            val row =
                pluginHealthRows(
                    mapOf("notes" to plugin("notes", "Notes", state)),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ).single()
            assertFalse(row.status == PluginHealthStatus.HEALTHY, state.name)
            assertNull(row.action, state.name)
        }
    }

    @Test
    fun `an error during unloading cannot offer reload`() {
        val row =
            pluginHealthRows(
                mapOf("notes" to plugin("notes", "Notes", PluginState.UNLOADING, errorMessage = "error")),
                emptyMap(),
                emptySet(),
                emptySet(),
                emptySet(),
            ).single()
        assertNull(row.action)
    }

    @Test
    fun `access-only record remains visible without a loaded manager entry`() {
        val row = pluginHealthRows(emptyMap(), emptyMap(), emptySet(), setOf("notes"), emptySet()).single()
        assertEquals(PluginHealthStatus.UNAVAILABLE, row.status)
        assertEquals("Access is required for this plugin.", row.detail)
        assertNull(row.action)
    }

    @Test
    fun `watchdog disabled sandbox offers full reload instead of registering twice`() {
        val states = mapOf("notes" to plugin("notes", "Notes", PluginState.LOADED))
        val healthy = pluginHealthRows(states, emptyMap(), emptySet(), emptySet(), emptySet())
        val row = healthRowsWithSandboxDisables(healthy, setOf("notes")).single()
        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, row.status)
        assertEquals(PluginHealthAction.RELOAD, row.action)
        assertEquals(healthy, healthRowsWithSandboxDisables(healthy, emptySet()))
    }

    @Test
    fun `watchdog recovery respects native restart and access restrictions`() {
        val id = HotReloadPolicy.NOT_HOT_RELOADABLE.first()
        val states = mapOf(id to plugin(id, "Browser", PluginState.LOADED))
        val healthy = pluginHealthRows(states, emptyMap(), emptySet(), emptySet(), emptySet())
        val row = healthRowsWithSandboxDisables(healthy, setOf(id)).single()
        assertNull(row.action)
        assertEquals("Restart BOSS to recover this plugin.", row.detail)
        val hidden = pluginHealthRows(states, emptyMap(), emptySet(), setOf(id), emptySet())
        assertEquals(hidden, healthRowsWithSandboxDisables(hidden, setOf(id)))
    }

    @Test
    fun `admin-only entries explain access and preserve manager row identity`() {
        val info =
            plugin("manifest-id", "Admin", PluginState.DISABLED).let {
                it.copy(manifest = it.manifest.copy(requiresAdmin = true), enabled = true)
            }
        val states = mapOf("manager-id" to info)
        val hidden = healthInaccessiblePluginIds(states, false, emptySet())
        val row = pluginHealthRows(states, emptyMap(), emptySet(), hidden, emptySet()).single()
        assertEquals("manager-id", row.pluginId)
        assertEquals("Access is required for this plugin.", row.detail)
        assertNull(row.action)
        assertEquals(emptySet(), healthInaccessiblePluginIds(states, true, emptySet()))
    }

    @Test
    fun `process-wide incompatibility does not create rows for another window or an uninstalled plugin`() {
        assertEquals(emptyList(), pluginHealthRows(emptyMap(), emptyMap(), emptySet(), emptySet(), setOf("other")))
        val states = mapOf("notes" to plugin("notes", "Notes", PluginState.LOADED))
        val rows = pluginHealthRows(states, emptyMap(), emptySet(), emptySet(), setOf("other", "notes"))
        assertEquals(listOf("notes"), rows.map { it.pluginId })
        assertEquals(PluginHealthStatus.NEEDS_ATTENTION, rows.single().status)
        assertNull(rows.single().action)
    }

    private fun plugin(
        id: String,
        name: String,
        state: PluginState,
        enabled: Boolean = state == PluginState.LOADED,
        errorMessage: String? = null,
    ) = DynamicPluginInfo(
        manifest =
            PluginManifest(
                pluginId = id,
                displayName = name,
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.$id",
            ),
        jarPath = "C:/plugins/$id.jar",
        state = state,
        loadedAt = 0,
        enabled = enabled,
        errorMessage = errorMessage,
    )
}
