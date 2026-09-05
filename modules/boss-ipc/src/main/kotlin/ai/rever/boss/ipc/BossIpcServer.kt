package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.util.MutableHandlerRegistry
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
 *
 * Services may also be added *after* [start]; they go into a [MutableHandlerRegistry] installed as the
 * fallback registry, so the socket is never touched. This used to stop the running server and rebuild it
 * on the same address, which is destructive in a way that only shows up once something streams:
 * `KernelBootstrap.registerPluginServices()` adds up to fifteen bridges one at a time, and a bidirectional
 * RPC never completes gracefully, so each rebuild burned the full 2s shutdown grace period and then killed
 * every in-flight call — with the address unbound in between. Unary bridges survived that by luck of
 * timing; `PluginUIService.StreamUI`, whose whole job is to stay open, cannot.
 */
class BossIpcServer(
    private val address: String,
    /**
     * When present, every call is run through a [ProcessIdentityInterceptor] backed by this registry,
     * so a service on this server can read a verified caller identity from the gRPC [io.grpc.Context]
     * instead of trusting a request field (BossConsole#53). Null (the default) installs no interceptor
     * and leaves every service's behaviour exactly as it was — most `BossIpcServer` instances (every
     * child process's own `processServer`, every test server) have no use for one.
     */
    private val tokenRegistry: ProcessTokenRegistry? = null,
) {
    private val logger = LoggerFactory.getLogger(BossIpcServer::class.java)
    private val services = mutableListOf<BindableService>()

    /** Services added after the server started. Resolved per-call, so adding one disturbs nothing. */
    private val lateServices = MutableHandlerRegistry()
    private var server: Server? = null

    fun addService(service: BindableService): BossIpcServer {
        if (isRunning) {
            lateServices.addService(service)
            logger.info("Registered service on the running IPC server: {}", service::class.java.simpleName)
        } else {
            services.add(service)
        }
        return this
    }

    fun start(): BossIpcServer {
        buildAndStart()
        return this
    }

    private fun buildAndStart() {
        val builder = IpcAddressResolver.configureServerBuilder(address)
        services.forEach { builder.addService(it) }
        // Consulted only for methods no directly-registered service claims, so build-time registration
        // takes precedence. That is a CHANGE: a rebuild put everything in the primary registry, where the
        // last one added won, so re-adding a service after start used to replace the build-time one and
        // now silently does nothing. Nothing in the tree relies on either behaviour.
        builder.fallbackHandlerRegistry(lateServices)
        tokenRegistry?.let { builder.intercept(ProcessIdentityInterceptor(it)) }
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
        // Cleared with the server, so a stopped-and-restarted instance does not silently resurrect every
        // service registered during its previous lifetime. `services` is deliberately NOT cleared: it is
        // the build-time set, and a restart is expected to bring the same server back up.
        lateServices.services.forEach { lateServices.removeService(it) }
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
