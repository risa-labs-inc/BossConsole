package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.loader.ApiClassLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the install-time dependency prompt decides, without a plugin loader.
 *
 * Until this feature, `PluginManifest.dependencies` was read in exactly one place
 * (`DynamicPluginManager.checkCanUnload`), so installing a plugin whose dependency was absent
 * produced no signal at all - the user met the consequence later, as a feature that did
 * nothing. These pin the rules of the resolver that closes that.
 */
class PluginDependencyResolutionTest {
    private fun manifest(
        pluginId: String = "com.example.dependent",
        displayName: String = "Dependent",
        dependencies: List<PluginDependency> = emptyList(),
    ) = PluginManifest(
        pluginId = pluginId,
        displayName = displayName,
        version = "1.0.0",
        apiVersion = "1.0.0",
        mainClass = "com.example.Main",
        dependencies = dependencies,
    )

    private fun info(
        pluginId: String,
        jarPath: String,
    ) = DynamicPluginInfo(
        manifest =
            PluginManifest(
                pluginId = pluginId,
                displayName = pluginId,
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.Main",
            ),
        jarPath = jarPath,
        state = PluginState.DISABLED,
        loadedAt = 0L,
        enabled = false,
    )

    private fun dependency(
        pluginId: String,
        optional: Boolean = false,
    ) = PluginDependency(pluginId = pluginId, version = "1.0.0", optional = optional)

    @Test
    fun `an installed dependency is not reported`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = listOf(dependency("com.example.gateway"))),
                installedPluginIds = setOf("com.example.gateway"),
            )

        assertTrue(missing.isEmpty(), "expected nothing missing, got $missing")
    }

    @Test
    fun `an absent dependency is reported with the dependent's display name`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(displayName = "Flow", dependencies = listOf(dependency("com.example.gateway"))),
                installedPluginIds = emptySet(),
            )

        assertEquals(1, missing.size)
        assertEquals("com.example.gateway", missing.single().missingPluginId)
        // The dialog says "Flow needs ...", so the name has to survive resolution.
        assertEquals("Flow", missing.single().dependentDisplayName)
    }

    /**
     * The gateway is declared `optional: true` by all three of its consumers, so dropping
     * optional dependencies here would leave this feature reporting nothing at all for the
     * case it was built for.
     */
    @Test
    fun `an optional dependency is reported and flagged, not dropped`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = listOf(dependency("com.example.gateway", optional = true))),
                installedPluginIds = emptySet(),
            )

        assertEquals(1, missing.size)
        assertTrue(missing.single().optional)
    }

    @Test
    fun `a self-dependency is not offered for install`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(
                    pluginId = "com.example.dependent",
                    dependencies = listOf(dependency("com.example.dependent")),
                ),
                // Deliberately empty: mid-install the plugin is not in the installed set yet,
                // so without the filter it would offer to install what was just installed.
                installedPluginIds = emptySet(),
            )

        assertTrue(missing.isEmpty(), "expected no self-dependency, got $missing")
    }

    @Test
    fun `a dependency declared twice prompts once, as the stricter declaration`() {
        // Both orders, because asserting only the count passes whichever declaration wins -
        // and calling something "Recommended" that the plugin requires is the worse mistake.
        listOf(
            listOf(dependency("com.example.gateway"), dependency("com.example.gateway", optional = true)),
            listOf(dependency("com.example.gateway", optional = true), dependency("com.example.gateway")),
        ).forEach { declarations ->
            val missing =
                PluginDependencyResolution.missingFor(
                    manifest(dependencies = declarations),
                    installedPluginIds = emptySet(),
                )

            assertEquals(1, missing.size, "declared twice, prompted ${missing.size} times")
            assertFalse(missing.single().optional, "the optional declaration won")
        }
    }

    @Test
    fun `system components are never offered for install`() {
        PluginDependencyResolution.NOT_USER_INSTALLABLE.forEach { systemId ->
            val missing =
                PluginDependencyResolution.missingFor(
                    manifest(dependencies = listOf(dependency(systemId))),
                    installedPluginIds = emptySet(),
                )

            // The microkernel runtime is never in `pluginStates` (DefaultPlugin skips it on
            // scan), so without this filter it looks missing to every manifest naming it - and
            // installing it trips the binary-compat validator on core JDK classes. The api
            // plugin's install is an unload-all/swap/reload-all hot swap.
            assertTrue(missing.isEmpty(), "offered to install $systemId")
        }
    }

    @Test
    fun `the guarded system ids are the real ones`() {
        // Literals in the resolver because ApiClassLoader.API_PLUGIN_ID is desktop-only; if
        // either id is renamed, this fails rather than the filter quietly ceasing to match.
        assertEquals(
            setOf("ai.rever.boss.microkernel.runtime", "ai.rever.boss.plugin.api"),
            PluginDependencyResolution.NOT_USER_INSTALLABLE,
        )
        assertEquals(MicrokernelRuntime.PLUGIN_ID, "ai.rever.boss.microkernel.runtime")
        assertEquals(ApiClassLoader.API_PLUGIN_ID, "ai.rever.boss.plugin.api")
    }

    @Test
    fun `a version constraint does not make an installed plugin missing`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = listOf(dependency("com.example.gateway"))),
                installedPluginIds = setOf("com.example.gateway"),
            )

        // Presence is by id: documented scope, pinned so a future version check is a
        // deliberate change rather than a surprise.
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `only the absent dependencies of several are reported`() {
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(
                    dependencies =
                        listOf(
                            dependency("com.example.here"),
                            dependency("com.example.gone"),
                        ),
                ),
                installedPluginIds = setOf("com.example.here"),
            )

        assertEquals(listOf("com.example.gone"), missing.map { it.missingPluginId })
    }

    @Test
    fun `an entry whose jar is gone does not count as installed`() {
        val states =
            mapOf(
                "com.example.present" to info("com.example.present", "/plugins/present.jar"),
                // What `installPlugin` leaves behind for a binary-incompatible plugin, after the
                // installer has deleted the jar it rejected.
                "com.example.dangling" to info("com.example.dangling", "/plugins/gone.jar"),
            )

        val installed =
            PluginDependencyResolution.installedAndOnDisk(states) { jarPath ->
                jarPath == "/plugins/present.jar"
            }

        // The bug this replaces a source-level regex with a real assertion: counting the
        // dangling entry as installed made every LATER dependent of that plugin report nothing,
        // with no prompt and no log line.
        assertEquals(setOf("com.example.present"), installed)
    }

    @Test
    fun `a dangling entry means its dependents are still reported as missing`() {
        val states = mapOf("com.example.gateway" to info("com.example.gateway", "/plugins/gone.jar"))

        val installed = PluginDependencyResolution.installedAndOnDisk(states) { false }
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = listOf(dependency("com.example.gateway"))),
                installedPluginIds = installed,
            )

        assertEquals(listOf("com.example.gateway"), missing.map { it.missingPluginId })
    }

    @Test
    fun `a required dependency reads as needed and an optional one as a feature`() {
        val required =
            MissingPluginDependency("com.example.d", "Flow", "com.example.gateway", optional = false)
        val optional = required.copy(optional = true)

        assertEquals("Flow needs AI Gateway, which is not installed.", required.description("AI Gateway"))
        assertTrue(optional.description("AI Gateway").startsWith("Flow works without AI Gateway"))
    }

    @Test
    fun `the description falls back to whatever name it is given`() {
        val required =
            MissingPluginDependency("com.example.d", "Flow", "com.example.gateway", optional = false)

        // The dialog passes the plugin id when the store lookup fails, so the sentence still
        // has to name something rather than reading "Flow needs null".
        assertTrue(required.description("com.example.gateway").contains("com.example.gateway"))
    }
}

/**
 * The delivery guarantee the prompt depends on.
 *
 * A broadcast would put the same dialog in front of every open window and let each of them
 * start the same install, so "exactly one collector receives it" is the property, not an
 * implementation detail. Each test builds its own bus - a shared one carries leftover prompts
 * between tests.
 */
class PluginDependencyBusTest {
    private val noopInstaller =
        object : MissingDependencyInstaller {
            override fun isInstalled(pluginId: String): Boolean = false

            override suspend fun displayNameFor(pluginId: String): String? = null

            override suspend fun install(pluginId: String): Result<Unit> = Result.success(Unit)
        }

    private fun prompt(missingPluginId: String) =
        MissingDependencyPrompt(
            MissingPluginDependency("com.example.d", "Dependent", missingPluginId, optional = false),
            noopInstaller,
        )

    @Test
    fun `reporting with nobody collecting neither suspends nor throws`() {
        // The installer calls this from a plugin-install path: it must never block on a UI
        // that may not exist yet, and must not fail the install the user asked for.
        PluginDependencyBus().report(prompt("com.example.dropped"))
    }

    @Test
    fun `one prompt reaches exactly one of two collectors`() =
        runTest {
            val bus = PluginDependencyBus()
            val received = mutableListOf<String>()
            val collectors =
                List(2) {
                    launch {
                        received +=
                            bus.missingDependencies
                                .first()
                                .missing.missingPluginId
                    }
                }
            runCurrent()

            bus.report(prompt("com.example.once"))
            advanceUntilIdle()
            collectors.forEach { it.cancel() }

            assertEquals(listOf("com.example.once"), received)
        }

    @Test
    fun `a full buffer refuses the newest and keeps the ones already waiting`() =
        runTest {
            val bus = PluginDependencyBus()
            // Capacity is 4. The fifth has nowhere to go.
            for (n in 1..5) bus.report(prompt("com.example.p$n"))

            val delivered =
                (1..4).map {
                    bus.missingDependencies
                        .first()
                        .missing.missingPluginId
                }

            // Not DROP_OLDEST: the oldest prompt is the one a user is most likely part-way
            // through answering, and a channel that always accepts makes the drop invisible.
            assertEquals(listOf("com.example.p1", "com.example.p2", "com.example.p3", "com.example.p4"), delivered)
        }

    @Test
    fun `a declined plugin stops being reported for the rest of the session`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.decline("com.example.gateway")

            bus.report(prompt("com.example.gateway"))
            bus.report(prompt("com.example.other"))

            // All three gateway consumers declare it optional, so without this, declining once
            // means being asked again for the next plugin that needs it.
            assertEquals(
                "com.example.other",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `declining one plugin does not silence another`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.decline("com.example.gateway")

            assertTrue(bus.wasDeclined("com.example.gateway"))
            assertFalse(bus.wasDeclined("com.example.other"))
        }

    @Test
    fun `two dependents of one missing plugin occupy a single slot`() =
        runTest {
            val bus = PluginDependencyBus()

            bus.report(prompt("com.example.gateway"))
            bus.report(prompt("com.example.gateway"))
            bus.report(prompt("com.example.other"))

            // The collector would discard the duplicate on arrival, but it costs a slot first -
            // and with four slots that can be what refuses a different, still-relevant prompt.
            assertEquals(
                listOf("com.example.gateway", "com.example.other"),
                (1..2).map {
                    bus.missingDependencies
                        .first()
                        .missing.missingPluginId
                },
            )
        }

    @Test
    fun `a plugin can be reported again once its prompt has been taken`() =
        runTest {
            val bus = PluginDependencyBus()

            bus.report(prompt("com.example.gateway"))
            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )

            // Consuming frees the slot: a second dependent installed later must still be able to
            // raise it, otherwise the dedup would become a permanent mute.
            bus.report(prompt("com.example.gateway"))
            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `a prompt reported before anyone collects is delivered when a collector appears`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.report(prompt("com.example.early"))

            // The install that raised it can finish long before a window exists.
            assertEquals(
                "com.example.early",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }
}
