package ai.rever.boss.components.plugin

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the dependency dialog offers to install, without a store or a network.
 *
 * `StoreMissingDependencyInstaller` loads a dependency directly rather than through the
 * install path precisely so one answer never raises a second dialog. The cost was silence:
 * A needs B, B needs C, and installing B from the dialog never mentioned C. The plan closes
 * that by resolving the whole closure *before* the user answers, so the one dialog can say
 * what it is about to install and then install it in an order that works.
 *
 * The store is injected as `dependenciesOf`. Tests never touch a repository; a null answer is
 * how the store says "I cannot describe this plugin", and the plan must stay useful then.
 */
class DependencyInstallPlanTest {
    /** A store where every plugin's dependencies are known up front. */
    private fun store(vararg edges: Pair<String, List<String>>): suspend (String) -> List<String>? {
        val graph = edges.toMap()
        return { id -> graph[id] ?: emptyList() }
    }

    private val nothingPresent: (String) -> Boolean = { false }

    @Test
    fun `a dependency with no dependencies of its own is a plan of one`() =
        runTest {
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = store(),
                )

            assertEquals(listOf("b"), plan.order)
            assertTrue(plan.unresolved.isEmpty())
            assertFalse(plan.cyclic)
            assertFalse(plan.truncated)
        }

    @Test
    fun `a chain installs the deepest dependency first and the root last`() =
        runTest {
            // The order is the contract the installer relies on: a plugin resolves its
            // dependency's API lazily, but "lazily" still means "before the first call", and
            // the first call can come from register(). Deps-first keeps that true.
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = store("b" to listOf("c"), "c" to listOf("d")),
                )

            assertEquals(listOf("d", "c", "b"), plan.order)
        }

    @Test
    fun `a diamond installs the shared dependency once, before both that need it`() =
        runTest {
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf =
                        store(
                            "b" to listOf("c", "e"),
                            "c" to listOf("d"),
                            "e" to listOf("d"),
                        ),
                )

            assertEquals(1, plan.order.count { it == "d" }, "shared dependency planned twice")
            assertTrue(plan.order.indexOf("d") < plan.order.indexOf("c"))
            assertTrue(plan.order.indexOf("d") < plan.order.indexOf("e"))
            assertEquals("b", plan.order.last())
        }

    @Test
    fun `a cycle terminates, installs each member once, and is flagged`() =
        runTest {
            // Store rows are free-form JSONB written by plugin authors; a cycle is a data
            // mistake, not something the walk may loop on. Flagged so the caller can log it,
            // not so it can refuse: the members still need installing.
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = store("b" to listOf("c"), "c" to listOf("b")),
                )

            assertEquals(setOf("b", "c"), plan.order.toSet())
            assertEquals(2, plan.order.size)
            assertTrue(plan.cyclic)
        }

    @Test
    fun `a plugin the store cannot describe stays in the plan but is not expanded`() =
        runTest {
            // Unknown metadata does not imply an optional dependency. Keep it in the plan so
            // installation retries the lookup; if that fails, stop before its dependents.
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = { id -> if (id == "c") null else listOf("c") },
                )

            assertEquals(listOf("c", "b"), plan.order)
            assertEquals(setOf("c"), plan.unresolved)
        }

    @Test
    fun `the store failing on the root still yields a plan of the root`() =
        runTest {
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = { null },
                )

            assertEquals(listOf("b"), plan.order)
            assertEquals(setOf("b"), plan.unresolved)
        }

    @Test
    fun `a dependency already present is not planned and not expanded`() =
        runTest {
            // Present means "installed and usable now", the same predicate the reporter and
            // the installer share. If it is present its own dependencies were someone else's
            // problem at its install time, not this dialog's.
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = { it == "c" },
                    dependenciesOf = store("b" to listOf("c"), "c" to listOf("d")),
                )

            assertEquals(listOf("b"), plan.order)
        }

    @Test
    fun `the root is planned even when reported present`() =
        runTest {
            // The dialog's Install button already guards on isInstalled before calling; the
            // plan is not a second guard. A root that turns out present between the two reads
            // is the installer's coalescing problem, and dropping it here would return an
            // empty plan that the installer has no sensible reading of.
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = { true },
                    dependenciesOf = store("b" to listOf("c")),
                )

            assertEquals(listOf("b"), plan.order)
        }

    @Test
    fun `system components are never planned, at any depth`() =
        runTest {
            // Same rule as missingFor, for the same reason: installing the api plugin is a
            // hot swap and the microkernel runtime is never in pluginStates. A transitive
            // declaration must not smuggle either past a two-button dialog.
            PluginDependencyResolution.NOT_USER_INSTALLABLE.forEach { systemId ->
                val plan =
                    PluginDependencyResolution.installPlan(
                        rootId = "b",
                        isPresent = nothingPresent,
                        dependenciesOf = store("b" to listOf("c"), "c" to listOf(systemId)),
                    )

                assertFalse(systemId in plan.order, "planned $systemId")
            }
        }

    @Test
    fun `blank ids and self-references are dropped rather than planned`() =
        runTest {
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "b",
                    isPresent = nothingPresent,
                    dependenciesOf = store("b" to listOf("", " ", "b", "c"), "c" to listOf("c")),
                )

            assertEquals(listOf("c", "b"), plan.order)
            assertFalse(plan.cyclic, "a self-reference is a typo, not a cycle")
        }

    @Test
    fun `expansion stops at the size cap and says so`() =
        runTest {
            // A store row can name anything, so the walk has to be bounded by something other
            // than the store's honesty. Real graphs are two or three deep; the cap exists so a
            // malformed one cannot turn the dialog into a hang.
            val cap = PluginDependencyResolution.MAX_PLAN_SIZE
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "p0",
                    isPresent = nothingPresent,
                    dependenciesOf = { id -> listOf("p" + (id.removePrefix("p").toInt() + 1)) },
                )

            assertTrue(plan.truncated)
            assertTrue(plan.order.size <= cap, "planned ${plan.order.size}, cap is $cap")
            assertEquals("p0", plan.order.last(), "the root is what the user asked for")
        }

    @Test
    fun `the store is asked about each plugin exactly once`() =
        runTest {
            // Every question is a network round trip the dialog is waiting on.
            val asked = mutableListOf<String>()
            val graph = mapOf("b" to listOf("c", "e"), "c" to listOf("d"), "e" to listOf("d"))

            PluginDependencyResolution.installPlan(
                rootId = "b",
                isPresent = nothingPresent,
                dependenciesOf = { id ->
                    asked += id
                    graph[id] ?: emptyList()
                },
            )

            assertEquals(asked.size, asked.toSet().size, "asked twice: $asked")
        }

    @Test
    fun `a full plan still detects a cycle back to an already visited root`() =
        runTest {
            val cap = PluginDependencyResolution.MAX_PLAN_SIZE
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "p0",
                    isPresent = nothingPresent,
                    dependenciesOf = { id ->
                        val next = (id.removePrefix("p").toInt() + 1) % cap
                        listOf("p$next")
                    },
                )
            assertEquals(cap, plan.order.size)
            assertTrue(plan.cyclic)
            assertFalse(plan.truncated)
        }

    @Test
    fun `a full diamond does not report truncation for repeated edges`() =
        runTest {
            val cap = PluginDependencyResolution.MAX_PLAN_SIZE
            val plan =
                PluginDependencyResolution.installPlan(
                    rootId = "root",
                    isPresent = nothingPresent,
                    dependenciesOf = { id ->
                        if (id == "root") (1 until cap).map { "p$it" } + "p1" else emptyList()
                    },
                )
            assertEquals(cap, plan.order.size)
            assertFalse(plan.truncated)
            assertFalse(plan.cyclic)
        }
}
