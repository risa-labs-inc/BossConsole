package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pid binding on the single-instance descriptor: the endpoint a
 * second launch hands links to — including the `boss://auth` callback carrying
 * live tokens — must belong to the process that published it, or the forward
 * has to stop before a connection is ever opened.
 */
class SingleInstanceDescriptorTrustTest {
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
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    // ==================== Descriptor pid ====================

    @Test
    fun `a published descriptor names the pid of the process that owns the channel`() {
        assertTrue(SingleInstanceManager.acquireLock())

        val descriptor = assertNotNull(readPublishedDescriptor())
        assertEquals(ProcessHandle.current().pid(), descriptor.pid)
        assertEquals(descriptor, parseInstanceDescriptor(descriptor.encode()), "the pid must survive the file format")
    }

    @Test
    fun `a descriptor with an unparseable pid is no descriptor at all`() {
        // pid=abc is not a legacy descriptor missing the field; it is a
        // tampered one, and tamper fails closed rather than downgrading to the
        // pid-less compat path.
        val token = "a".repeat(TOKEN_HEX_LENGTH)
        val text = "version=1\ntransport=TCP\nendpoint=12345\ntoken=$token\npid=abc\n"
        assertNull(parseInstanceDescriptor(text))
    }

    // ==================== Forwarding gate ====================

    @Test
    fun `a descriptor whose recorded owner is dead is forwarded nothing`() {
        // The cheap hijack: plant a descriptor that points at a listener we
        // control, so a second launch hands it the auth deep link.
        val (endpoint, connections, listener) = rogueEndpoint()
        try {
            plantDescriptor(descriptorTo(endpoint, pid = terminatedPid()))

            assertFalse(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
            assertFalse(SingleInstanceManager.sendToExistingInstance("boss://plugin?id=bookmarks"))
            assertEquals(0, connections.get(), "a refused forward must never reach the planted endpoint")
        } finally {
            listener.close()
        }
    }

    @Test
    fun `a descriptor naming a live foreign process is not trusted with an auth link`() {
        // "Pid does not match" without the easy case of a dead one: the process
        // is alive, it is simply not this program.
        val foreign = foreignProcess()
        val (endpoint, connections, listener) = rogueEndpoint()
        try {
            plantDescriptor(descriptorTo(endpoint, pid = foreign.pid()))

            assertFalse(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
            assertEquals(0, connections.get(), "a refused forward must never reach the planted endpoint")
        } finally {
            listener.close()
            foreign.destroyForcibly()
            foreign.waitFor()
        }
    }

    @Test
    fun `a descriptor borrowing a live pid for a foreign-owned endpoint is refused`() {
        // The replay shape: a planter cannot invent a live pid it owns, but it
        // can copy the real one into a descriptor pointing at its own listener.
        // Only asking the OS who actually holds the endpoint catches that.
        val (foreign, port) = foreignTcpListener()
        try {
            plantDescriptor(
                descriptorTo(port.toString(), pid = ProcessHandle.current().pid()),
            )

            assertFalse(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
            assertFalse(SingleInstanceManager.sendToExistingInstance("boss://plugin?id=bookmarks"))
        } finally {
            foreign.destroyForcibly()
            foreign.waitFor()
        }
    }

    @Test
    fun `a descriptor without a pid still carries ordinary links but never auth`() {
        // A descriptor written before pids were recorded is unverifiable, not
        // forged: ordinary links keep working, but a credential-bearing link is
        // refused rather than handed to a peer that cannot be identified.
        assertTrue(SingleInstanceManager.acquireLock())
        val descriptor = assertNotNull(readPublishedDescriptor())
        Files.writeString(descriptorPath(), descriptor.copy(pid = null).encode())

        assertTrue(SingleInstanceManager.sendToExistingInstance("boss://plugin?id=bookmarks"))
        assertFalse(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
    }

    @Test
    fun `a verified descriptor still forwards auth and ordinary links`() {
        assertTrue(SingleInstanceManager.acquireLock())

        assertTrue(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
        assertTrue(SingleInstanceManager.sendToExistingInstance("boss://plugin?id=bookmarks"))
    }

    // ==================== Helpers ====================

    private fun runtimeDirPath(): Path = File(tempDir.toFile(), "run").toPath()

    private fun descriptorPath(): Path = runtimeDirPath().resolve("single-instance")

    private fun readPublishedDescriptor(): InstanceDescriptor? =
        if (Files.exists(descriptorPath())) parseInstanceDescriptor(Files.readString(descriptorPath())) else null

    private fun plantDescriptor(descriptor: InstanceDescriptor) {
        Files.createDirectories(runtimeDirPath())
        Files.writeString(descriptorPath(), descriptor.encode())
    }

    private fun descriptorTo(
        endpoint: String,
        pid: Long,
    ): InstanceDescriptor =
        InstanceDescriptor(
            transport = SingleInstanceTransport.TCP,
            endpoint = endpoint,
            token = "d".repeat(TOKEN_HEX_LENGTH),
            pid = pid,
        )

    /**
     * A listener that plays the hijacker's half: it accepts connections and
     * counts them, standing in for a socket a planted descriptor would point at.
     */
    private fun rogueEndpoint(): Triple<String, AtomicInteger, ServerSocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val port = (server.localAddress as InetSocketAddress).port
        val connections = AtomicInteger(0)
        thread(isDaemon = true, name = "rogue-endpoint") {
            try {
                while (true) {
                    server.accept()?.close()
                    connections.incrementAndGet()
                }
            } catch (_: IOException) {
                // The test closed the listener.
            }
        }
        return Triple(port.toString(), connections, server)
    }

    /**
     * A TCP listener owned by a process that is not this one — what a planted
     * descriptor's endpoint resolves to in a real hijack. Aborts the test where
     * no Python is available to play that role.
     */
    private fun foreignTcpListener(): Pair<Process, Int> {
        val code =
            "import socket,time\n" +
                "s=socket.socket()\n" +
                "s.bind(('127.0.0.1',0))\n" +
                "s.listen(1)\n" +
                "print(s.getsockname()[1],flush=True)\n" +
                "time.sleep(120)\n"
        for (binary in listOf("python3", "python")) {
            val process =
                runCatching {
                    ProcessBuilder(binary, "-c", code).redirectErrorStream(true).start()
                }.getOrNull() ?: continue
            val port =
                process.inputStream
                    .bufferedReader()
                    .readLine()
                    ?.trim()
                    ?.toIntOrNull()
            if (port != null && process.isAlive) {
                return process to port
            }
            process.destroyForcibly()
        }
        throw TestAbortedException("no Python interpreter available for a foreign-owned listener")
    }

    /** A pid that provably has no process behind it. */
    private fun terminatedPid(): Long {
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

        error("Process $pid did not exit within the test timeout")
    }

    /** A live process that is provably not this program. */
    private fun foreignProcess(): Process {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command =
            if (isWindows) {
                listOf("cmd", "/c", "ping", "-n", "60", "127.0.0.1")
            } else {
                listOf("sleep", "60")
            }
        return ProcessBuilder(command).start()
    }
}
