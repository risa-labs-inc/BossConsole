package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.CancelMasteryResponse
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.ExecuteMasteryRequest
import ai.rever.boss.ipc.proto.GenerateMasteryRequest
import ai.rever.boss.ipc.proto.ListMasteriesRequest
import ai.rever.boss.ipc.proto.ListMasteriesResponse
import ai.rever.boss.ipc.proto.MasteryCompleted
import ai.rever.boss.ipc.proto.MasteryExecutionId
import ai.rever.boss.ipc.proto.MasteryFailed
import ai.rever.boss.ipc.proto.MasteryId
import ai.rever.boss.ipc.proto.MasteryServiceGrpcKt
import ai.rever.boss.ipc.proto.MasteryStarted
import ai.rever.boss.ipc.proto.MasteryStatus
import ai.rever.boss.ipc.proto.MasterySummary
import ai.rever.boss.ipc.proto.NodeCompleted
import ai.rever.boss.ipc.proto.NodeFailed
import ai.rever.boss.ipc.proto.NodeStarted
import ai.rever.boss.mastery.MasteryExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import ai.rever.boss.ipc.proto.MasteryDefinition as PMasteryDef
import ai.rever.boss.ipc.proto.MasteryEdge as PMasteryEdge
import ai.rever.boss.ipc.proto.MasteryNode as PMasteryNode
import ai.rever.boss.ipc.proto.MasteryProgress as PProgress
import ai.rever.boss.mastery.MasteryDefinition as KMasteryDef
import ai.rever.boss.mastery.MasteryEdge as KMasteryEdge
import ai.rever.boss.mastery.MasteryNode as KMasteryNode
import ai.rever.boss.mastery.MasteryProgress as KProgress

/**
 * gRPC implementation of MasteryService from mastery.proto.
 *
 * Stores mastery definitions in memory and delegates execution to [MasteryExecutor].
 * Running executions are tracked for cancellation via [cancelMastery].
 */
class MasteryServiceImpl(
    private val executor: MasteryExecutor,
) : MasteryServiceGrpcKt.MasteryServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(MasteryServiceImpl::class.java)

    private val definitions = ConcurrentHashMap<String, KMasteryDef>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val execStatus = ConcurrentHashMap<String, MasteryStatus>()

    override suspend fun createMastery(request: PMasteryDef): MasteryId {
        val kotlinDef = request.toKotlin()
        val id = kotlinDef.id.ifBlank { UUID.randomUUID().toString() }
        definitions[id] = kotlinDef.copy(id = id)
        logger.info("Mastery created: id={}, name={}", id, kotlinDef.name)
        return MasteryId.newBuilder().setId(id).build()
    }

    override fun executeMastery(request: ExecuteMasteryRequest): Flow<PProgress> =
        flow {
            val def =
                definitions[request.masteryId] ?: run {
                    logger.warn("ExecuteMastery: definition not found: {}", request.masteryId)
                    return@flow
                }

            val executionId = UUID.randomUUID().toString()
            logger.info("Starting mastery execution: id={}, masteryId={}", executionId, def.id)
            updateStatus(executionId, request.masteryId, "running")

            try {
                coroutineScope {
                    // Register the coroutineScope's Job BEFORE any suspension point to prevent
                    // a cancel-window race where cancelMastery() arrives before registration (M9 fix).
                    runningJobs[executionId] = coroutineContext[Job]!!

                    executor.execute(def, request.inputMap).collect { progress ->
                        emit(progress.toProto(executionId))
                        when (progress) {
                            is KProgress.Completed -> updateStatus(executionId, request.masteryId, "completed")
                            is KProgress.Failed -> updateStatus(executionId, request.masteryId, "failed")
                            else -> Unit
                        }
                    }
                }
            } finally {
                runningJobs.remove(executionId)
            }
        }

    override suspend fun cancelMastery(request: MasteryExecutionId): CancelMasteryResponse {
        val job = runningJobs.remove(request.executionId)
        return if (job != null) {
            job.cancel()
            updateStatus(request.executionId, "", "cancelled")
            logger.info("Cancelled execution: {}", request.executionId)
            CancelMasteryResponse
                .newBuilder()
                .setSuccess(true)
                .setMessage("Execution ${request.executionId} cancelled")
                .build()
        } else {
            CancelMasteryResponse
                .newBuilder()
                .setSuccess(false)
                .setMessage("No running execution: ${request.executionId}")
                .build()
        }
    }

    override suspend fun getMasteryStatus(request: MasteryExecutionId): MasteryStatus =
        execStatus[request.executionId]
            ?: MasteryStatus
                .newBuilder()
                .setExecutionId(request.executionId)
                .setState("unknown")
                .build()

    override suspend fun generateMastery(request: GenerateMasteryRequest): PMasteryDef {
        // AI integration point: future — generate definition from task description + available capabilities
        logger.info("GenerateMastery stub: task={}", request.taskDescription)
        return KMasteryDef(
            id = UUID.randomUUID().toString(),
            name = "Generated: ${request.taskDescription.take(40)}",
            description = request.taskDescription,
            nodes = emptyList(),
            edges = emptyList(),
        ).toProto()
    }

    override suspend fun listMasteries(request: ListMasteriesRequest): ListMasteriesResponse {
        val all: List<KMasteryDef> =
            definitions.values
                .filter { def ->
                    request.filter.isBlank() ||
                        def.name.contains(request.filter, ignoreCase = true) ||
                        def.description.contains(request.filter, ignoreCase = true)
                }.sortedByDescending { it.updatedAt }

        val paginated =
            if (request.limit > 0) {
                all.drop(request.offset).take(request.limit)
            } else {
                all.drop(request.offset)
            }

        return ListMasteriesResponse
            .newBuilder()
            .addAllMasteries(
                paginated.map { def ->
                    MasterySummary
                        .newBuilder()
                        .setId(def.id)
                        .setName(def.name)
                        .setDescription(def.description)
                        .setNodeCount(def.nodes.size)
                        .setAuthor(def.author)
                        .build()
                },
            ).setTotalCount(all.size)
            .build()
    }

    override suspend fun deleteMastery(request: MasteryId): Empty {
        definitions.remove(request.id)
        logger.info("Mastery deleted: id={}", request.id)
        return Empty.getDefaultInstance()
    }

    private fun updateStatus(
        executionId: String,
        masteryId: String,
        state: String,
    ) {
        val builder =
            execStatus[executionId]?.toBuilder()
                ?: MasteryStatus
                    .newBuilder()
                    .setExecutionId(executionId)
                    .setMasteryId(masteryId)
                    .setStartedAt(System.currentTimeMillis())
        builder.setState(state)
        if (state in setOf("completed", "failed", "cancelled")) {
            builder.setCompletedAt(System.currentTimeMillis())
        }
        execStatus[executionId] = builder.build()
    }
}

// ---- Proto <-> Kotlin conversion (private to this file) ----

private fun PMasteryDef.toKotlin(): KMasteryDef =
    KMasteryDef(
        id = id,
        name = name,
        description = description,
        inputSchemaJson = inputSchemaJson,
        outputSchemaJson = outputSchemaJson,
        nodes =
            nodesList.map { n ->
                KMasteryNode(
                    id = n.id,
                    pluginId = n.pluginId,
                    action = n.action,
                    inputMapping = n.inputMappingMap,
                    staticConfig = n.staticConfigMap,
                    isAgentCall = n.isAgentCall,
                    agentPrompt = n.agentPrompt.takeIf { it.isNotBlank() },
                    maxRetries = n.maxRetries,
                    timeoutMs = n.timeoutMs.takeIf { it > 0 } ?: 300_000L,
                    displayName = n.displayName,
                )
            },
        edges =
            edgesList.map { e ->
                KMasteryEdge(
                    fromNode = e.fromNode,
                    toNode = e.toNode,
                    outputKey = e.outputKey,
                    inputKey = e.inputKey,
                    condition = e.condition.takeIf { it.isNotBlank() },
                )
            },
        author = author,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun KMasteryDef.toProto(): PMasteryDef {
    val b =
        PMasteryDef
            .newBuilder()
            .setId(id)
            .setName(name)
            .setDescription(description)
            .setInputSchemaJson(inputSchemaJson)
            .setOutputSchemaJson(outputSchemaJson)
            .setAuthor(author)
            .setCreatedAt(createdAt)
            .setUpdatedAt(updatedAt)
    nodes.forEach { n ->
        b.addNodes(
            PMasteryNode
                .newBuilder()
                .setId(n.id)
                .setPluginId(n.pluginId)
                .setAction(n.action)
                .putAllInputMapping(n.inputMapping)
                .putAllStaticConfig(n.staticConfig)
                .setIsAgentCall(n.isAgentCall)
                .apply { n.agentPrompt?.let { setAgentPrompt(it) } }
                .setMaxRetries(n.maxRetries)
                .setTimeoutMs(n.timeoutMs)
                .setDisplayName(n.displayName)
                .build(),
        )
    }
    edges.forEach { e ->
        b.addEdges(
            PMasteryEdge
                .newBuilder()
                .setFromNode(e.fromNode)
                .setToNode(e.toNode)
                .setOutputKey(e.outputKey)
                .setInputKey(e.inputKey)
                .apply { e.condition?.let { setCondition(it) } }
                .build(),
        )
    }
    return b.build()
}

private fun KProgress.toProto(executionId: String): PProgress {
    val b =
        PProgress
            .newBuilder()
            .setExecutionId(executionId)
            .setTimestamp(System.currentTimeMillis())
    when (this) {
        is KProgress.Started -> {
            b.setStarted(
                MasteryStarted
                    .newBuilder()
                    .setMasteryId(masteryId)
                    .setTotalNodes(totalNodes)
                    .build(),
            )
        }

        is KProgress.NodeStarted -> {
            b.setNodeStarted(
                NodeStarted
                    .newBuilder()
                    .setNodeId(nodeId)
                    .setDisplayName(displayName)
                    .build(),
            )
        }

        is KProgress.NodeCompleted -> {
            b.setNodeCompleted(
                NodeCompleted
                    .newBuilder()
                    .setNodeId(nodeId)
                    .putAllOutput(output)
                    .setDurationMs(durationMs)
                    .build(),
            )
        }

        is KProgress.NodeFailed -> {
            b.setNodeFailed(
                NodeFailed
                    .newBuilder()
                    .setNodeId(nodeId)
                    .setErrorMessage(error)
                    .setWillRetry(willRetry)
                    .build(),
            )
        }

        is KProgress.Completed -> {
            b.setCompleted(
                MasteryCompleted
                    .newBuilder()
                    .putAllOutput(output)
                    .setTotalDurationMs(totalDurationMs)
                    .build(),
            )
        }

        is KProgress.Failed -> {
            b.setFailed(
                MasteryFailed
                    .newBuilder()
                    .setErrorMessage(error)
                    .setFailedNodeId(failedNodeId)
                    .build(),
            )
        }
    }
    return b.build()
}
