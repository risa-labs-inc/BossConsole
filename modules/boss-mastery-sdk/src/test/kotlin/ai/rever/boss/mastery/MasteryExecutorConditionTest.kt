package ai.rever.boss.mastery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the #1060 failure mode: [MasteryEdge.condition] must actually be
 * evaluated before an edge is followed. Before the fix every conditional node
 * executed unconditionally because the executor used edges only as a
 * topological dependency list and never read the condition field.
 */
class MasteryExecutorConditionTest {
    /** Mock that returns predefined responses keyed by "pluginId/action" and records calls. */
    private class RecordingResolver(
        private val responses: Map<String, Map<String, String>>,
    ) : CapabilityResolver {
        val invocations = mutableListOf<Triple<String, String, Map<String, String>>>()

        override suspend fun invoke(
            pluginId: String,
            action: String,
            input: Map<String, String>,
        ): Map<String, String> {
            invocations.add(Triple(pluginId, action, input))
            return responses["$pluginId/$action"] ?: emptyMap()
        }

        override fun getAvailableCapabilities(): List<CapabilityInfo> = emptyList()
    }

    /** scan -> {condition} -> delete: the destructive-step guard from the issue. */
    private fun guardedMastery(condition: String?) =
        MasteryDefinition(
            id = "guarded",
            name = "Guarded",
            description = "",
            nodes =
                listOf(
                    MasteryNode("scan", "plugin-a", "scan"),
                    MasteryNode(
                        id = "delete",
                        pluginId = "plugin-b",
                        action = "delete",
                        inputMapping = mapOf("target" to "scan.scan_clean"),
                    ),
                ),
            edges =
                listOf(
                    MasteryEdge("scan", "delete", "scan_clean", "target", condition),
                ),
        )

    @Test
    fun `false condition blocks the edge and the guarded node never executes`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true"), emptyMap())
                    .toList()

            assertEquals(
                listOf("plugin-a/scan"),
                resolver.invocations.map { "${it.first}/${it.second}" },
            )
            assertEquals(
                listOf("scan"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertFalse("deleted" in completed.output)
        }

    @Test
    fun `true condition follows the edge and the guarded node executes with source data`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "true"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true"), emptyMap())
                    .toList()

            assertEquals(
                listOf("scan", "delete"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            assertEquals(
                "true",
                resolver.invocations.single { it.second == "delete" }.third["target"],
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertEquals("quarantine", completed.output["deleted"])
        }

    @Test
    fun `condition on an INPUT edge evaluates against the mastery input`() =
        runBlocking {
            val resolver =
                RecordingResolver(mapOf("plugin-b/delete" to mapOf("deleted" to "quarantine")))
            val mastery =
                MasteryDefinition(
                    id = "input-guard",
                    name = "Input Guard",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode(
                                id = "delete",
                                pluginId = "plugin-b",
                                action = "delete",
                                inputMapping = mapOf("dry_run" to "INPUT.dry_run"),
                            ),
                        ),
                    edges =
                        listOf(
                            MasteryEdge(
                                "INPUT",
                                "delete",
                                "dry_run",
                                "dry_run",
                                "dry_run == false",
                            ),
                        ),
                )
            val executor = MasteryExecutor(resolver)

            val dryRun =
                executor
                    .execute(mastery, mapOf("dry_run" to "true"))
                    .toList()
            assertTrue(dryRun.filterIsInstance<MasteryProgress.NodeStarted>().isEmpty())
            assertTrue(resolver.invocations.isEmpty())

            val realRun =
                executor
                    .execute(mastery, mapOf("dry_run" to "false"))
                    .toList()
            assertEquals(
                listOf("delete"),
                realRun.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            assertEquals("false", resolver.invocations.single().third["dry_run"])
        }

    @Test
    fun `null and blank conditions keep edges unconditional`() =
        runBlocking {
            // The source value is falsy, but neither edge carries a usable
            // condition, so both dependents must keep running (pre-#1060 fix behaviour).
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/scrub" to mapOf("scrubbed" to "yes"),
                        "plugin-c/purge" to mapOf("purged" to "yes"),
                    ),
                )
            val mastery =
                MasteryDefinition(
                    id = "unconditional",
                    name = "Unconditional",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("scrub", "plugin-b", "scrub"),
                            MasteryNode("purge", "plugin-c", "purge"),
                        ),
                    edges =
                        listOf(
                            MasteryEdge("scan", "scrub", "scan_clean", "cleanliness", null),
                            MasteryEdge("scan", "purge", "scan_clean", "cleanliness", ""),
                        ),
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            val started = events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId }
            assertEquals(setOf("scan", "scrub", "purge"), started.toSet())
            assertEquals(3, resolver.invocations.size)
        }

    @Test
    fun `malformed condition fails closed and the run still completes`() =
        runBlocking {
            // The source value is true and one conjunct would hold, but `&&` is
            // outside the grammar: the whole expression fails closed rather
            // than executing the guarded node on a partial guess.
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "true"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true && confirmed == true"), emptyMap())
                    .toList()

            assertEquals(
                listOf("plugin-a/scan"),
                resolver.invocations.map { "${it.first}/${it.second}" },
            )
            assertEquals(
                listOf("scan"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertFalse("deleted" in completed.output)
        }

    @Test
    fun `skipping a node also skips nodes reachable only through it`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/clean" to mapOf("cleaned" to "yes"),
                        "plugin-c/report" to mapOf("report" to "done"),
                    ),
                )
            val mastery =
                MasteryDefinition(
                    id = "chained",
                    name = "Chained Guard",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("clean", "plugin-b", "clean"),
                            MasteryNode("report", "plugin-c", "report"),
                        ),
                    edges =
                        listOf(
                            MasteryEdge(
                                "scan",
                                "clean",
                                "scan_clean",
                                "cleanliness",
                                "scan_clean == true",
                            ),
                            MasteryEdge("clean", "report", "cleaned", "summary"),
                        ),
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            assertEquals(
                listOf("plugin-a/scan"),
                resolver.invocations.map { "${it.first}/${it.second}" },
            )
            assertEquals(
                listOf("scan"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertTrue(completed.output.isEmpty())
        }

    @Test
    fun `a node still runs when another incoming edge is followed`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/audit" to mapOf("audited" to "yes"),
                        "plugin-c/archive" to mapOf("archived" to "yes"),
                    ),
                )
            val mastery =
                MasteryDefinition(
                    id = "fan-in",
                    name = "Fan In",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("audit", "plugin-b", "audit"),
                            MasteryNode("archive", "plugin-c", "archive"),
                        ),
                    edges =
                        listOf(
                            MasteryEdge(
                                "scan",
                                "archive",
                                "scan_clean",
                                "cleanliness",
                                "scan_clean == true",
                            ),
                            MasteryEdge("audit", "archive", "audited", "auditLog"),
                        ),
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            val started = events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId }
            assertEquals(setOf("scan", "audit", "archive"), started.toSet())
        }

    @Test
    fun `a skipped node emits NodeSkipped with the reason and the run still completes`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true"), emptyMap())
                    .toList()

            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(listOf("delete"), skipped.map { it.nodeId })
            assertEquals(
                "condition 'scan_clean == true' evaluated false",
                skipped.single().reason,
            )
            assertEquals(
                listOf("scan"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertFalse("deleted" in completed.output)
        }

    @Test
    fun `a followed edge emits no NodeSkipped`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "true"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true"), emptyMap())
                    .toList()

            assertTrue(events.filterIsInstance<MasteryProgress.NodeSkipped>().isEmpty())
            assertEquals(
                listOf("scan", "delete"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
        }

    @Test
    fun `a malformed condition emits NodeSkipped with the malformed reason`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "true"),
                        "plugin-b/delete" to mapOf("deleted" to "quarantine"),
                    ),
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(guardedMastery("scan_clean == true && confirmed == true"), emptyMap())
                    .toList()

            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(listOf("delete"), skipped.map { it.nodeId })
            assertEquals(
                "Malformed condition 'scan_clean == true && confirmed == true' (failing closed; " +
                    "supported forms: 'true', 'false', 'key', 'key == literal', 'key != literal')",
                skipped.single().reason,
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertFalse("deleted" in completed.output)
        }

    @Test
    fun `a node skipped by propagation also emits NodeSkipped`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/clean" to mapOf("cleaned" to "yes"),
                        "plugin-c/report" to mapOf("report" to "done"),
                    ),
                )
            val mastery =
                MasteryDefinition(
                    id = "chained-skip",
                    name = "Chained Skip",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("clean", "plugin-b", "clean"),
                            MasteryNode("report", "plugin-c", "report"),
                        ),
                    edges =
                        listOf(
                            MasteryEdge(
                                "scan",
                                "clean",
                                "scan_clean",
                                "cleanliness",
                                "scan_clean == true",
                            ),
                            MasteryEdge("clean", "report", "cleaned", "summary"),
                        ),
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(setOf("clean", "report"), skipped.map { it.nodeId }.toSet())
            assertEquals(
                "source node 'clean' produced no output (it was skipped)",
                skipped.single { it.nodeId == "report" }.reason,
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertTrue(completed.output.isEmpty())
        }
}
