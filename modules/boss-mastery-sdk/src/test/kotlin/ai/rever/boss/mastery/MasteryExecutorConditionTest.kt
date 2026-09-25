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
    fun `a blocked guard vetoes a side-effecting node even when another edge is followed`() =
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
                    id = "fan-in-veto",
                    name = "Fan In Veto",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("audit", "plugin-b", "audit"),
                            MasteryNode(
                                id = "archive",
                                pluginId = "plugin-c",
                                action = "archive",
                                inputMapping =
                                    mapOf(
                                        "cleanliness" to "scan.scan_clean",
                                        "auditLog" to "audit.audited",
                                    ),
                            ),
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

            // The scan guard fired, so the side-effecting archive node is
            // vetoed even though the unguarded audit edge is followed: the
            // scan mapping it would have consumed is exactly the data the
            // author guarded.
            val started = events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId }
            assertEquals(setOf("scan", "audit"), started.toSet())
            assertTrue(resolver.invocations.none { it.second == "archive" })

            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(listOf("archive"), skipped.map { it.nodeId })
            assertEquals(
                "incoming edge blocked: condition 'scan_clean == true' evaluated false",
                skipped.single().reason,
            )
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertFalse("archived" in completed.output)
        }

    @Test
    fun `a pure node admits on any followed edge while the blocked edge contributes no data`() =
        runBlocking {
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-b/audit" to mapOf("audited" to "yes"),
                        "plugin-c/unlock" to mapOf("unlocked" to "granted"),
                    ),
                )
            val mastery =
                MasteryDefinition(
                    id = "fan-in-pure",
                    name = "Fan In Pure",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("audit", "plugin-b", "audit"),
                            MasteryNode(
                                id = "unlock",
                                pluginId = "plugin-c",
                                action = "unlock",
                                inputMapping =
                                    mapOf(
                                        "cleanliness" to "scan.scan_clean",
                                        "auditLog" to "audit.audited",
                                    ),
                                pure = true,
                            ),
                        ),
                    edges =
                        listOf(
                            MasteryEdge(
                                "scan",
                                "unlock",
                                "scan_clean",
                                "cleanliness",
                                "scan_clean == true",
                            ),
                            MasteryEdge("audit", "unlock", "audited", "auditLog"),
                        ),
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            // The mastery-unlock-style node keeps any-admit fan-in: it runs
            // on the followed audit edge, but the blocked scan edge's mapped
            // data never reaches it — only the audit mapping resolves.
            val started = events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId }
            assertEquals(setOf("scan", "audit", "unlock"), started.toSet())
            assertTrue(events.filterIsInstance<MasteryProgress.NodeSkipped>().isEmpty())

            val unlockInput = resolver.invocations.single { it.second == "unlock" }.third
            assertEquals("yes", unlockInput["auditLog"])
            assertFalse("cleanliness" in unlockInput)
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertEquals("granted", completed.output["unlocked"])
        }

    /**
     * A pure unlock node fanned into by two references to one byte-identical
     * blocked edge from scan, plus a downstream report node: the duplicate
     * occurrences must not collapse into a single fan-in verdict.
     */
    private fun duplicateFanInMastery(edge: MasteryEdge) =
        MasteryDefinition(
            id = "duplicate-fan-in",
            name = "Duplicate Fan In",
            description = "",
            nodes =
                listOf(
                    MasteryNode("scan", "plugin-a", "scan"),
                    MasteryNode(
                        id = "unlock",
                        pluginId = "plugin-c",
                        action = "unlock",
                        inputMapping = mapOf("cleanliness" to "scan.scan_clean"),
                        pure = true,
                    ),
                    MasteryNode(
                        id = "report",
                        pluginId = "plugin-d",
                        action = "report",
                        inputMapping = mapOf("state" to "unlock.unlocked"),
                    ),
                ),
            edges =
                listOf(
                    edge,
                    edge,
                    MasteryEdge("unlock", "report", "unlocked", "state"),
                ),
        )

    @Test
    fun `duplicate byte-identical blocked edges cannot fail a pure node open`() =
        runBlocking<Unit> {
            // Verdicts used to be keyed on the MasteryEdge data class, so the
            // two byte-identical occurrences collapsed into one map entry and
            // the all-blocked test compared one blocked verdict against two
            // occurrences — a pure node whose every incoming edge was blocked
            // failed open and executed. Verdicts are per-occurrence now, so
            // both block and the node is skipped like any other fan-in whose
            // every edge blocked.
            val resolver =
                RecordingResolver(
                    mapOf(
                        "plugin-a/scan" to mapOf("scan_clean" to "false"),
                        "plugin-c/unlock" to mapOf("unlocked" to "granted"),
                        "plugin-d/report" to mapOf("report" to "done"),
                    ),
                )
            val edge =
                MasteryEdge(
                    "scan",
                    "unlock",
                    "scan_clean",
                    "cleanliness",
                    "scan_clean == true",
                )
            val events =
                MasteryExecutor(resolver)
                    .execute(duplicateFanInMastery(edge), emptyMap())
                    .toList()

            // Only scan runs: every occurrence of the duplicated guard
            // blocked, so the pure node has no followed edge to admit on, and
            // the report node is skipped by propagation from its skipped
            // source.
            assertEquals(
                listOf("scan"),
                events.filterIsInstance<MasteryProgress.NodeStarted>().map { it.nodeId },
            )
            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(setOf("unlock", "report"), skipped.map { it.nodeId }.toSet())
            assertEquals(
                "condition 'scan_clean == true' evaluated false; " +
                    "condition 'scan_clean == true' evaluated false",
                skipped.single { it.nodeId == "unlock" }.reason,
            )
            assertTrue(resolver.invocations.none { it.second == "unlock" })
            val completed = assertIs<MasteryProgress.Completed>(events.last())
            assertTrue(completed.output.isEmpty())
        }

    @Test
    fun `a skip reason longer than 2048 characters is capped like NodeFailed error`() =
        runBlocking {
            val longKey = "k" + "x".repeat(230)
            val resolver = RecordingResolver(mapOf("plugin-a/scan" to mapOf("scan_clean" to "true")))
            val mastery =
                MasteryDefinition(
                    id = "reason-cap",
                    name = "Reason Cap",
                    description = "",
                    nodes =
                        listOf(
                            MasteryNode("scan", "plugin-a", "scan"),
                            MasteryNode("delete", "plugin-b", "delete"),
                        ),
                    edges =
                        (1..10).map { i ->
                            MasteryEdge("scan", "delete", "scan_clean", "target", longKey + i)
                        },
                )
            val events = MasteryExecutor(resolver).execute(mastery, emptyMap()).toList()

            // Ten blocked edges join ten ~290-character reasons into one
            // reason per skipped node; the emitted reason stays bounded.
            val skipped = events.filterIsInstance<MasteryProgress.NodeSkipped>()
            assertEquals(listOf("delete"), skipped.map { it.nodeId })
            assertEquals(
                (1..10)
                    .joinToString("; ") { i ->
                        "condition '${longKey}$i' evaluated false (key has no output value)"
                    }.take(2048),
                skipped.single().reason,
            )
            assertEquals(2048, skipped.single().reason.length)
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
                    "supported forms: 'true', 'false', 'key', 'key == literal', 'key != literal'; " +
                    "a condition key is a bare key of the source node's output map, not the " +
                    "SOURCE_NODE.outputKey form used by inputMapping)",
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
