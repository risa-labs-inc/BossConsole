@file:Suppress("DEPRECATION") // Netty EventLoopGroup constructors deprecated in 4.2.x, required for UDS transport

package ai.rever.boss.ipc

import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves IPC addresses for inter-process communication.
 *
 * On macOS/Linux: Uses Unix domain sockets for zero-overhead local IPC.
 * On Windows: Falls back to TCP localhost.
 *
 * UDS path convention: $BOSS_DATA_DIR/ipc/boss-{type}-{id}.sock
 */
object IpcAddressResolver {

    private val logger = LoggerFactory.getLogger(IpcAddressResolver::class.java)

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    /** Base directory for IPC socket files */
    private val ipcDir: File by lazy {
        val bossDataDir = System.getenv("BOSS_DATA_DIR")
            ?: System.getProperty("boss.data.dir")
            ?: try {
                // Use BossDirectories to respect .boss_debug in dev mode
                val dirsCls = Class.forName("ai.rever.boss.plugin.pathutils.BossDirectories")
                val dirsInst = dirsCls.getDeclaredField("INSTANCE").get(null)
                val rootDir = dirsCls.getMethod("getRootDir").invoke(dirsInst) as File
                rootDir.absolutePath
            } catch (_: Exception) {
                "${System.getProperty("user.home")}/.boss"
            }
        File(bossDataDir, "ipc").also { it.mkdirs() }
    }

    /** TCP port range for Windows fallback */
    private const val TCP_PORT_BASE = 57000
    private const val TCP_PORT_RANGE = 100

    /**
     * Cache of allocated TCP ports per process identity (processType:processId).
     * Once a port is allocated for a given process, the same port is returned on
     * subsequent calls, avoiding TOCTOU races where the port could be taken between
     * discovery and actual bind in the server builder.
     */
    private val tcpPortCache = ConcurrentHashMap<String, Int>()

    /** Regex for valid process identifiers — prevents path traversal in socket names. */
    private val PROCESS_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")

    /**
     * Get the IPC address for a process.
     * Returns a UDS path on macOS/Linux, or TCP localhost address on Windows.
     *
     * @throws IllegalArgumentException if processType or processId contain invalid characters.
     */
    fun resolveAddress(processType: String, processId: String): String {
        require(PROCESS_ID_REGEX.matches(processType)) {
            "Invalid processType '$processType': must match [a-zA-Z0-9._-]+"
        }
        require(PROCESS_ID_REGEX.matches(processId)) {
            "Invalid processId '$processId': must match [a-zA-Z0-9._-]+"
        }
        return if (isWindows) {
            val key = "$processType:$processId"
            val port = tcpPortCache.computeIfAbsent(key) { findAvailableTcpPort() }
            "tcp://localhost:$port"
        } else {
            val socketFile = File(ipcDir, "boss-${processType}-${processId}.sock")
            "unix://${socketFile.absolutePath}"
        }
    }

    /**
     * Get the kernel's IPC address from environment or generate one.
     */
    fun kernelAddress(): String {
        // Check if kernel address is provided (child process scenario)
        System.getenv("BOSS_KERNEL_IPC_ADDR")?.let { return it }

        return resolveAddress("kernel", "main")
    }

    /**
     * Parse an IPC address string into a form usable by gRPC Netty.
     */
    fun parseAddress(address: String): Any {
        return when {
            address.startsWith("unix://") -> {
                val path = address.removePrefix("unix://")
                DomainSocketAddress(path)
            }
            address.startsWith("tcp://") -> {
                val hostPort = address.removePrefix("tcp://")
                val parts = hostPort.split(":")
                InetSocketAddress(parts[0], parts[1].toInt())
            }
            else -> throw IllegalArgumentException("Unknown IPC address format: $address")
        }
    }

    /**
     * Configure a NettyServerBuilder for the given address.
     */
    fun configureServerBuilder(address: String): NettyServerBuilder {
        val parsed = parseAddress(address)
        return when (parsed) {
            is DomainSocketAddress -> {
                // Clean up stale socket file
                File(parsed.path()).delete()

                when {
                    isMacOS -> NettyServerBuilder.forAddress(parsed)
                        .channelType(KQueueServerDomainSocketChannel::class.java)
                        .bossEventLoopGroup(KQueueEventLoopGroup(1))
                        .workerEventLoopGroup(KQueueEventLoopGroup())

                    isLinux -> NettyServerBuilder.forAddress(parsed)
                        .channelType(EpollServerDomainSocketChannel::class.java)
                        .bossEventLoopGroup(EpollEventLoopGroup(1))
                        .workerEventLoopGroup(EpollEventLoopGroup())

                    else -> throw UnsupportedOperationException(
                        "Unix domain sockets not supported on this platform"
                    )
                }
            }
            is InetSocketAddress -> NettyServerBuilder.forAddress(parsed)
            else -> throw IllegalArgumentException("Unknown address type: $parsed")
        }
    }

    /**
     * Configure a NettyChannelBuilder for the given address.
     */
    fun configureChannelBuilder(address: String): NettyChannelBuilder {
        val parsed = parseAddress(address)
        return when (parsed) {
            is DomainSocketAddress -> {
                when {
                    isMacOS -> NettyChannelBuilder.forAddress(parsed)
                        .channelType(KQueueDomainSocketChannel::class.java)
                        .eventLoopGroup(KQueueEventLoopGroup())

                    isLinux -> NettyChannelBuilder.forAddress(parsed)
                        .channelType(EpollDomainSocketChannel::class.java)
                        .eventLoopGroup(EpollEventLoopGroup())

                    else -> throw UnsupportedOperationException(
                        "Unix domain sockets not supported on this platform"
                    )
                }
            }
            is InetSocketAddress -> NettyChannelBuilder.forAddress(parsed)
            else -> throw IllegalArgumentException("Unknown address type: $parsed")
        }
    }

    /**
     * Clean up socket file on shutdown.
     */
    fun cleanupAddress(address: String) {
        if (address.startsWith("unix://")) {
            val path = address.removePrefix("unix://")
            File(path).delete()
        }
    }

    /**
     * Set owner-only (0700) permissions on a Unix domain socket file after the server starts.
     * Prevents other local users from connecting to the IPC socket.
     */
    fun secureSocketFile(address: String) {
        if (isWindows || !address.startsWith("unix://")) return
        val path = address.removePrefix("unix://")
        try {
            val file = File(path)
            if (file.exists()) {
                val ownerOnly = setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                )
                java.nio.file.Files.setPosixFilePermissions(file.toPath(), ownerOnly)
            }
        } catch (e: Exception) {
            // Non-fatal: log but continue. Some filesystems don't support POSIX permissions.
            logger.warn("Could not set socket permissions for {}: {}", path, e.message)
        }
    }

    private fun findAvailableTcpPort(): Int {
        for (port in TCP_PORT_BASE until TCP_PORT_BASE + TCP_PORT_RANGE) {
            try {
                java.net.ServerSocket(port).use { return port }
            } catch (_: Exception) {
                continue
            }
        }
        throw IllegalStateException("No available TCP ports in range $TCP_PORT_BASE-${TCP_PORT_BASE + TCP_PORT_RANGE}")
    }
}
