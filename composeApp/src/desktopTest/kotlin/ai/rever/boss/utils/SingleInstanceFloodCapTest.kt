package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val READ_TIMED_OUT = Int.MIN_VALUE
private typealias DummyServerFixture = Triple<ServerSocket, Thread, ConcurrentHashMap<String, AtomicInteger>>

/**
 * Regression test for issue #1326:
 * `SingleInstanceManager.handleClient` spawned one daemon thread per accepted connection
 * without bounding in-flight handlers, allowing a local flood to park an unbounded number
 * of short-lived daemon threads.
 *
 * The fix gates `handleClient` behind a semaphore bounded by `MAX_CLIENT_HANDLERS` (32).
 * Any connection accepted when all 32 handler slots are occupied is answered with `BUSY`
 * and immediately closed without spawning a thread.
 */
class SingleInstanceFloodCapTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun useTempRuntimeDir() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun releaseChannel() {
        SingleInstanceManager.release()
        SingleInstanceManager.watchdogSchedulerOverride = null
        SingleInstanceManager.connectionBudgetMsOverride = null
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
        SingleInstanceManager.acceptNextClientOverride = null
        SingleInstanceManager.beforeTeardownFaultedListenerForTest = null
        waitForSlotsRecovery()
        assertEquals(
            MAX_CLIENT_HANDLERS,
            SingleInstanceManager.availableClientSlots,
            "All client handler slots must be recovered after test",
        )
    }

    @Test
    fun `handleClient is bounded so a local flood cannot spawn unbounded threads`() {
        SingleInstanceManager.connectionBudgetMsOverride = 30_000L
        assertTrue(SingleInstanceManager.acquireLock(), "SingleInstanceManager failed to bind")
        val descriptor = assertNotNull(readPublishedDescriptor(), "Published descriptor must exist")
        val targetAddress = toSocketAddress(descriptor)

        val parkedThreadsBefore = parkedThreadCount()
        val heldSockets = mutableListOf<SocketChannel>()
        try {
            repeat(MAX_CLIENT_HANDLERS) {
                heldSockets += SocketChannel.open(targetAddress)
            }

            waitForSlotsExhaustion()
            assertEquals(0, SingleInstanceManager.availableClientSlots, "All client slots should be occupied")

            assertOverflowConnectionsRejected(targetAddress, count = 5)

            // Saturated instance must preserve single-instance ownership against a second launch
            val secondAcquire = SingleInstanceManager.acquireLock()
            assertFalse(secondAcquire, "Second acquireLock must fail while first host is saturated")
            val currentDescriptor = readPublishedDescriptor()
            assertNotNull(currentDescriptor)
            assertEquals(descriptor.endpoint, currentDescriptor.endpoint, "Endpoint must stay with first host")
            assertEquals(descriptor.pid, currentDescriptor.pid, "PID must stay with first host")
            assertEquals(descriptor.token, currentDescriptor.token, "Token must stay with first host")

            val parkedThreadsAfter = parkedThreadCount()
            val spawned = parkedThreadsAfter - parkedThreadsBefore
            assertTrue(
                spawned <= MAX_CLIENT_HANDLERS,
                "Flood parked $spawned handler threads; expected <= $MAX_CLIENT_HANDLERS",
            )
        } finally {
            heldSockets.forEach { runCatching { it.close() } }
        }

        waitForSlotsRecovery()
        assertEquals(
            MAX_CLIENT_HANDLERS,
            SingleInstanceManager.availableClientSlots,
            "Client handler slots must fully recover after connections close",
        )

        val ping = formatPingRequest(descriptor.token)
        val pong = SingleInstanceWire.exchange(descriptor, ping)
        assertEquals(RESPONSE_PONG, pong, "Server must respond with PONG after slots recovery")
    }

    @Test
    fun `failing watchdog setup does not leak socket or client slots`() {
        val failingScheduler = newFailingScheduler()
        SingleInstanceManager.watchdogSchedulerOverride = failingScheduler
        try {
            assertTrue(SingleInstanceManager.acquireLock(), "Failed to bind")
            val descriptor = assertNotNull(readPublishedDescriptor(), "Published descriptor must exist")
            val targetAddress = toSocketAddress(descriptor)

            val ch = SocketChannel.open(targetAddress)
            ch.configureBlocking(false)
            val buf = ByteBuffer.allocate(16)
            val readBytes = readWithTimeout(ch, buf, timeoutMs = 2000L)
            assertEquals(-1, readBytes, "Socket must be closed when watchdog setup fails")
            ch.close()

            waitForSlotsRecovery()
            assertEquals(
                MAX_CLIENT_HANDLERS,
                SingleInstanceManager.availableClientSlots,
                "Slots must recover even if watchdog setup throws",
            )
        } finally {
            failingScheduler.shutdownNow()
            SingleInstanceManager.watchdogSchedulerOverride = null
        }
    }

    @Test
    fun `accepted connection with empty partial or rejected reply preserves verified ownership`() {
        val fixture = AtomicReference("EMPTY")
        val (server, acceptThread, servedCounts) = startDummyServer { fixture.get() }
        val dummyPort = server.localPort

        try {
            for (currentFixture in listOf("EMPTY", "PARTIAL", "REJECTED", "UNREAD_RST")) {
                assertVerifiedFixturePreservesOwnership(currentFixture, dummyPort, fixture, servedCounts)
            }
        } finally {
            runCatching { server.close() }
            acceptThread.join(2000L)
            assertFalse(acceptThread.isAlive, "Dummy server accept thread must terminate")
        }
    }

    private fun assertVerifiedFixturePreservesOwnership(
        currentFixture: String,
        dummyPort: Int,
        fixture: AtomicReference<String>,
        servedCounts: ConcurrentHashMap<String, AtomicInteger>,
    ) {
        val servedBefore = servedCounts[currentFixture]?.get() ?: 0
        fixture.set(currentFixture)
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = dummyPort.toString(),
                token = newChannelToken(),
                pid = ProcessHandle.current().pid(),
            )

        val probe = SingleInstanceWire.probeInstance(descriptor)
        assertEquals(
            SingleInstanceProbe.CONNECTED_NO_REPLY,
            probe,
            "Fixture $currentFixture must probe as CONNECTED_NO_REPLY",
        )

        val wireReply = SingleInstanceWire.exchange(descriptor, "PING")
        val expectedWireReply =
            when (currentFixture) {
                "EMPTY" -> null
                "PARTIAL" -> "BUS"
                "REJECTED" -> "REJECTED"
                else -> null
            }
        assertEquals(
            expectedWireReply,
            wireReply,
            "Fixture $currentFixture must return expected wire response",
        )

        Files.createDirectories(descriptorPath().parent)
        Files.writeString(descriptorPath(), descriptor.encode())
        assertTrue(
            SingleInstanceManager.isAnotherInstanceRunning(),
            "Verified descriptor with fixture $currentFixture must report another instance running",
        )
        val acquired = SingleInstanceManager.acquireLock()
        assertFalse(
            acquired,
            "Must not reclaim verified descriptor from active endpoint with fixture $currentFixture",
        )
        val published = readPublishedDescriptor()
        assertNotNull(published)
        assertEquals(descriptor.token, published.token, "Descriptor token must remain unchanged")

        val servedAfter = servedCounts[currentFixture]?.get() ?: 0
        assertTrue(
            servedAfter > servedBefore,
            "Fixture $currentFixture must have been served by the dummy server",
        )
    }

    @Test
    fun `unverified descriptor with unresponsive or rejected endpoint is reclaimed instead of bricked startup`() {
        val fixture = AtomicReference("EMPTY")
        val (server, acceptThread, servedCounts) = startDummyServer { fixture.get() }
        val dummyPort = server.localPort

        try {
            for (currentFixture in listOf("EMPTY", "PARTIAL", "REJECTED")) {
                val servedBefore = servedCounts[currentFixture]?.get() ?: 0
                fixture.set(currentFixture)
                val unverifiedDescriptor =
                    InstanceDescriptor(
                        transport = SingleInstanceTransport.TCP,
                        endpoint = dummyPort.toString(),
                        token = newChannelToken(),
                        pid = null,
                    )

                val probe = SingleInstanceWire.probeInstance(unverifiedDescriptor)
                assertEquals(
                    SingleInstanceProbe.CONNECTED_NO_REPLY,
                    probe,
                    "Fixture $currentFixture must probe as CONNECTED_NO_REPLY",
                )

                val wireReply = SingleInstanceWire.exchange(unverifiedDescriptor, "PING")
                val expectedWireReply =
                    when (currentFixture) {
                        "EMPTY" -> null
                        "PARTIAL" -> "BUS"
                        "REJECTED" -> "REJECTED"
                        else -> null
                    }
                assertEquals(
                    expectedWireReply,
                    wireReply,
                    "Fixture $currentFixture must return expected wire response",
                )

                Files.createDirectories(descriptorPath().parent)
                Files.writeString(descriptorPath(), unverifiedDescriptor.encode())

                assertFalse(
                    SingleInstanceManager.isAnotherInstanceRunning(),
                    "Unverified descriptor with fixture $currentFixture must not be treated as a live instance",
                )
                val acquired = SingleInstanceManager.acquireLock()
                assertTrue(
                    acquired,
                    "Must reclaim unverified descriptor for fixture $currentFixture to prevent bricked startup",
                )

                SingleInstanceManager.release()

                val servedAfter = servedCounts[currentFixture]?.get() ?: 0
                assertTrue(
                    servedAfter > servedBefore,
                    "Fixture $currentFixture must have been served by the dummy server",
                )
            }
        } finally {
            runCatching { server.close() }
            acceptThread.join(2000L)
            assertFalse(acceptThread.isAlive, "Dummy server accept thread must terminate")
        }
    }

    private fun startDummyServer(fixtureProvider: () -> String): DummyServerFixture {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val servedCounts = ConcurrentHashMap<String, AtomicInteger>()
        val acceptThread =
            kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: IOException) {
                            break
                        }
                    try {
                        client.use {
                            client.soTimeout = 2000
                            val currentFixture = fixtureProvider()
                            servedCounts.computeIfAbsent(currentFixture) { AtomicInteger() }.incrementAndGet()
                            when (currentFixture) {
                                "EMPTY" -> {
                                    client.getInputStream().bufferedReader().readLine()
                                }

                                "PARTIAL" -> {
                                    client.getInputStream().bufferedReader().readLine()
                                    val out = client.getOutputStream()
                                    out.write("BUS".toByteArray(StandardCharsets.UTF_8))
                                    out.flush()
                                }

                                "REJECTED" -> {
                                    client.getInputStream().bufferedReader().readLine()
                                    val out = client.getOutputStream()
                                    out.write("REJECTED\n".toByteArray(StandardCharsets.UTF_8))
                                    out.flush()
                                }

                                "UNREAD_RST" -> {
                                    // Intentionally left unread; closed by client.use
                                }

                                else -> {
                                    // Closed by client.use
                                }
                            }
                        }
                    } catch (_: IOException) {
                        // Individual client communication error does not terminate the accept loop
                    }
                }
            }
        return Triple(server, acceptThread, servedCounts)
    }

    private fun startQueueDummyServer(
        server: ServerSocket,
        queue: LinkedBlockingQueue<Socket>,
    ): Thread =
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    client.soTimeout = 2000
                    queue.put(client)
                } catch (_: IOException) {
                    break
                }
            }
        }

    @Test
    fun `probeInstance and exchange under failing watchdog scheduler do not leak socket or throw`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val dummyPort = server.localPort
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = dummyPort.toString(),
                token = newChannelToken(),
                pid = ProcessHandle.current().pid(),
            )

        val acceptedQueue = LinkedBlockingQueue<Socket>()
        val acceptThread = startQueueDummyServer(server, acceptedQueue)

        val failingScheduler = newFailingScheduler()
        try {
            SingleInstanceManager.watchdogSchedulerOverride = failingScheduler

            val probe = SingleInstanceWire.probeInstance(descriptor)
            assertEquals(
                SingleInstanceProbe.CONNECTED_NO_REPLY,
                probe,
                "probeInstance must return CONNECTED_NO_REPLY when watchdog scheduling fails",
            )
            val probeSocket = acceptedQueue.poll(3000L, TimeUnit.MILLISECONDS)
            assertNotNull(probeSocket, "Server must have accepted probe connection")
            assertClientSocketClosed(
                probeSocket,
                "Client socket must be closed by probeInstance on rejected watchdog setup",
            )

            val reply = SingleInstanceWire.exchange(descriptor, formatPingRequest(descriptor.token))
            assertEquals(null, reply, "exchange must return null without throwing when watchdog scheduling fails")
            val exchangeSocket = acceptedQueue.poll(3000L, TimeUnit.MILLISECONDS)
            assertNotNull(exchangeSocket, "Server must have accepted exchange connection")
            assertClientSocketClosed(
                exchangeSocket,
                "Client socket must be closed by exchange on rejected watchdog setup",
            )

            Files.createDirectories(descriptorPath().parent)
            Files.writeString(descriptorPath(), descriptor.encode())
            val acquired = SingleInstanceManager.acquireLock()
            assertFalse(acquired, "acquireLock must return false safely when watchdog scheduling is rejected")
        } finally {
            failingScheduler.shutdownNow()
            SingleInstanceManager.watchdogSchedulerOverride = null
            runCatching { server.close() }
            while (acceptedQueue.isNotEmpty()) {
                runCatching { acceptedQueue.poll()?.close() }
            }
            acceptThread.join(2000L)
            assertFalse(acceptThread.isAlive, "Accept thread must terminate")
        }
    }

    @Test
    fun `faulted accept loop tears down endpoint so subsequent launch reclaims cleanly`() {
        assertTrue(SingleInstanceManager.acquireLock(), "First launch must acquire lock")
        val descriptor = assertNotNull(readPublishedDescriptor(), "Descriptor must exist")
        val targetAddress = toSocketAddress(descriptor)

        // Inject accept failure while listening is true and channel is open
        SingleInstanceManager.acceptNextClientOverride = { _, _ -> null }

        // Wake up the accept loop by initiating a connection
        runCatching { SocketChannel.open(targetAddress).close() }

        // Wait for listener thread to detect accept-null, finish teardown, and terminate
        val deadline = System.currentTimeMillis() + 3000L
        while ((SingleInstanceManager.isListening || SingleInstanceManager.isListenerAlive) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertFalse(SingleInstanceManager.isListening, "Listener must stop listening upon unexpected accept failure")
        assertFalse(SingleInstanceManager.isListenerAlive, "Listener thread must terminate upon accept failure")

        waitForSlotsRecovery()
        assertEquals(
            MAX_CLIENT_HANDLERS,
            SingleInstanceManager.availableClientSlots,
            "Client handler slots must remain fully available during and after listener teardown",
        )

        // Descriptor must be withdrawn and channel closed
        assertNull(readPublishedDescriptor(), "Faulted endpoint descriptor must be withdrawn")

        // Reset the override to simulate next instance launch
        SingleInstanceManager.acceptNextClientOverride = null

        // Second launch must now cleanly acquire lock without hanging or being bricked
        val secondAcquired = SingleInstanceManager.acquireLock()
        assertTrue(secondAcquired, "Next launch must reclaim and acquire lock cleanly after listener fault")
    }

    private fun newFailingScheduler(): ScheduledExecutorService =
        object : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
            override fun schedule(
                command: Runnable,
                delay: Long,
                unit: TimeUnit,
            ): ScheduledFuture<*> = throw RejectedExecutionException("Watchdog scheduling rejected")
        }

    private fun assertClientSocketClosed(
        socket: Socket,
        message: String,
    ) {
        socket.soTimeout = 2000
        val eof = socket.getInputStream().read()
        assertEquals(-1, eof, message)
        socket.close()
    }

    private fun waitForSlotsExhaustion() {
        val deadline = System.currentTimeMillis() + 5000L
        while (SingleInstanceManager.availableClientSlots > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun waitForSlotsRecovery() {
        val deadline = System.currentTimeMillis() + 5000L
        while (SingleInstanceManager.availableClientSlots < MAX_CLIENT_HANDLERS &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
    }

    private fun assertOverflowConnectionsRejected(
        targetAddress: SocketAddress,
        count: Int,
    ) {
        val overflowSockets = mutableListOf<SocketChannel>()
        try {
            repeat(count) {
                try {
                    val ch = SocketChannel.open(targetAddress)
                    ch.configureBlocking(false)
                    overflowSockets += ch
                } catch (_: IOException) {
                    // Rejected synchronously at transport layer
                }
            }

            overflowSockets.forEach { ch ->
                val buf = ByteBuffer.allocate(32)
                val readBytes = runCatching { readWithTimeout(ch, buf) }.getOrDefault(-1)
                assertTrue(readBytes > 0, "Must read BUSY response bytes before connection is closed")
                val msg = String(buf.array(), 0, readBytes, StandardCharsets.UTF_8).trim()
                assertEquals(RESPONSE_BUSY, msg, "Overflow connection must receive BUSY")
            }
        } finally {
            overflowSockets.forEach { runCatching { it.close() } }
        }
    }

    private fun readWithTimeout(
        ch: SocketChannel,
        buf: ByteBuffer,
        timeoutMs: Long = 2000L,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var total = 0
        var done = false
        while (buf.hasRemaining() && !done && System.currentTimeMillis() < deadline) {
            val r = ch.read(buf)
            if (r < 0) {
                done = true
            } else if (r > 0) {
                total += r
                val bytes = buf.array()
                if ((0 until buf.position()).any { bytes[it] == '\n'.code.toByte() }) {
                    done = true
                }
            } else {
                Thread.sleep(10)
            }
        }
        return when {
            total > 0 -> total
            done -> -1
            else -> READ_TIMED_OUT
        }
    }

    private fun toSocketAddress(descriptor: InstanceDescriptor): SocketAddress =
        when (descriptor.transport) {
            SingleInstanceTransport.UNIX -> {
                UnixDomainSocketAddress.of(descriptor.endpoint)
            }

            SingleInstanceTransport.TCP -> {
                InetSocketAddress(InetAddress.getLoopbackAddress(), descriptor.endpoint.toInt())
            }
        }

    private fun parkedThreadCount(): Int {
        val threads = Thread.getAllStackTraces().keys
        return threads.count { it.isAlive && it.name == "BOSS-IPC-Client-Handler" }
    }

    private fun descriptorPath(): Path = File(tempDir.toFile(), "run").toPath().resolve("single-instance")

    private fun readPublishedDescriptor(): InstanceDescriptor? {
        val path = descriptorPath()
        return if (Files.exists(path)) {
            parseInstanceDescriptor(Files.readString(path))
        } else {
            null
        }
    }
}
