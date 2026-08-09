package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.MissingDependencyPrompt
import ai.rever.boss.components.plugin.PluginDependencyBus
import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the reporter puts on the bus, with a real bus and no plugin loader.
 *
 * The reporter exists as a class rather than a private method because three host paths install
 * on a user's behalf - the plugin-manager delegate, the first-run wizard and the update bridge -
 * and for a while only the first of them reported. These tests cover the decisions that were
 * previously reachable only by reading `PluginLoaderDelegateImpl`'s source.
 */
class MissingDependencyReporterTest {
    private fun manifest(
        pluginId: String = "com.example.dependent",
        dependencies: List<PluginDependency> = emptyList(),
    ) = PluginManifest(
        pluginId = pluginId,
        displayName = "Dependent",
        version = "1.0.0",
        apiVersion = "1.0.0",
        mainClass = "com.example.Main",
        dependencies = dependencies,
    )

    private fun dependency(
        pluginId: String,
        optional: Boolean = false,
    ) = PluginDependency(pluginId = pluginId, version = "1.0.0", optional = optional)

    private fun loadedInfo(pluginId: String) = stateInfo(pluginId, PluginState.LOADED)

    private fun disabledInfo(pluginId: String) = stateInfo(pluginId, PluginState.DISABLED)

    private fun stateInfo(
        pluginId: String,
        state: PluginState,
    ) = DynamicPluginInfo(
        manifest = manifest(pluginId),
        jarPath = "/plugins/$pluginId.jar",
        state = state,
        loadedAt = 0L,
        enabled = state == PluginState.LOADED,
    )

    /** Nothing is installed and nothing can be installed: only what gets reported matters. */
    private fun reporter(
        bus: PluginDependencyBus,
        states: Map<String, DynamicPluginInfo> = emptyMap(),
        jarExists: (String) -> Boolean = { true },
    ) = MissingDependencyReporter(
        states = { states },
        installer = NoopInstaller,
        bus = bus,
        jarExists = jarExists,
    )

    private object NoopInstaller : MissingDependencyInstaller {
        override fun isInstalled(pluginId: String) = false

        override suspend fun displayNameFor(pluginId: String): String? = null

        override suspend fun install(pluginId: String) = Result.success(Unit)
    }

    @Test
    fun `an absent dependency reaches the bus`() =
        runTest {
            val bus = PluginDependencyBus()

            reporter(bus).report(manifest(dependencies = listOf(dependency("com.example.gateway"))))

            val prompt: MissingDependencyPrompt = bus.missingDependencies.first()
            assertEquals("com.example.gateway", prompt.missing.missingPluginId)
            assertEquals("com.example.dependent", prompt.missing.dependentPluginId)
        }

    @Test
    fun `a system component is never put on the bus`() =
        runTest {
            val bus = PluginDependencyBus()

            // Reported alongside one that IS offerable, so the assertion is "the system id was
            // skipped" rather than "nothing happened".
            reporter(bus).report(
                manifest(
                    dependencies =
                        listOf(
                            dependency("ai.rever.boss.plugin.api"),
                            dependency("ai.rever.boss.microkernel.runtime"),
                            dependency("com.example.gateway"),
                        ),
                ),
            )

            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `a manifest with nothing missing reports nothing`() =
        runTest {
            val bus = PluginDependencyBus()

            reporter(bus).report(manifest())

            assertNull(withTimeoutOrNull(1_000) { bus.missingDependencies.first() })
        }

    @Test
    fun `an installed dependency is not reported`() =
        runTest {
            val bus = PluginDependencyBus()
            val states = mapOf("com.example.gateway" to loadedInfo("com.example.gateway"))

            reporter(bus, states).report(manifest(dependencies = listOf(dependency("com.example.gateway"))))

            assertNull(withTimeoutOrNull(1_000) { bus.missingDependencies.first() })
        }

    @Test
    fun `a running plugin whose jar has moved is not reported as missing`() =
        runTest {
            val bus = PluginDependencyBus()
            val states = mapOf("com.example.gateway" to loadedInfo("com.example.gateway"))

            // The manager does not repoint `jarPath` when a file moves, so a LOADED plugin can
            // hold a path that no longer exists. Reporting it would prompt for something that is
            // running, and Install would fail with "Plugin already loaded".
            reporter(bus, states, jarExists = { false })
                .report(manifest(dependencies = listOf(dependency("com.example.gateway"))))

            assertNull(withTimeoutOrNull(1_000) { bus.missingDependencies.first() })
        }

    @Test
    fun `a rejected plugin whose jar was deleted is still reported as missing`() =
        runTest {
            val bus = PluginDependencyBus()
            // What a binary-incompatible load leaves behind, after the installer deleted the jar.
            val states = mapOf("com.example.gateway" to disabledInfo("com.example.gateway"))

            reporter(bus, states, jarExists = { false })
                .report(manifest(dependencies = listOf(dependency("com.example.gateway"))))

            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `a blank dependency id is not reported`() =
        runTest {
            val bus = PluginDependencyBus()

            reporter(bus).report(
                manifest(dependencies = listOf(dependency(""), dependency("com.example.gateway"))),
            )

            // Otherwise the prompt reads "Dependent needs , which is not installed."
            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `reporting never throws, whatever the manifest holds`() {
        val bus = PluginDependencyBus()

        // The install that triggered this must not fail because the dependency check did.
        reporter(bus).report(manifest(dependencies = List(64) { dependency("com.example.d$it") }))

        assertTrue(true)
    }
}
