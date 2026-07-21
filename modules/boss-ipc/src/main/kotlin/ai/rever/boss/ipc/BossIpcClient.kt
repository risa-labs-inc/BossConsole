package ai.rever.boss.ipc

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
 * val client = BossIpcClient(address)
 * val channel = client.channel
 * val stub = MyServiceGrpcKt.MyServiceCoroutineStub(channel)
 * ```
 */
class BossIpcClient(
    private val address: String,
    private val usePlaintext: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(BossIpcClient::class.java)

    val channel: ManagedChannel by lazy {
        val builder = IpcAddressResolver.configureChannelBuilder(address)
        if (usePlaintext) {
            builder.usePlaintext()
        }
        builder.build().also {
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
        get() = channel.getState(false) == ConnectivityState.READY

    /**
     * Gracefully shut down the channel.
     */
    fun shutdown(timeoutMs: Long = 5_000) {
        if (!channel.isShutdown) {
            logger.info("Shutting down IPC client to: {}", address)
            channel.shutdown()
            if (!channel.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("IPC client did not terminate in {}ms, forcing", timeoutMs)
                channel.shutdownNow()
            }
        }
    }
}
