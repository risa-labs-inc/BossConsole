package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginRestartRecoveryTest {
    private val loaded =
        DynamicPluginInfo(
            manifest =
                PluginManifest(
                    pluginId = "com.example.recovered",
                    displayName = "Recovered",
                    version = "1.0.0",
                    apiVersion = "1.0",
                    mainClass = "example.Plugin",
                    type = PluginType.PANEL,
                ),
            jarPath = "/unused.jar",
            state = PluginState.LOADED,
            loadedAt = 0L,
            enabled = true,
        )

    @Test
    fun `successful running reload records recovery but disabled and failed loads do not`() {
        val recorded = mutableListOf<String>()
        recordRestartRecoveryIfRunning(loaded.copy(enabled = false), false, recorded::add)
        recordRestartRecoveryIfRunning(loaded.copy(state = PluginState.DISABLED), false, recorded::add)
        recordRestartRecoveryIfRunning(loaded.copy(state = PluginState.ERROR), false, recorded::add)
        recordRestartRecoveryIfRunning(loaded, true, recorded::add)
        assertEquals(emptyList(), recorded)
        recordRestartRecoveryIfRunning(loaded, false, recorded::add)
        assertEquals(listOf(loaded.manifest.pluginId), recorded)
    }
}
