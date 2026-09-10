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
        state: PluginState = PluginState.DISABLED,
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
        state = state,
        loadedAt = 0L,
        enabled = state == PluginState.LOADED,
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
    fun `a padded manifest id cannot offer itself as a dependency`() {
        for (child in listOf(" com.example.self ", "com.example.self")) {
            val missing =
                PluginDependencyResolution.missingFor(
                    manifest(pluginId = " com.example.self ", dependencies = listOf(dependency(child))),
                    installedPluginIds = emptySet(),
                )
            assertTrue(missing.isEmpty(), "offered self-reference $child")
        }
    }

    @Test
    fun `direct prompts reject blank and padded protected ids`() {
        val ids = listOf("", " ", "\t") + PluginDependencyResolution.NOT_USER_INSTALLABLE.map { " $it " }
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = ids.map { dependency(it) }),
                installedPluginIds = emptySet(),
            )
        assertTrue(missing.isEmpty())
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
            PluginDependencyResolution.installedAndOnDisk(
                states = states,
                exists = { jarPath -> jarPath == "/plugins/present.jar" },
            )

        // The bug this replaces a source-level regex with a real assertion: counting the
        // dangling entry as installed made every LATER dependent of that plugin report nothing,
        // with no prompt and no log line.
        assertEquals(setOf("com.example.present"), installed)
    }

    @Test
    fun `a loaded plugin counts as installed even when its jar is gone`() {
        // The LOADED clause exists for this: `PluginJarReconciler` and the updater rewrite paths
        // without repointing the manager's in-memory `jarPath`, so a running plugin can hold a
        // path that no longer exists. Every other test in this file uses a DISABLED entry, so
        // without this the clause was never exercised at all.
        val states =
            mapOf(
                "com.example.gateway" to
                    info("com.example.gateway", "/plugins/moved.jar", PluginState.LOADED),
            )

        val installed = PluginDependencyResolution.installedAndOnDisk(states, exists = { false })

        assertEquals(setOf("com.example.gateway"), installed)
    }

    @Test
    fun `a loaded plugin still flagged incompatible counts as installed`() {
        // `PluginCrashRegistry` keeps the flag until the re-enable path clears it, and neither
        // install nor update does. So a plugin that failed registration, was updated from the
        // Toolbox's own incompatibility prompt and is now running still carries it - and
        // excluding it would report a *running* plugin as missing, then claim the download "did
        // not start".
        val states =
            mapOf(
                "com.example.gateway" to
                    info("com.example.gateway", "/plugins/gateway.jar", PluginState.LOADED),
            )

        val installed =
            PluginDependencyResolution.installedAndOnDisk(
                states = states,
                exists = { true },
                isIncompatible = { true },
            )

        assertEquals(setOf("com.example.gateway"), installed)
    }

    @Test
    fun `a plugin that failed to register does not count as installed`() {
        // There are two binary-incompatibility paths in `installPlugin` and only one fails: the
        // registration-time one force-unloads the plugin and returns SUCCESS with state DISABLED
        // and the jar still on disk. Counting that jar would have made the Install button report
        // success for a plugin that was unloaded, and silenced every other dependent of it.
        val states = mapOf("com.example.gateway" to info("com.example.gateway", "/plugins/gateway.jar"))

        val installed =
            PluginDependencyResolution.installedAndOnDisk(
                states = states,
                exists = { true },
                isIncompatible = { it == "com.example.gateway" },
            )

        assertEquals(emptySet(), installed)
    }

    @Test
    fun `an incompatible plugin is still reported as missing to its dependents`() {
        val states = mapOf("com.example.gateway" to info("com.example.gateway", "/plugins/gateway.jar"))

        val installed =
            PluginDependencyResolution.installedAndOnDisk(states, exists = { true }, isIncompatible = { true })
        val missing =
            PluginDependencyResolution.missingFor(
                manifest(dependencies = listOf(dependency("com.example.gateway"))),
                installedPluginIds = installed,
            )

        assertEquals(listOf("com.example.gateway"), missing.map { it.missingPluginId })
    }

    @Test
    fun `a dangling entry means its dependents are still reported as missing`() {
        val states = mapOf("com.example.gateway" to info("com.example.gateway", "/plugins/gone.jar"))

        val installed = PluginDependencyResolution.installedAndOnDisk(states, exists = { false })
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

    // What `DynamicPluginManager.checkCanUnload` refuses on. The install side above and the
    // unload side here read the same declarations, and they disagreed: an optional dependency
    // was reported as "works without it" at install time and then treated as a hard veto at
    // unload time.

    @Test
    fun `an optional dependent does not block an unload`() {
        // The AI Gateway case. jupyter-notebook, flow-tab and llmrpa each declare the gateway
        // optional, so treating that as a veto made the gateway impossible to unload - and the
        // Toolbox's update path is uninstall-then-reinstall, so its Update button did nothing.
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.jupyter",
                            displayName = "Jupyter Notebook",
                            dependencies = listOf(dependency("com.example.gateway", optional = true)),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(emptyList(), blocking)
    }

    @Test
    fun `a required dependent blocks an unload and is named`() {
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.jupyter",
                            displayName = "Jupyter Notebook",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { false },
            )

        // The display name, because it goes straight into a reason the user reads.
        assertEquals(listOf("Jupyter Notebook"), blocking)
    }

    @Test
    fun `a plugin declaring itself does not block its own unload`() {
        // Otherwise a manifest typo is permanent: the plugin could never be unloaded, updated
        // or removed, and the reason would name the plugin being unloaded.
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.gateway",
                            displayName = "AI Gateway",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(emptyList(), blocking)
    }

    @Test
    fun `a plugin depending on something else does not block`() {
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.other",
                            displayName = "Other",
                            dependencies = listOf(dependency("com.example.unrelated")),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(emptyList(), blocking)
    }

    @Test
    fun `one required dependent among optional ones still blocks`() {
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.jupyter",
                            displayName = "Jupyter Notebook",
                            dependencies = listOf(dependency("com.example.gateway", optional = true)),
                        ),
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                        manifest(
                            pluginId = "com.example.llmrpa",
                            displayName = "LLM RPA",
                            dependencies = listOf(dependency("com.example.gateway", optional = true)),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(listOf("Flow"), blocking)
    }

    @Test
    fun `a disabled dependent does not block an unload`() {
        // disablePlugin unregisters the tracking context and flips state to DISABLED but never
        // calls pluginLoader.unloadPlugin, so a disabled plugin is still in getLoadedPlugins().
        // Letting it veto raises a refusal on behalf of something that is not running.
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.jupyter",
                            displayName = "Jupyter Notebook",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { it == "com.example.jupyter" },
            )

        assertEquals(emptyList(), blocking)
    }

    @Test
    fun `an enabled dependent still blocks when a disabled one does not`() {
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.jupyter",
                            displayName = "Jupyter Notebook",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { it == "com.example.jupyter" },
            )

        assertEquals(listOf("Flow"), blocking)
    }

    @Test
    fun `the disabled check fails closed`() {
        // The host answers false for a state it does not recognise or does not track, so an
        // untracked-but-loaded dependent still vetoes. Over-vetoing an unfamiliar state is
        // recoverable; dropping a real dependent silently is what this whole predicate is
        // about not doing.
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.untracked",
                            displayName = "Untracked",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(listOf("Untracked"), blocking)
    }

    @Test
    fun `a dependency declared both ways blocks, as the stricter declaration`() {
        // Mirrors `missingFor`, which resolves a doubled declaration to the stricter one: a
        // plugin that requires something does not stop requiring it by also listing it
        // optionally.
        val blocking =
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies =
                                listOf(
                                    dependency("com.example.gateway", optional = true),
                                    dependency("com.example.gateway"),
                                ),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(listOf("Flow"), blocking)
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

    private fun missing(
        dependentPluginId: String,
        missingPluginId: String,
        optional: Boolean,
    ) = MissingPluginDependency(dependentPluginId, "Dependent", missingPluginId, optional)

    private fun promptFor(missing: MissingPluginDependency) = MissingDependencyPrompt(missing, noopInstaller)

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
    fun `declining an optional dependency silences it for every dependent`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.decline(missing("com.example.first", "com.example.gateway", optional = true))

            // All three gateway consumers declare it optional, so "Not now" is one answer about
            // the gateway - not something to be asked again for the next plugin that needs it.
            bus.report(promptFor(missing("com.example.second", "com.example.gateway", optional = true)))
            bus.report(promptFor(missing("com.example.second", "com.example.other", optional = true)))

            assertEquals(
                "com.example.other",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `skipping a required dependency does not silence another plugin that requires it`() =
        runTest {
            val bus = PluginDependencyBus()
            bus.decline(missing("com.example.first", "com.example.gateway", optional = false))

            // "Skip" answers for this dependent only: another plugin that hard-requires the same
            // thing is a different question, and silencing that would be worse than the optional
            // case rather than better.
            val other = missing("com.example.second", "com.example.gateway", optional = false)
            assertFalse(bus.wasDeclined(other))
            bus.report(promptFor(other))

            assertEquals(
                "com.example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `declining one plugin does not silence another`() =
        runTest {
            val bus = PluginDependencyBus()
            val gateway = missing("com.example.first", "com.example.gateway", optional = true)
            bus.decline(gateway)

            assertTrue(bus.wasDeclined(gateway))
            assertFalse(bus.wasDeclined(missing("com.example.first", "com.example.other", optional = true)))
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
    fun `a required prompt is not swallowed by a pending optional one`() =
        runTest {
            val bus = PluginDependencyBus()

            // jupyter declares the gateway optional; a plugin that hard-requires it installs next.
            bus.report(promptFor(missing("com.example.jupyter", "com.example.gateway", optional = true)))
            bus.report(promptFor(missing("com.example.strict", "com.example.gateway", optional = false)))

            // Both must survive: keyed by bare id, the required one was dropped, the user saw only
            // "Recommended / Not now", and declining that silenced the plugin that required it.
            val delivered =
                (1..2).map {
                    bus.missingDependencies
                        .first()
                        .missing.optional
                }
            assertEquals(listOf(true, false), delivered)
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

/**
 * What the dependent-restart prompt lists, as against what the unload veto counts.
 *
 * The two read the same set through `dependentsOf`, and these pin where they diverge: the veto
 * counts only hard declarations, the prompt counts every loaded dependent. Getting that backwards
 * either refuses an update nobody asked to have refused, or silently leaves a plugin running
 * against a classloader that has closed.
 */
class PluginDependentsTest {
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

    private fun dependency(
        pluginId: String,
        optional: Boolean = false,
    ) = PluginDependency(pluginId = pluginId, version = "1.0.0", optional = optional)

    @Test
    fun `an optional dependent is listed for restart even though it does not block`() {
        // The whole reason the restart prompt exists. The gateway's consumers all declare it
        // optional, so the veto lets the update through - and used to leave them running
        // against a classloader that had closed underneath them.
        val loaded =
            listOf(
                manifest(
                    pluginId = "com.example.jupyter",
                    displayName = "Jupyter Notebook",
                    dependencies = listOf(dependency("com.example.gateway", optional = true)),
                ),
            )

        val dependents =
            PluginDependencyResolution.dependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests = loaded,
                isDisabled = { false },
            )

        assertEquals(listOf("com.example.jupyter"), dependents.map { it.pluginId })
        assertTrue(dependents.single().optional)
        assertEquals(
            emptyList(),
            PluginDependencyResolution.blockingDependentsOf("com.example.gateway", loaded) { false },
        )
    }

    @Test
    fun `dependentsOf lists required and optional together`() {
        val dependents =
            PluginDependencyResolution.dependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies = listOf(dependency("com.example.gateway", optional = true)),
                        ),
                        manifest(
                            pluginId = "com.example.llmrpa",
                            displayName = "LLM RPA",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                        manifest(
                            pluginId = "com.example.unrelated",
                            displayName = "Unrelated",
                            dependencies = listOf(dependency("com.example.other")),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(listOf("com.example.flow", "com.example.llmrpa"), dependents.map { it.pluginId })
        assertEquals(listOf(true, false), dependents.map { it.optional })
    }

    @Test
    fun `a manifest naming itself is not its own dependent`() {
        // Same rule as the veto, and for the same reason: it would make the plugin permanently
        // unremovable, and here it would also offer to restart the plugin being unloaded.
        val dependents =
            PluginDependencyResolution.dependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.gateway",
                            displayName = "AI Gateway",
                            dependencies = listOf(dependency("com.example.gateway")),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(emptyList(), dependents)
    }

    @Test
    fun `a disabled dependent is not listed for restart`() {
        val dependents =
            PluginDependencyResolution.dependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies = listOf(dependency("com.example.gateway", optional = true)),
                        ),
                    ),
                // disablePlugin leaves the plugin in getLoadedPlugins(), so without this it
                // would be restarted on behalf of something that is not running.
                isDisabled = { id -> id == "com.example.flow" },
            )

        assertEquals(emptyList(), dependents)
    }

    @Test
    fun `a dependency declared twice is listed by its stricter declaration`() {
        // Matches missingFor and the veto: calling something optional that the plugin actually
        // requires would also drop it from blockingDependentsOf, which reads off this list.
        val dependents =
            PluginDependencyResolution.dependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies =
                                listOf(
                                    dependency("com.example.gateway", optional = true),
                                    dependency("com.example.gateway", optional = false),
                                ),
                        ),
                    ),
                isDisabled = { false },
            )

        assertEquals(1, dependents.size)
        assertFalse(dependents.single().optional)
        assertEquals(
            listOf("Flow"),
            PluginDependencyResolution.blockingDependentsOf(
                pluginId = "com.example.gateway",
                loadedManifests =
                    listOf(
                        manifest(
                            pluginId = "com.example.flow",
                            displayName = "Flow",
                            dependencies =
                                listOf(
                                    dependency("com.example.gateway", optional = true),
                                    dependency("com.example.gateway", optional = false),
                                ),
                        ),
                    ),
                isDisabled = { false },
            ),
        )
    }
}
