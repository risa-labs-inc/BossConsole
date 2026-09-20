package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import ai.rever.boss.mastery.MasteryExecutor
import ai.rever.boss.process.ProcessRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.response.respond
import io.ktor.server.request.*
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SubmitFeedbackReq(val nodeId: String, val feedback: String)

@Serializable
data class EditCheckpointReq(val nodeId: String, val editedOutput: Map<String, String>)

@Serializable
data class ReplayReq(val startNodeId: String, val feedback: String? = null)
import org.slf4j.LoggerFactory

/**
 * Entry point for the Mastery Orchestrator process.
 *
 * Connects to the kernel, registers itself, starts the MasteryService gRPC server,
 * and exposes workflow execution to the rest of the microkernel.
 */
fun main() {
    val logger = LoggerFactory.getLogger("MasteryOrchestratorMain")
    logger.info("Mastery Orchestrator starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest =
        ProcessManifest
            .newBuilder()
            .setProcessId(bootstrap.processId)
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .setDisplayName("BOSS Mastery Orchestrator")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.mastery.orchestrator.MasteryOrchestratorMainKt")
            .setBehaviorSpec(
                "Mastery workflow orchestrator. Executes DAG workflows that compose plugin capabilities. " +
                    "Stores mastery definitions in memory, resolves capabilities from registered plugin processes, " +
                    "and streams real-time progress events during execution.",
            ).addAllSourceFiles(
                listOf(
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryOrchestratorMain.kt",
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryServiceImpl.kt",
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/ProcessRegistryCapabilityResolver.kt",
                ),
            ).addAllExposedServices(listOf("boss.ipc.v1.MasteryService"))
            .setHealthContract(
                HealthContract
                    .newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(20000)
                    .build(),
            ).build()

    runBlocking {
        val connection = bootstrap.connect(manifest)

        val processRegistry = ProcessRegistry()
        val capabilityResolver = ProcessRegistryCapabilityResolver(processRegistry)
        // Use a file-based SQLite execution store for persistence across restarts.
        val dbPath = System.getProperty("boss.mastery.db")
            ?: "${System.getProperty("user.home")}${java.io.File.separator}.boss${java.io.File.separator}mastery.db"
        val executionStore = ai.rever.boss.mastery.SqlExecutionStore(dbPath)
        val masteryExecutor = MasteryExecutor(capabilityResolver, executionStore)
        val masteryService = MasteryServiceImpl(masteryExecutor, executionStore)
        connection.processServer.addService(masteryService)
        connection.startServer()

        // Start a small HTTP server for checkpoint inspection UI and JSON API
        kotlinx.coroutines.launch {
            startHttpUiServer(executionStore, masteryService)
        }

        logger.info("Mastery Orchestrator running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}

// Minimal Ktor-based UI server for inspection
private fun startHttpUiServer(executionStore: ai.rever.boss.mastery.ExecutionStore, masteryService: MasteryServiceImpl) {
    val server = embeddedServer(Netty, port = 8081) {
        install(ContentNegotiation) { json(Json { prettyPrint = true }) }

        routing {
            static("/") {
                resources("static")
                defaultResource("static/index.html")
            }

            get("/api/executions") {
                val execs = executionStore.listExecutions()
                call.respond(execs)
            }

            get("/api/executions/{id}/checkpoints") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val cps = executionStore.getCheckpoints(id)
                call.respond(cps)
            }

            post("/api/executions/{id}/feedback") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<SubmitFeedbackReq>()
                val ok = masteryService.submitNodeFeedback(id, req.nodeId, req.feedback)
                call.respond(mapOf("success" to ok))
            }

            post("/api/executions/{id}/edit") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<EditCheckpointReq>()
                val newExec = masteryService.continueFromEditedCheckpoint(id, req.nodeId, req.editedOutput)
                call.respond(mapOf("newExecutionId" to (newExec ?: "")))
            }

            post("/api/executions/{id}/replay") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<ReplayReq>()
                val newExec = masteryService.replayFromNode(id, req.startNodeId, req.feedback)
                call.respond(mapOf("newExecutionId" to (newExec ?: "")))
            }

            post("/api/executions/{id}/resume") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val newExec = masteryService.resumeMastery(ai.rever.boss.ipc.proto.MasteryExecutionId.newBuilder().setExecutionId(id).build())
                call.respond(mapOf("newExecutionId" to newExec.newExecutionId))
            }
        }
    }
    server.start(false)
}
