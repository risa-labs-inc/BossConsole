package ai.rever.boss.cache

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val originalFaviconDns = lazy { FaviconDnsResolver() }

internal suspend fun resolveOriginalFaviconHost(host: String): List<InetAddress> =
    if (isPublicFaviconHost(host)) originalFaviconDns.value.resolve(host) else emptyList()

internal fun closeOriginalFaviconDns() {
    if (originalFaviconDns.isInitialized()) originalFaviconDns.value.close()
}

/** Blocking platform DNS runs on at most three daemon workers, never an unbounded IO pool. */
internal class FaviconDnsResolver(
    private val timeoutMs: Long = 1500,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : AutoCloseable {
    private val executor =
        ThreadPoolExecutor(
            3,
            3,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(3),
            { task -> Thread(task, "boss-favicon-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    suspend fun resolve(host: String): List<InetAddress> =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val future =
                        executor.submit {
                            val result =
                                try {
                                    lookup(host)
                                } catch (_: UnknownHostException) {
                                    emptyList()
                                } catch (_: SecurityException) {
                                    emptyList()
                                }
                            continuation.resume(result)
                        }
                    continuation.invokeOnCancellation {
                        future.cancel(true)
                        // Remove cancelled queued work even if a platform resolver ignores interrupt.
                        if (future is Runnable) executor.remove(future)
                    }
                } catch (_: RejectedExecutionException) {
                    continuation.resume(emptyList())
                }
            }
        } ?: emptyList()

    override fun close() {
        executor.shutdownNow()
    }
}
