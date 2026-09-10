package ai.rever.boss.performance

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginProcessMetadataTest {
    @Test
    fun `opaque window process id never replaces explicit plugin identity`() {
        assertEquals(
            "ai.rever.boss.plugin.dynamic.example",
            pluginIdFromProcessMetadata(
                "plugin-d077f244defc24c228e8b49c4ad9a09e",
                mapOf("BOSS_PLUGIN_ID" to "ai.rever.boss.plugin.dynamic.example"),
            ),
        )
    }

    @Test
    fun `legacy processes without metadata retain their plugin name`() {
        assertEquals("example-plugin", pluginIdFromProcessMetadata("plugin-example-plugin", emptyMap<String, String>()))
    }

    @Test
    fun `missing optional environment preserves legacy identity`() {
        assertEquals("notes", pluginIdFromProcessMetadata("plugin-notes", processEnvironmentOrNull(Any())))
        assertEquals("notes", pluginIdFromProcessMetadata("plugin-notes", mapOf("BOSS_PLUGIN_ID" to " ")))
    }
}
