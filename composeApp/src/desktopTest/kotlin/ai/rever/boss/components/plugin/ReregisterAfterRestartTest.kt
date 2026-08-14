package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `the sequence unregisters then registers, and does not dispose`() {
        val calls = mutableListOf<String>()

        val result =
            reregisterInPlace(
                unregisterAll = { calls.add("unregister") },
                register = { calls.add("register") },
            )

        assertTrue(result.isSuccess)
        // Exactly what disablePlugin/enablePlugin do between them. dispose()
        // has only ever preceded a classloader close, so a dispose() ->
        // register() no plugin was written against would relocate this PR's own
        // bug into them: one that cancels a scope it owns as a field comes back
        // attaching every launch to a cancelled scope, silently.
        assertEquals(listOf("unregister", "register"), calls)
    }

    @Test
    fun `a register that throws tears down what it half-registered`() {
        val calls = mutableListOf<String>()

        val result =
            reregisterInPlace(
                unregisterAll = { calls.add("unregister") },
                register = {
                    calls.add("register")
                    error("register blew up")
                },
            )

        assertTrue(result.isFailure)
        assertEquals(
            listOf("unregister", "register", "unregister"),
            calls,
            "a half-registered plugin leaves agent-callable MCP tools live",
        )
    }

    @Test
    fun `a teardown that throws does not mask the original failure`() {
        var unregisterCalls = 0

        val result =
            reregisterInPlace(
                unregisterAll = {
                    unregisterCalls++
                    if (unregisterCalls == 2) error("teardown blew up too")
                },
                register = { error("the real problem") },
            )

        assertTrue(result.isFailure)
        assertEquals("the real problem", result.exceptionOrNull()?.message)
    }
}
