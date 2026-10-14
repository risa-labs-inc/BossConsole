package ai.rever.boss.logging

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogNotificationQueueTest {
    private fun entry(message: String) = LogEntry(0, message, LogSource.STDOUT)

    @Test
    fun `overflow retains exactly the newest entries in order`() {
        val queue = LogNotificationQueue(3)
        repeat(10) { queue.append(entry("$it")) }
        assertEquals(listOf("7", "8", "9"), List(3) { queue.take().message })
    }

    @Test
    fun `concurrent producers cannot lose the final append or exceed the bound`() {
        val queue = LogNotificationQueue(3)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(4) { producer -> workers.submit { repeat(1000) { queue.append(entry("$producer:$it")) } } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            queue.append(entry("last"))
            val reader = workers.submit<List<String>> { List(3) { queue.take().message } }
            assertEquals("last", reader.get(10, TimeUnit.SECONDS).last())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `waiting consumer wakes and clear discards old entries`() {
        val queue = LogNotificationQueue(3)
        queue.append(entry("old"))
        queue.clear()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<LogEntry> { queue.take() }
            queue.append(entry("new"))
            assertEquals("new", read.get(10, TimeUnit.SECONDS).message)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
