package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [shouldReregisterAfterRestart] - the predicate that decides
 * whether a restarted plugin gets `register()` run again.
 *
 * The restart is decided by a watchdog on its own coroutine and lands some
 * time later, so what matters here is everything the user might have done in
 * between. Re-registering a plugin they disabled would put its panels, tab
 * types and agent-callable MCP tools straight back.
 */
class ReregisterAfterRestartTest {
    private fun info(
        state: PluginState,
        enabled: Boolean,
    ) = DynamicPluginInfo(
        manifest =
            PluginManifest(
                pluginId = "ai.rever.boss.plugin.dynamic.example",
                displayName = "Example",
                version = "1.0.0",
                apiVersion = "1.0",
                mainClass = "com.example.ExamplePlugin",
                type = PluginType.PANEL,
            ),
        jarPath = "/plugins/example.jar",
        state = state,
        loadedAt = 0L,
        enabled = enabled,
    )

    @Test
    fun `a running plugin is re-registered`() {
        assertTrue(shouldReregisterAfterRestart(info(PluginState.LOADED, enabled = true)))
    }

    @Test
    fun `a plugin disabled while the restart was in flight is left alone`() {
        assertFalse(
            shouldReregisterAfterRestart(info(PluginState.LOADED, enabled = false)),
            "re-registering would undo the disable the user just asked for",
        )
    }

    @Test
    fun `a plugin whose state says disabled is left alone even when the enabled flag disagrees`() {
        // installPlugin records DISABLED for a plugin rejected as binary
        // incompatible; other paths leave `enabled` set. Either half saying no
        // has to be enough.
        assertFalse(shouldReregisterAfterRestart(info(PluginState.DISABLED, enabled = true)))
    }

    @Test
    fun `a plugin mid-unload is left alone`() {
        assertFalse(shouldReregisterAfterRestart(info(PluginState.UNLOADING, enabled = true)))
        assertFalse(shouldReregisterAfterRestart(info(PluginState.UNLOADED, enabled = true)))
    }

    @Test
    fun `a plugin that is gone entirely is left alone`() {
        assertFalse(
            shouldReregisterAfterRestart(null),
            "uninstalled between the restart and this callback",
        )
    }
}
