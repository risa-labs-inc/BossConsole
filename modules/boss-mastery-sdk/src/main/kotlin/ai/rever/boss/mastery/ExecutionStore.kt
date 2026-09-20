package ai.rever.boss.mastery

import java.util.concurrent.ConcurrentHashMap

interface ExecutionStore {
    suspend fun createExecution(execution: MasteryExecution)
    suspend fun getExecution(executionId: String): MasteryExecution?
    suspend fun saveCheckpoint(checkpoint: NodeCheckpoint)
    suspend fun getCheckpoints(executionId: String): List<NodeCheckpoint>
    suspend fun listExecutions(): List<MasteryExecution>
}

/** Simple in-memory implementation for prototyping and tests. */
class InMemoryExecutionStore : ExecutionStore {
    private val executions = ConcurrentHashMap<String, MasteryExecution>()
    private val checkpoints = ConcurrentHashMap<String, MutableList<NodeCheckpoint>>()

    override suspend fun createExecution(execution: MasteryExecution) {
        executions[execution.executionId] = execution
        checkpoints.putIfAbsent(execution.executionId, mutableListOf())
    }

    override suspend fun getExecution(executionId: String): MasteryExecution? {
        return executions[executionId]
    }

    override suspend fun saveCheckpoint(checkpoint: NodeCheckpoint) {
        checkpoints.computeIfAbsent(checkpoint.executionId) { mutableListOf() }.add(checkpoint)
        // Also update the execution's checkpoint list snapshot if present
        executions.computeIfPresent(checkpoint.executionId) { _, old ->
            old.copy(checkpoints = (checkpoints[checkpoint.executionId] ?: emptyList()).toList())
        }
    }

    override suspend fun getCheckpoints(executionId: String): List<NodeCheckpoint> {
        return checkpoints[executionId]?.toList() ?: emptyList()
    }

    override suspend fun listExecutions(): List<MasteryExecution> {
        return executions.values.toList()
    }
}
