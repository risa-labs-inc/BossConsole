package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.OrchestratorServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairApproval
import ai.rever.boss.ipc.proto.RepairHint
import ai.rever.boss.ipc.proto.RepairStrategy
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrchestratorAuthorizationTest {
    @Test
    fun `only the host may initiate repairs or claim an approval`() =
        runBlocking {
            val directory = Files.createTempDirectory("orchestrator-authorization-").toFile()
            val restarts = AtomicInteger()
            val approvals = AtomicInteger()
            val engine =
                RepairEngine(
                    analyzer = CrashAnalyzer(),
                    snapshotManager = SnapshotManager(directory),
                    projectRoot = directory.absolutePath,
                    onRequestRestart = { _, _ -> restarts.incrementAndGet() },
                )
            val service =
                OrchestratorServiceImpl(engine, onRepairApproved = { _, _ ->
                    approvals.incrementAndGet()
                    ApprovalResult.Applied("test")
                })
            val registry = ProcessTokenRegistry()
            val identity = IpcTlsIdentity.create()
            val server = BossIpcServer("tcp://127.0.0.1:0", registry, identity).addService(service).start()
            try {
                for (authority in ProcessAuthority.entries) {
                    val token = registry.issue(authority.name, authority)
                    val client =
                        BossIpcClient(
                            "tcp://127.0.0.1:${server.port}",
                            IpcClientCredentials(identity.certificateBase64, token),
                        )
                    try {
                        val stub = OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineStub(client.channel)
                        verifyAccess(authority, stub, restarts, approvals)
                    } finally {
                        client.shutdown(0)
                    }
                }
            } finally {
                server.stop()
                directory.deleteRecursively()
            }
        }

    private suspend fun verifyAccess(
        authority: ProcessAuthority,
        stub: OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineStub,
        restarts: AtomicInteger,
        approvals: AtomicInteger,
    ) {
        val restart = RepairStrategy.REPAIR_STRATEGY_RESTART
        val hint = RepairHint.newBuilder().setFailurePattern("Boom").setRepairStrategy(restart)
        val report =
            ProcessFailureReport
                .newBuilder()
                .setProcessId("target")
                .setErrorType("Boom")
                .setManifest(ProcessManifest.newBuilder().addRepairHints(hint))
                .build()
        if (authority == ProcessAuthority.HOST) {
            stub.reportFailure(report)
            assertEquals(1, restarts.get())
        } else {
            val failure = assertFailsWith<StatusException> { stub.reportFailure(report) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            val approval =
                RepairApproval
                    .newBuilder()
                    .setRepairId("unknown")
                    .setApproved(true)
                    .build()
            val denial = assertFailsWith<StatusException> { stub.approveRepair(approval) }
            assertEquals(Status.Code.PERMISSION_DENIED, denial.status.code)
            assertEquals(0, restarts.get())
            assertEquals(0, approvals.get())
        }
    }
}
