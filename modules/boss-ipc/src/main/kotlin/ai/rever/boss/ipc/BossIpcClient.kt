package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import io.grpc.ClientInterceptor
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * gRPC client wrapper with automatic reconnection and exponential backoff.
 *
 * Supports Unix domain sockets (macOS/Linux) and TCP (Windows).
 *
 * Usage:
 * ```kotlin
 * val client = BossIpcClient(address, credentials)
 * val channel = client.channel
 * val stub = MyServiceGrpcKt.MyServiceCoroutineStub(channel)
 * ```
 */
class BossIpcClient(
    private val address: String,
    private val credentials: IpcClientCredentials,
    private val interceptors: List<ClientInterceptor> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(BossIpcClient::class.java)

    private val lifecycle = Any()
    private var existingChannel: ManagedChannel? = null
    private var closed = false

    val channel: ManagedChannel
        get() =
            synchronized(lifecycle) {
                check(!closed) { "IPC client has been closed" }
                existingChannel ?: buildChannel().also { existingChannel = it }
            }

    private fun buildChannel(): ManagedChannel {
        val builder = IpcAddressResolver.configureChannelBuilder(address)
        builder.sslContext(IpcTlsIdentity.clientContext(credentials.certificateBase64))
        builder.overrideAuthority(IpcTlsIdentity.AUTHORITY)
        builder.intercept(ProcessTokenClientInterceptor(credentials.token))
        if (interceptors.isNotEmpty()) {
            builder.intercept(interceptors)
        }
        return builder.build().also {
            logger.info("IPC client connected to: {}", address)
        }
    }

    /**
     * Wait for the channel to become ready, with exponential backoff.
     * Returns true if connected, false if timeout exceeded.
     */
    suspend fun waitForReady(timeoutMs: Long = 30_000): Boolean {
        val startTime = System.currentTimeMillis()
        var backoffMs = 100L
        val maxBackoffMs = 5_000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val state = channel.getState(true)
            if (state == ConnectivityState.READY) {
                return true
            }
            if (state == ConnectivityState.SHUTDOWN) {
                logger.error("Channel to {} is shutdown", address)
                return false
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        }

        logger.warn("Timeout waiting for channel to {} to become ready", address)
        return false
    }

    /**
     * Check if the channel is currently connected.
     */
    val isConnected: Boolean
        get() = synchronized(lifecycle) { existingChannel?.getState(false) == ConnectivityState.READY }

    /**
     * Gracefully shut down the channel.
     */
    fun shutdown(timeoutMs: Long = 5_000) {
        val current =
            synchronized(lifecycle) {
                closed = true
                existingChannel
            } ?: return
        if (!current.isShutdown) {
            logger.info("Shutting down IPC client to: {}", address)
            current.shutdown()
            if (!current.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                current.shutdownNow()
            }
        }
    }
}
