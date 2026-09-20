package ai.rever.boss.mastery

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

class SqlExecutionStoreTest {
    private lateinit var dbFile: File
    private lateinit var store: SqlExecutionStore

    @BeforeTest
    fun setup() {
        dbFile = File("build/tmp/test-mastery-store.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile.mkdirs()
        store = SqlExecutionStore(dbFile.absolutePath)
    }

    @AfterTest
    fun teardown() {
        try {
            if (dbFile.exists()) dbFile.delete()
        } catch (e: Exception) {
        }
    }

    @Test
    fun `create and retrieve execution and checkpoints`() = runBlocking {
        val exec = MasteryExecution("exec-1", "mastery-1", mapOf("k" to "v"), "RUNNING")
        store.createExecution(exec)

        val fromDb = store.getExecution("exec-1")
        assertEquals(exec.executionId, fromDb?.executionId)
        assertEquals(exec.masteryId, fromDb?.masteryId)

        val checkpoint = NodeCheckpoint(
            checkpointId = "cp-1",
            executionId = "exec-1",
            nodeId = "node-1",
            attempt = 1,
            input = mapOf("in" to "1"),
            output = mapOf("out" to "ok"),
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
        )

        store.saveCheckpoint(checkpoint)

        val cps = store.getCheckpoints("exec-1")
        assertEquals(1, cps.size)
        assertEquals("cp-1", cps[0].checkpointId)
        assertEquals("ok", cps[0].output["out"])
    }
}
