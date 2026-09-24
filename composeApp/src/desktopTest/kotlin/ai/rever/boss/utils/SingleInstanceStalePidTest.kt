package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies PID tracking and instant recovery from stale single-instance descriptors left
 * by crashed or terminated processes.
 */
class SingleInstanceStalePidTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    /**
     * Spawns a short-lived process that exits immediately and waits until the child
     * process is observed as no longer alive.
     */
    private fun getTerminatedPid(): Long {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (isWindows) listOf("cmd", "/c", "exit 0") else listOf("true")
        val process = ProcessBuilder(command).start()

        process.waitFor()
        val pid = process.pid()

        repeat(50) {
            if (!isProcessAlive(pid)) {
                return pid
            }
            Thread.sleep(10)
        }

        error("Process $pid did not become dead within the test timeout")
    }

    @Test
    fun `descriptor with pid round trips through encoding and parsing`() {
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "56789",
                token = "a".repeat(TOKEN_HEX_LENGTH),
                pid = 424242L,
            )

        val encoded = descriptor.encode()
        assertTrue(encoded.contains("pid=424242"))

        val parsed = parseInstanceDescriptor(encoded)
        assertNotNull(parsed)
        assertEquals(descriptor, parsed)
        assertEquals(424242L, parsed.pid)
    }

    @Test
    fun `descriptor without pid still parses cleanly with null pid`() {
        val token = "b".repeat(TOKEN_HEX_LENGTH)
        val legacyText = "version=1\ntransport=TCP\nendpoint=56789\ntoken=$token"

        val parsed = parseInstanceDescriptor(legacyText)
        assertNotNull(parsed)
        assertNull(parsed.pid)
        assertEquals(SingleInstanceTransport.TCP, parsed.transport)
    }

    @Test
    fun `non-positive and non-numeric pid values parse as null pid`() {
        val token = "c".repeat(TOKEN_HEX_LENGTH)

        val negativePidText = "version=1\ntransport=TCP\nendpoint=56789\ntoken=$token\npid=-1"
        val parsedNegative = parseInstanceDescriptor(negativePidText)
        assertNotNull(parsedNegative)
        assertNull(parsedNegative.pid, "Negative PID must parse as null")

        val zeroPidText = "version=1\ntransport=TCP\nendpoint=56789\ntoken=$token\npid=0"
        val parsedZero = parseInstanceDescriptor(zeroPidText)
        assertNotNull(parsedZero)
        assertNull(parsedZero.pid, "Zero PID must parse as null")

        val alphaPidText = "version=1\ntransport=TCP\nendpoint=56789\ntoken=$token\npid=abc123"
        val parsedAlpha = parseInstanceDescriptor(alphaPidText)
        assertNotNull(parsedAlpha)
        assertNull(parsedAlpha.pid, "Non-numeric PID must parse as null")
    }

    @Test
    fun `isProcessAlive correctly identifies current process and dead pids`() {
        val currentPid = ProcessHandle.current().pid()
        assertTrue(isProcessAlive(currentPid), "Current JVM process must be alive")

        val deadPid = getTerminatedPid()
        assertFalse(isProcessAlive(deadPid), "Terminated process PID should not be alive")

        assertTrue(isProcessAlive(0), "Non-positive PID 0 must return true to prevent false dead verdict")
        assertTrue(isProcessAlive(-1), "Negative PID -1 must return true to prevent false dead verdict")
    }

    @Test
    fun `isAnotherInstanceRunning immediately returns false for dead pid without network ping`() {
        val deadPid = getTerminatedPid()
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            serverSocket.soTimeout = 50
            val staleDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = "d".repeat(TOKEN_HEX_LENGTH),
                    pid = deadPid,
                )

            writeDescriptor(staleDescriptor)

            // Must return false immediately because PID is known dead
            val running = SingleInstanceManager.isAnotherInstanceRunning()
            assertFalse(running, "Should not detect running instance when PID is dead")

            // Verify that no socket connection or ping was attempted against the endpoint
            assertThrows<SocketTimeoutException> {
                serverSocket.accept()
            }
        }
    }

    @Test
    fun `acquireLock immediately reclaims stale descriptor with dead pid`() {
        val deadPid = getTerminatedPid()
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            serverSocket.soTimeout = 50
            val staleDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = "e".repeat(TOKEN_HEX_LENGTH),
                    pid = deadPid,
                )

            writeDescriptor(staleDescriptor)

            // Attempting to acquire lock should instantly succeed by reclaiming the stale descriptor
            val acquired = SingleInstanceManager.acquireLock()
            assertTrue(acquired, "acquireLock must succeed by reclaiming stale descriptor from dead process")

            // Verify that no socket connection or ping was attempted before reclaiming
            assertThrows<SocketTimeoutException> {
                serverSocket.accept()
            }

            val published = readPublishedDescriptor()
            assertNotNull(published)
            assertEquals(ProcessHandle.current().pid(), published.pid)
        }
    }

    @Test
    fun `live pid with non-answering endpoint still reclaims via ping fallback`() {
        val currentPid = ProcessHandle.current().pid()
        val deadPort = ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { it.localPort }
        val staleDescriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = deadPort.toString(),
                token = "f".repeat(TOKEN_HEX_LENGTH),
                pid = currentPid,
            )

        writeDescriptor(staleDescriptor)

        // Live PID must not be taken as proof the instance is running; fallback ping fails
        assertFalse(
            SingleInstanceManager.isAnotherInstanceRunning(),
            "isAnotherInstanceRunning must return false when live PID's endpoint does not answer",
        )

        // acquireLock must reclaim the stale descriptor because ping fails
        val acquired = SingleInstanceManager.acquireLock()
        assertTrue(
            acquired,
            "acquireLock must reclaim stale descriptor with live PID when ping fails",
        )

        val published = readPublishedDescriptor()
        assertNotNull(published)
        assertEquals(currentPid, published.pid)
    }

    @Test
    fun `live pid with answering endpoint is detected as running and not reclaimed`() {
        val currentPid = ProcessHandle.current().pid()
        val token = "g".repeat(TOKEN_HEX_LENGTH)
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            val liveDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = token,
                    pid = currentPid,
                )

            writeDescriptor(liveDescriptor)
            val serverThread = startFakePingServer(serverSocket)

            try {
                assertTrue(
                    SingleInstanceManager.isAnotherInstanceRunning(),
                    "isAnotherInstanceRunning must return true for responding live instance",
                )

                val acquired = SingleInstanceManager.acquireLock()
                assertFalse(
                    acquired,
                    "acquireLock must not reclaim responding live instance",
                )

                val currentDescriptor = readPublishedDescriptor()
                assertNotNull(currentDescriptor)
                assertEquals(token, currentDescriptor.token)
                assertEquals(currentPid, currentDescriptor.pid)
            } finally {
                serverSocket.close()
                serverThread.join(1000)
            }
        }
    }

    @Test
    fun `legacy descriptor with null pid falls back to ping and prevents lock reclamation when endpoint responds`() {
        val token = "h".repeat(TOKEN_HEX_LENGTH)
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            val legacyDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = token,
                    pid = null,
                )

            writeDescriptor(legacyDescriptor)

            val serverThread = startFakePingServer(serverSocket)

            try {
                // 1. pid == null
                assertNull(legacyDescriptor.pid, "Legacy descriptor must have null pid")

                // 2. Legacy descriptor without PID must fall back to ping and detect the running instance
                val running = SingleInstanceManager.isAnotherInstanceRunning()
                assertTrue(running, "isAnotherInstanceRunning must return true for responding legacy instance")

                // 3. acquireLock must not reclaim a legacy descriptor whose endpoint is responding
                val acquired = SingleInstanceManager.acquireLock()
                assertFalse(acquired, "acquireLock must return false and not reclaim responding legacy instance")

                // 4. Verify the descriptor was not overwritten or reclaimed
                val currentDescriptor = readPublishedDescriptor()
                assertNotNull(currentDescriptor)
                assertNull(currentDescriptor.pid, "Descriptor pid must remain null")
                assertEquals(legacyDescriptor.endpoint, currentDescriptor.endpoint)
                assertEquals(legacyDescriptor.token, currentDescriptor.token)
            } finally {
                serverSocket.close()
                serverThread.join(1000)
            }
        }
    }

    @Test
    fun `reloadDevPlugin returns HostOffline immediately for dead pid without exchange timeout`() {
        val deadPid = getTerminatedPid()
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            serverSocket.soTimeout = 50
            val staleDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = "i".repeat(TOKEN_HEX_LENGTH),
                    pid = deadPid,
                )

            writeDescriptor(staleDescriptor)

            val result = SingleInstanceManager.reloadDevPlugin("sample-tool")
            assertIs<ReloadResult.HostOffline>(result)
            assertTrue(
                result.message.contains("instance process is not running"),
                "Message should state instance process is not running: ${result.message}",
            )

            // Verify that no socket connection or exchange was attempted against the endpoint
            assertThrows<SocketTimeoutException> {
                serverSocket.accept()
            }
        }
    }

    @Test
    fun `symlink descriptor falls back to ping and is not reclaimed when answering`() {
        val currentPid = ProcessHandle.current().pid()
        val token = "j".repeat(TOKEN_HEX_LENGTH)
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { serverSocket ->
            val liveDescriptor =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.TCP,
                    endpoint = serverSocket.localPort.toString(),
                    token = token,
                    pid = currentPid,
                )

            val realTarget = File(tempDir.toFile(), "other-instance").toPath()
            Files.writeString(realTarget, liveDescriptor.encode())
            Files.createDirectories(runtimeDirPath())
            Files.deleteIfExists(descriptorPath())

            try {
                Files.createSymbolicLink(descriptorPath(), realTarget)
            } catch (_: Exception) {
                // Symlink creation may require elevation on Windows; skip if not supported
                return
            }

            val serverThread = startFakePingServer(serverSocket)
            try {
                // Symlink means isOwnedByCurrentUser returns false, preventing dead-PID assumption
                val acquired = SingleInstanceManager.acquireLock()
                assertFalse(acquired, "acquireLock must not reclaim when descriptor is symlinked and answering")
            } finally {
                serverSocket.close()
                serverThread.join(1000)
            }
        }
    }

    // ==================== Helpers ====================

    private fun runtimeDirPath(): Path = File(tempDir.toFile(), "run").toPath()

    private fun descriptorPath(): Path = runtimeDirPath().resolve("single-instance")

    private fun readPublishedDescriptor(): InstanceDescriptor? =
        if (Files.exists(descriptorPath())) parseInstanceDescriptor(Files.readString(descriptorPath())) else null

    private fun writeDescriptor(descriptor: InstanceDescriptor) {
        Files.createDirectories(runtimeDirPath())
        Files.writeString(descriptorPath(), descriptor.encode())
    }

    private fun startFakePingServer(serverSocket: ServerSocket): Thread =
        thread(isDaemon = true) {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    handlePingClient(client)
                }
            } catch (_: IOException) {
                // Socket closed during test cleanup
            }
        }

    private fun handlePingClient(client: Socket) {
        thread(isDaemon = true) {
            client.use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine()
                if (line != null && line.endsWith(VERB_PING)) {
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("$RESPONSE_PONG\n")
                    writer.flush()
                }
            }
        }
    }
}
