package ai.rever.boss.plugin.sandbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PluginSandboxManagerImpl].
 */
class PluginSandboxManagerTest {
    private lateinit var manager: PluginSandboxManagerImpl

    @BeforeEach
    fun setUp() {
        manager =
            PluginSandboxManagerImpl(
                defaultConfig =
                    SandboxConfig(
                        maxThreads = 1,
                        maxRestartAttempts = 3,
                    ),
            )
    }

    @AfterEach
    fun tearDown() =
        runTest {
            manager.dispose()
        }

    @Nested
    inner class SandboxCreationTests {
        @Test
        fun `createSandbox returns new sandbox`() {
            val sandbox = manager.createSandbox("plugin-1")

            assertNotNull(sandbox)
            assertEquals("plugin-1", sandbox.pluginId)
        }

        @Test
        fun `createSandbox returns existing sandbox for same pluginId`() {
            val sandbox1 = manager.createSandbox("plugin-1")
            val sandbox2 = manager.createSandbox("plugin-1")

            assertEquals(sandbox1, sandbox2)
        }

        @Test
        fun `createSandbox creates different sandboxes for different pluginIds`() {
            val sandbox1 = manager.createSandbox("plugin-1")
            val sandbox2 = manager.createSandbox("plugin-2")

            assertNotNull(sandbox1)
            assertNotNull(sandbox2)
            assertTrue(sandbox1 !== sandbox2)
        }

        @Test
        fun `getSandbox returns null for unknown plugin`() {
            val sandbox = manager.getSandbox("unknown-plugin")

            assertNull(sandbox)
        }

        @Test
        fun `getSandbox returns sandbox after creation`() {
            manager.createSandbox("plugin-1")

            val sandbox = manager.getSandbox("plugin-1")

            assertNotNull(sandbox)
            assertEquals("plugin-1", sandbox.pluginId)
        }
    }

    @Nested
    inner class SandboxRemovalTests {
        @Test
        fun `removeSandbox removes sandbox`() =
            runTest {
                manager.createSandbox("plugin-1")

                manager.removeSandbox("plugin-1")

                assertNull(manager.getSandbox("plugin-1"))
            }

        @Test
        fun `removeSandbox is safe for unknown plugin`() =
            runTest {
                // Should not throw
                manager.removeSandbox("unknown-plugin")
            }

        @Test
        fun `getAllSandboxes returns all created sandboxes`() {
            manager.createSandbox("plugin-1")
            manager.createSandbox("plugin-2")
            manager.createSandbox("plugin-3")

            val sandboxes = manager.getAllSandboxes()

            assertEquals(3, sandboxes.size)
            assertTrue(sandboxes.containsKey("plugin-1"))
            assertTrue(sandboxes.containsKey("plugin-2"))
            assertTrue(sandboxes.containsKey("plugin-3"))
        }
    }

    @Nested
    inner class RestartTests {
        @Test
        fun `restartPlugin returns failure for unknown plugin`() =
            runTest {
                val result = manager.restartPlugin("unknown-plugin")

                assertTrue(result.isFailure)
            }

        @Test
        fun `restartPlugin returns success for known plugin`() =
            runTest {
                val sandbox = manager.createSandbox("plugin-1")
                sandbox.start()

                val result = manager.restartPlugin("plugin-1")

                assertTrue(result.isSuccess)
            }
    }

    @Nested
    inner class DisableEnableTests {
        @Test
        fun `disablePlugin marks plugin as disabled`() =
            runTest {
                manager.createSandbox("plugin-1")

                manager.disablePlugin("plugin-1")

                assertTrue(manager.isPluginDisabled("plugin-1"))
            }

        @Test
        fun `enablePlugin removes plugin from disabled set`() =
            runTest {
                manager.createSandbox("plugin-1")
                manager.disablePlugin("plugin-1")

                manager.enablePlugin("plugin-1")

                assertFalse(manager.isPluginDisabled("plugin-1"))
            }

        @Test
        fun `isPluginDisabled returns false for non-disabled plugin`() {
            manager.createSandbox("plugin-1")

            assertFalse(manager.isPluginDisabled("plugin-1"))
        }

        @Test
        fun `getDisabledPlugins returns all disabled plugins`() =
            runTest {
                manager.createSandbox("plugin-1")
                manager.createSandbox("plugin-2")
                manager.createSandbox("plugin-3")

                manager.disablePlugin("plugin-1")
                manager.disablePlugin("plugin-3")

                val disabled = manager.getDisabledPlugins()

                assertEquals(2, disabled.size)
                assertTrue(disabled.contains("plugin-1"))
                assertTrue(disabled.contains("plugin-3"))
                assertFalse(disabled.contains("plugin-2"))
            }

        @Test
        fun `disablePlugin sets sandbox state to DISABLED`() =
            runTest {
                val sandbox = manager.createSandbox("plugin-1")
                sandbox.start()

                manager.disablePlugin("plugin-1")

                assertEquals(SandboxState.DISABLED, sandbox.state.value)
            }
    }

    @Nested
    inner class ListenerTests {
        @Test
        fun `listener receives onPluginRestarting event`() =
            runTest {
                var receivedPluginId: String? = null
                val listener =
                    object : PluginSandboxListener {
                        override fun onPluginRestarting(pluginId: String) {
                            receivedPluginId = pluginId
                        }
                    }
                manager.addListener(listener)

                val sandbox = manager.createSandbox("plugin-1")
                sandbox.start()
                manager.restartPlugin("plugin-1")

                assertEquals("plugin-1", receivedPluginId)
            }

        @Test
        fun `listener receives onPluginRestarted event`() =
            runTest {
                var receivedPluginId: String? = null
                val listener =
                    object : PluginSandboxListener {
                        override fun onPluginRestarted(pluginId: String) {
                            receivedPluginId = pluginId
                        }
                    }
                manager.addListener(listener)

                val sandbox = manager.createSandbox("plugin-1")
                sandbox.start()
                manager.restartPlugin("plugin-1")

                assertEquals("plugin-1", receivedPluginId)
            }

        @Test
        fun `listener receives onPluginDisabled event`() =
            runTest {
                var receivedPluginId: String? = null
                val listener =
                    object : PluginSandboxListener {
                        override fun onPluginDisabled(pluginId: String) {
                            receivedPluginId = pluginId
                        }
                    }
                manager.addListener(listener)

                manager.createSandbox("plugin-1")
                manager.disablePlugin("plugin-1")

                assertEquals("plugin-1", receivedPluginId)
            }

        @Test
        fun `a listener that throws does not stop the others or the caller`() =
            runTest {
                var secondListenerSaw: String? = null
                val thrower =
                    object : PluginSandboxListener {
                        override fun onPluginRestarted(pluginId: String) = error("listener blew up")
                    }
                val survivor =
                    object : PluginSandboxListener {
                        override fun onPluginRestarted(pluginId: String) {
                            secondListenerSaw = pluginId
                        }
                    }
                manager.addListener(thrower)
                manager.addListener(survivor)
                val sandbox = manager.createSandbox("plugin-1")
                sandbox.start()

                // On the automatic path this is dispatched from inside the
                // watchdog's own coroutine, so an escaping exception completes
                // that job - leaving the plugin with no health monitoring for
                // the rest of the session, and only a default-handler stack
                // trace to show for it.
                val result = manager.restartPlugin("plugin-1")

                assertTrue(result.isSuccess, "a throwing listener must not fail the restart")
                assertEquals("plugin-1", secondListenerSaw, "later listeners were skipped")
            }

        @Test
        fun `removeListener stops receiving events`() =
            runTest {
                var callCount = 0
                val listener =
                    object : PluginSandboxListener {
                        override fun onPluginDisabled(pluginId: String) {
                            callCount++
                        }
                    }
                manager.addListener(listener)
                manager.createSandbox("plugin-1")
                manager.disablePlugin("plugin-1")

                manager.removeListener(listener)
                manager.enablePlugin("plugin-1")
                manager.disablePlugin("plugin-1")

                assertEquals(1, callCount) // Only the first disable
            }
    }

    @Nested
    inner class HealthSummaryTests {
        @Test
        fun `healthSummary is available`() {
            val summary = manager.healthSummary.value

            assertNotNull(summary)
        }
    }

    @Nested
    inner class DisposeTests {
        @Test
        fun `dispose clears all sandboxes`() =
            runTest {
                manager.createSandbox("plugin-1")
                manager.createSandbox("plugin-2")

                manager.dispose()

                assertTrue(manager.getAllSandboxes().isEmpty())
            }
    }

    /**
     * The give-up path, which used to be unreachable: `restartAttempts` was
     * zeroed by every restart, so no plugin ever reached its budget. It is
     * reachable now, and it is the branch that produces the user-visible
     * "plugin disabled" outcome, so all of its effects matter.
     */
    @Nested
    inner class RestartBudgetTests {
        /**
         * Driven through a real watchdog rather than by calling
         * [PluginSandboxManagerImpl.handleRestartRequest] directly, because the
         * bug this pins only exists when that code runs *inside* the watchdog's
         * own coroutine - which is where the real path puts it (checkHealth ->
         * triggerRestart -> onRestartRequested). Called directly from a test
         * coroutine, stopping the watchdog cancels somebody else and the
         * teardown completes either way: the first version of this test passed
         * against the bug it was written for.
         *
         * The trigger is the consecutive-error threshold, not a stale
         * heartbeat, because the sandbox's own heartbeat job keeps beating and
         * a heartbeat timeout would need the test to outwait it.
         */
        @Test
        fun `exhausting the budget disables the plugin and does not strand its thread pool`() =
            runBlocking {
                val config =
                    SandboxConfig(
                        maxThreads = 2,
                        heartbeatIntervalMs = 50,
                        // Neither the heartbeat nor the stall guard should be
                        // what fires here.
                        unhealthyThresholdMs = 60_000,
                        stallGraceMs = 60_000,
                        maxConsecutiveErrors = 1,
                        // So the very first request is already over budget.
                        maxRestartAttempts = 0,
                    )
                val budgetManager = PluginSandboxManagerImpl(config)
                try {
                    var disabledNotification: String? = null
                    val listener =
                        object : PluginSandboxListener {
                            override fun onPluginDisabled(pluginId: String) {
                                disabledNotification = pluginId
                            }
                        }
                    budgetManager.addListener(listener)

                    // Passed explicitly: createSandbox defaults to a stock
                    // SandboxConfig, not the manager's defaultConfig.
                    val sandbox = budgetManager.createSandbox("plugin-1", config) as InProcessPluginSandbox
                    sandbox.start()
                    sandbox.recordError(RuntimeException("wedged"))

                    val disabled =
                        withTimeoutOrNull(10_000) {
                            while (sandbox.state.value != SandboxState.DISABLED) delay(20)
                            true
                        } ?: false

                    // All four have to happen. Landing on STOPPED without
                    // setDisabled() leaves PluginErrorBoundary rendering the
                    // plugin normally over a dead scope, with no notification
                    // and isPluginDisabled() false - invisible to the user.
                    assertTrue(disabled, "the plugin was never marked disabled")
                    assertTrue(budgetManager.isPluginDisabled("plugin-1"))
                    assertEquals("plugin-1", disabledNotification)

                    val terminated =
                        withTimeoutOrNull(5_000) {
                            while (!sandbox.isExecutorTerminated()) delay(20)
                            true
                        } ?: false
                    assertTrue(
                        terminated,
                        "the thread pool leaked: stopping the watchdog first cancels the very coroutine " +
                            "running this teardown, and sandbox.stop() suspends to retire the pool",
                    )
                } finally {
                    budgetManager.dispose()
                }
            }

        @Test
        fun `a plugin's own restart budget is used, not the manager default`() =
            runTest {
                // The manager default is 3 (see setUp). Nothing about
                // cancellation here, so the request can be made directly.
                val generous = SandboxConfig(maxRestartAttempts = 5)
                manager.createSandbox("plugin-generous", generous).start()

                repeat(4) { manager.restartPlugin("plugin-generous") }
                manager.handleRestartRequest("plugin-generous")

                assertFalse(
                    manager.isPluginDisabled("plugin-generous"),
                    "a plugin declaring its own budget had the manager default applied to it",
                )
            }
    }
}
