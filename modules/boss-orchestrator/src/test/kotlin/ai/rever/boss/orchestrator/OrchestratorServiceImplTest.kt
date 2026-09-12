package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairAction
import ai.rever.boss.ipc.proto.RepairApproval
import ai.rever.boss.ipc.proto.RepairHint
import ai.rever.boss.ipc.proto.RepairHistoryRequest
import ai.rever.boss.ipc.proto.RepairStrategy
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestratorServiceImplTest {
    private lateinit var dataDir: File
    private lateinit var snapshots: SnapshotManager

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("boss-service-test").toFile()
        snapshots = SnapshotManager(dataDir)
    }

    @AfterTest
    fun cleanup() {
        dataDir.deleteRecursively()
    }

    private fun engine(onRequestRestart: suspend (String, List<String>) -> Unit = { _, _ -> }) =
        RepairEngine(
            analyzer = CrashAnalyzer(),
            snapshotManager = snapshots,
            aiClient = null,
            projectRoot = dataDir.absolutePath,
            onRequestRestart = onRequestRestart,
        )

    /** A report that forces [strategy] through a HIGH-confidence manifest hint. */
    private fun report(
        processId: String,
        strategy: RepairStrategy,
    ): ProcessFailureReport =
        ProcessFailureReport
            .newBuilder()
            .setProcessId(processId)
            .setErrorType("ai.rever.Boom")
            .setErrorMessage("boom")
            .setConsecutiveFailures(1)
            .setManifest(
                ProcessManifest
                    .newBuilder()
                    .addRepairHints(
                        RepairHint
                            .newBuilder()
                            .setFailurePattern("Boom")
                            .setRepairStrategy(strategy)
                            .setDescription("forced by test")
                            .build(),
                    ).build(),
            ).build()

    // ---- the approval response says what happened ----

    @Test
    fun `completed history is bounded without evicting pending proposals`() =
        runTest {
            val service = OrchestratorServiceImpl(engine(), historyLimit = 3, pendingLimit = 2)
            val pending = service.reportFailure(report("pending", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))
            repeat(8) { service.reportFailure(report("done-$it", RepairStrategy.REPAIR_STRATEGY_RESTART)) }
            val history = service.getRepairHistory(RepairHistoryRequest.getDefaultInstance()).entriesList
            assertEquals(3, history.size)
            assertEquals(setOf("pending", "done-6", "done-7"), history.map { it.processId }.toSet())
            assertTrue(history.any { it.repairId == pending.repairId })
        }

    @Test
    fun `pending capacity includes an approval while its execution is still running`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val service =
                OrchestratorServiceImpl(
                    engine(),
                    historyLimit = 2,
                    pendingLimit = 1,
                    onRepairApproved = { _, _ ->
                        started.complete(Unit)
                        finish.await()
                        ApprovalResult.Applied("Applied")
                    },
                )
            val pending = service.reportFailure(report("one", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))
            val execution = async { service.approveRepair(approval(pending.repairId)) }
            started.await()
            val refused =
                assertFailsWith<StatusRuntimeException> {
                    service.reportFailure(report("two", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))
                }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
            assertFalse(service.approveRepair(approval(pending.repairId)).applied)
            finish.complete(Unit)
            assertTrue(execution.await().applied)
            val next = service.reportFailure(report("two", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))
            assertTrue(next.requiresUserApproval)
        }

    @Test
    fun `analysis admission happens before restart side effects and recovers after completion`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val service =
                OrchestratorServiceImpl(
                    engine { _, _ ->
                        calls++
                        started.complete(Unit)
                        finish.await()
                    },
                    analysisLimit = 1,
                )
            val first = async { service.reportFailure(report("one", RepairStrategy.REPAIR_STRATEGY_RESTART)) }
            started.await()
            val refused =
                assertFailsWith<StatusRuntimeException> {
                    service.reportFailure(report("two", RepairStrategy.REPAIR_STRATEGY_RESTART))
                }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, refused.status.code)
            assertEquals(1, calls)
            finish.complete(Unit)
            first.await()
            service.reportFailure(report("three", RepairStrategy.REPAIR_STRATEGY_RESTART))
            assertEquals(2, calls)
        }

    @Test
    fun `an approval whose execution fails is not reported as applied`() =
        runTest {
            val service =
                OrchestratorServiceImpl(
                    repairEngine = engine(),
                    onRepairApproved = { _, _ -> error("the patch could not be written") },
                )
            val parked = service.reportFailure(report("p1", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))
            assertTrue(parked.requiresUserApproval, "PATCH_SOURCE is the strategy that gets parked")

            val response = service.approveRepair(approval(parked.repairId))

            assertFalse(response.applied, "a repair nothing carried out must not be reported as applied")
            assertTrue(
                response.resultMessage.contains("the patch could not be written"),
                response.resultMessage,
            )
        }

    @Test
    fun `an approval that is refused is not reported as applied`() =
        runTest {
            val service =
                OrchestratorServiceImpl(
                    repairEngine = engine(),
                    onRepairApproved = { processId, _ ->
                        ApprovalResult.Refused("nothing applies patches for $processId")
                    },
                )
            val parked = service.reportFailure(report("p2", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))

            val response = service.approveRepair(approval(parked.repairId))

            assertFalse(response.applied)
            assertEquals("nothing applies patches for p2", response.resultMessage)
        }

    @Test
    fun `an approval that is carried out is reported as applied with the executor's message`() =
        runTest {
            val service =
                OrchestratorServiceImpl(
                    repairEngine = engine(),
                    onRepairApproved = { processId, _ -> ApprovalResult.Applied("patched $processId") },
                )
            val parked = service.reportFailure(report("p3", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))

            val response = service.approveRepair(approval(parked.repairId))

            assertTrue(response.applied)
            assertEquals("patched p3", response.resultMessage)
        }

    @Test
    fun `a host that wires nothing to apply repairs reports the approval as not applied`() =
        runTest {
            val service = OrchestratorServiceImpl(repairEngine = engine())
            val parked = service.reportFailure(report("p4", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))

            val response = service.approveRepair(approval(parked.repairId))

            assertFalse(response.applied, "an unwired host must not look like one that applied the repair")
            assertTrue(response.resultMessage.contains("p4"), response.resultMessage)
        }

    // ---- an approved repair acts on the process, not on the repair id ----

    @Test
    fun `an approved repair is handed the process it was reported for`() =
        runTest {
            val seen = mutableListOf<Pair<String, String>>()
            val service =
                OrchestratorServiceImpl(
                    repairEngine = engine(),
                    onRepairApproved = { processId, action ->
                        seen.add(processId to action.repairId)
                        ApprovalResult.Applied("ok")
                    },
                )
            val parked = service.reportFailure(report("service-worker-7", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))

            service.approveRepair(approval(parked.repairId))

            assertEquals(listOf("service-worker-7" to parked.repairId), seen)
            assertTrue(
                seen.single().first != seen.single().second,
                "the process id and the repair id are different things",
            )
        }

    @Test
    fun `applyApprovedRepair shuts down the reported process and never the repair id`() =
        runTest {
            val shutdown = mutableListOf<String>()
            val action =
                RepairAction
                    .newBuilder()
                    .setRepairId("11111111-2222-3333-4444-555555555555")
                    .setStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART)
                    .build()

            val result = applyApprovedRepair("service-editor-2", action) { shutdown.add(it) }

            assertEquals(listOf("service-editor-2"), shutdown)
            assertFalse(shutdown.contains(action.repairId), "a repair id is not a process id")
            assertTrue(result is ApprovalResult.Applied, "got $result")
        }

    @Test
    fun `applyApprovedRepair refuses a strategy it cannot carry out and shuts down nothing`() =
        runTest {
            val shutdown = mutableListOf<String>()
            val action =
                RepairAction
                    .newBuilder()
                    .setRepairId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                    .setStrategy(RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE)
                    .build()

            val result = applyApprovedRepair("service-editor-2", action) { shutdown.add(it) }

            assertEquals(emptyList(), shutdown, "a source patch must not stop the process")
            val refused = assertNotNull(result as? ApprovalResult.Refused, "got $result")
            assertTrue(refused.reason.contains("service-editor-2"), refused.reason)
        }

    // ---- the reset-state action carries the snapshot it points at ----

    @Test
    fun `a reset-state action names the snapshot the process comes back on`() =
        runTest {
            val snapshotId = snapshots.save("p5", "state".toByteArray())
            val service = OrchestratorServiceImpl(repairEngine = engine())

            val action = service.reportFailure(report("p5", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            assertTrue(action.hasResetState(), "got ${action.repairDetailCase}")
            assertTrue(action.resetState.restoreSnapshot, "the reset restores rather than discards")
            assertEquals(snapshotId, action.resetState.snapshotId)
        }

    @Test
    fun `a reset-state action with no snapshot recorded says so`() =
        runTest {
            val service = OrchestratorServiceImpl(repairEngine = engine())

            val action = service.reportFailure(report("p6", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            assertTrue(action.hasResetState())
            assertFalse(action.resetState.restoreSnapshot)
            assertEquals("", action.resetState.snapshotId)
        }

    @Test
    fun `an unknown repair id is still reported as not applied`() =
        runTest {
            val service = OrchestratorServiceImpl(repairEngine = engine())

            val response = service.approveRepair(approval("no-such-repair"))

            assertFalse(response.applied)
            assertTrue(response.resultMessage.contains("no-such-repair"), response.resultMessage)
        }

    @Test
    fun `a rejected repair is not applied and is not parked twice`() =
        runTest {
            var applications = 0
            val service =
                OrchestratorServiceImpl(
                    repairEngine = engine(),
                    onRepairApproved = { _, _ ->
                        applications++
                        ApprovalResult.Applied("ok")
                    },
                )
            val parked = service.reportFailure(report("p7", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE))

            val rejected =
                service.approveRepair(
                    RepairApproval
                        .newBuilder()
                        .setRepairId(parked.repairId)
                        .setApproved(false)
                        .setUserNotes("not now")
                        .build(),
                )

            assertFalse(rejected.applied)
            assertEquals(0, applications)
            // The repair is spent: approving it afterwards finds nothing pending.
            assertFalse(service.approveRepair(approval(parked.repairId)).applied)
            assertEquals(0, applications)
        }

    private fun approval(repairId: String): RepairApproval =
        RepairApproval
            .newBuilder()
            .setRepairId(repairId)
            .setApproved(true)
            .build()
}
