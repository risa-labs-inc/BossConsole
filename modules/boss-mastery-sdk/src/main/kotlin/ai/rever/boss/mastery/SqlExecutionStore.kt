package ai.rever.boss.mastery

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SqlExecutionStore(private val dbPath: String) : ExecutionStore {
    private val conn: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        createTables()
    }

    private fun createTables() {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS executions (
                    execution_id TEXT PRIMARY KEY,
                    mastery_id TEXT,
                    input_json TEXT,
                    state TEXT,
                    created_at INTEGER
                )
            """.trimIndent(),
            )

            st.execute(
                """
                CREATE TABLE IF NOT EXISTS checkpoints (
                    checkpoint_id TEXT PRIMARY KEY,
                    execution_id TEXT,
                    node_id TEXT,
                    attempt INTEGER,
                    input_json TEXT,
                    output_json TEXT,
                    started_at INTEGER,
                    completed_at INTEGER,
                    feedback TEXT,
                    parent_checkpoint_id TEXT,
                    human_edited INTEGER
                )
            """.trimIndent(),
            )
        }
    }

    override suspend fun createExecution(execution: MasteryExecution) {
        val sql = "INSERT OR REPLACE INTO executions(execution_id, mastery_id, input_json, state, created_at) VALUES (?,?,?,?,?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, execution.executionId)
            ps.setString(2, execution.masteryId)
            ps.setString(3, Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), execution.input))
            ps.setString(4, execution.state)
            ps.setLong(5, Instant.now().toEpochMilli())
            ps.executeUpdate()
        }
    }

    override suspend fun getExecution(executionId: String): MasteryExecution? {
        val sql = "SELECT execution_id, mastery_id, input_json, state FROM executions WHERE execution_id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, executionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val inputJson = rs.getString("input_json")
                    val input = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), inputJson)
                    return MasteryExecution(rs.getString("execution_id"), rs.getString("mastery_id"), input, rs.getString("state"))
                }
            }
        }
        return null
    }

    override suspend fun saveCheckpoint(checkpoint: NodeCheckpoint) {
        val sql = "INSERT INTO checkpoints(checkpoint_id, execution_id, node_id, attempt, input_json, output_json, started_at, completed_at, feedback, parent_checkpoint_id, human_edited) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, checkpoint.checkpointId)
            ps.setString(2, checkpoint.executionId)
            ps.setString(3, checkpoint.nodeId)
            ps.setInt(4, checkpoint.attempt)
            ps.setString(5, Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), checkpoint.input))
            ps.setString(6, Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), checkpoint.output))
            ps.setLong(7, checkpoint.startedAt)
            ps.setLong(8, checkpoint.completedAt)
            ps.setString(9, checkpoint.feedback)
            ps.setString(10, checkpoint.parentCheckpointId)
            ps.setInt(11, if (checkpoint.humanEdited) 1 else 0)
            ps.executeUpdate()
        }
    }

    override suspend fun getCheckpoints(executionId: String): List<NodeCheckpoint> {
        val sql = "SELECT * FROM checkpoints WHERE execution_id = ? ORDER BY completed_at ASC"
        val out = mutableListOf<NodeCheckpoint>()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, executionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val input = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), rs.getString("input_json"))
                    val output = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), rs.getString("output_json"))
                    out.add(
                        NodeCheckpoint(
                            checkpointId = rs.getString("checkpoint_id"),
                            executionId = rs.getString("execution_id"),
                            nodeId = rs.getString("node_id"),
                            attempt = rs.getInt("attempt"),
                            input = input,
                            output = output,
                            startedAt = rs.getLong("started_at"),
                            completedAt = rs.getLong("completed_at"),
                            feedback = rs.getString("feedback"),
                            parentCheckpointId = rs.getString("parent_checkpoint_id"),
                            humanEdited = rs.getInt("human_edited") == 1,
                        ),
                    )
                }
            }
        }
        return out
    }

    override suspend fun listExecutions(): List<MasteryExecution> {
        val sql = "SELECT execution_id FROM executions ORDER BY created_at DESC"
        val out = mutableListOf<MasteryExecution>()
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val eid = rs.getString("execution_id")
                    getExecution(eid)?.let { out.add(it) }
                }
            }
        }
        return out
    }
}
