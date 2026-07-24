package ai.rever.boss.ipc

import io.grpc.BindableService
import io.grpc.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * gRPC server wrapper that supports Unix domain sockets (macOS/Linux) and TCP (Windows).
 *
 * Usage:
 * ```kotlin
 * val server = BossIpcServer(address)
 *     .addService(MyServiceImpl())
 *     .start()
 * ```
 */
class BossIpcServer(
    private val address: String,
) {
    private val logger = LoggerFactory.getLogger(BossIpcServer::class.java)
    private val services = mutableListOf<BindableService>()
    private var server: Server? = null

    fun addService(service: BindableService): BossIpcServer {
        services.add(service)
        // If server is already running, rebuild it with the new service
        if (server != null && server?.isShutdown == false) {
            rebuildServer()
        }
        return this
    }

    fun start(): BossIpcServer {
        buildAndStart()
        return this
    }

    /**
     * Rebuild the running server with the current service list.
     * Must stop old server first to release the Unix socket address.
     */
    private fun rebuildServer() {
        val oldServer = server
        // Stop old server FIRST to release the socket address
        oldServer?.let { s ->
            s.shutdown()
            if (!s.awaitTermination(2, TimeUnit.SECONDS)) {
                s.shutdownNow()
            }
        }
        buildAndStart()
        logger.info("IPC server rebuilt with {} services on: {}", services.size, address)
    }

    private fun buildAndStart() {
        val builder = IpcAddressResolver.configureServerBuilder(address)
        services.forEach { builder.addService(it) }
        server = builder.build().start()
        logger.info("IPC server started on: {}", address)
        IpcAddressResolver.secureSocketFile(address)
    }

    fun stop(timeoutMs: Long = 5000) {
        server?.let { s ->
            logger.info("Shutting down IPC server on: {}", address)
            s.shutdown()
            if (!s.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("IPC server did not terminate in {}ms, forcing shutdown", timeoutMs)
                s.shutdownNow()
            }
        }
        IpcAddressResolver.cleanupAddress(address)
        server = null
    }

    fun awaitTermination() {
        server?.awaitTermination()
    }

    val isRunning: Boolean
        get() = server?.isShutdown == false

    val port: Int
        get() = server?.port ?: -1
}
