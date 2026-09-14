package ai.rever.boss.cli

import ai.rever.boss.utils.DeepLinkOrigin
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CLICommandHandlerReadinessQueueTest {
    @Test
    fun `initialization preserves every command variant and external terminal origin`() {
        val queue = ReadinessQueue<CLICommand>()
        val commands =
            listOf(
                CLICommand.OpenUrl("https://example.com"),
                CLICommand.LoadWorkspace("workspace.json"),
                CLICommand.OpenFile("file.txt"),
                CLICommand.OpenFolder("project"),
                CLICommand.OpenTerminal("echo external", DeepLinkOrigin.EXTERNAL),
            )
        commands.forEach { assertFalse(queue.enqueueOrClaimForCaller(it)) }

        assertEquals(commands, queue.markReadyAndClaimQueued())
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
        commands.forEach { assertTrue(queue.enqueueOrClaimForCaller(it)) }
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `queued file values are claimed once in FIFO order when readiness wins`() {
        val queue = ReadinessQueue<String>()

        assertFalse(queue.enqueueOrClaimForCaller("first.txt"))
        assertFalse(queue.enqueueOrClaimForCaller("second.txt"))

        assertEquals(listOf("first.txt", "second.txt"), queue.markReadyAndClaimQueued())
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `a ready workspace queue transfers ownership to the submitting caller`() {
        val queue = ReadinessQueue<String>()

        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
        assertTrue(queue.enqueueOrClaimForCaller("workspace.json"))
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `repeat readiness does not claim file values twice`() {
        val queue = ReadinessQueue<String>()

        assertFalse(queue.enqueueOrClaimForCaller("only-once.txt"))

        assertEquals(listOf("only-once.txt"), queue.markReadyAndClaimQueued())
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `enqueue then readiness gives readiness ownership of the terminal value`() {
        val queue = ReadinessQueue<CLICommand.OpenTerminal>()
        val terminal = CLICommand.OpenTerminal("echo ready", DeepLinkOrigin.OPERATOR_CLI)

        assertFalse(queue.enqueueOrClaimForCaller(terminal))
        assertEquals(listOf(terminal), queue.markReadyAndClaimQueued())
    }

    @Test
    fun `readiness then enqueue gives caller ownership of the terminal value`() {
        val queue = ReadinessQueue<CLICommand.OpenTerminal>()
        val terminal = CLICommand.OpenTerminal("echo ready", DeepLinkOrigin.OPERATOR_CLI)

        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
        assertTrue(queue.enqueueOrClaimForCaller(terminal))
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `terminal payload is preserved through deferred ownership transfer`() {
        val queue = ReadinessQueue<CLICommand.OpenTerminal>()
        val terminal = CLICommand.OpenTerminal("echo preserved", DeepLinkOrigin.EXTERNAL)

        assertFalse(queue.enqueueOrClaimForCaller(terminal))

        assertEquals(listOf(terminal), queue.markReadyAndClaimQueued())
    }

    @Test
    fun `concurrent producers and readiness assign every value to exactly one owner`() {
        val queue = ReadinessQueue<Int>()
        val values = 0 until 16
        val start = CyclicBarrier(values.count() + 1)
        val executor = Executors.newFixedThreadPool(values.count() + 1)

        try {
            val producerResults =
                values.map { value ->
                    executor.submit<Pair<Int, Boolean>> {
                        start.await(5, TimeUnit.SECONDS)
                        value to queue.enqueueOrClaimForCaller(value)
                    }
                }
            val readinessResult =
                executor.submit<List<Int>> {
                    start.await(5, TimeUnit.SECONDS)
                    queue.markReadyAndClaimQueued()
                }

            val directValues =
                producerResults
                    .map { it.get(5, TimeUnit.SECONDS) }
                    .filter { (_, callerOwns) -> callerOwns }
                    .map { (value, _) -> value }
            val readinessValues = readinessResult.get(5, TimeUnit.SECONDS)
            val allOwnedValues = directValues + readinessValues

            assertEquals(values.toSet(), allOwnedValues.toSet())
            assertEquals(values.count(), allOwnedValues.size)
            assertTrue(queue.markReadyAndClaimQueued().isEmpty())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent readiness calls claim a deferred batch only once`() {
        val queue = ReadinessQueue<Int>()
        val values = listOf(1, 2, 3)
        values.forEach { assertFalse(queue.enqueueOrClaimForCaller(it)) }
        val start = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val batches =
                List(2) {
                    executor.submit<List<Int>> {
                        start.await(5, TimeUnit.SECONDS)
                        queue.markReadyAndClaimQueued()
                    }
                }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(values, batches.flatten())
            assertEquals(1, batches.count { it.isNotEmpty() })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
