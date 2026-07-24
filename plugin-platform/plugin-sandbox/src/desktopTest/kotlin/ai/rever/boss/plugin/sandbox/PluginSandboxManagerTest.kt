package ai.rever.boss.plugin.sandbox

import kotlinx.coroutines.test.runTest
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
}
