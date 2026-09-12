package ai.rever.boss.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val STARTUP_NOTICE_SOURCE = "kernel-startup-summary"
internal const val KERNEL_NOTICE_DURATION_MS = 12_000L

/** Retain latest diagnostics per source. Cancellation favors at-least-once delivery over loss. */
internal class StartupNoticeQueue {
    private val pending = MutableStateFlow<Map<String, String>>(emptyMap())
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val delivery = Mutex()
    val notices =
        flow {
            while (true) {
                delivery.withLock {
                    if (pending.value.isEmpty()) wakeUp.receive()
                    val batch = pending.value
                    if (batch.isNotEmpty()) {
                        pending.update { current -> current.filterNot { (key, value) -> batch[key] == value } }
                        try {
                            emit(renderKernelNotices(batch))
                        } catch (cancelled: CancellationException) {
                            // Flow.first aborts after delivery while its job is still active.
                            // A cancelled window instead returns ownership to pending state.
                            if (!currentCoroutineContext().isActive) pending.update { batch + it }
                            throw cancelled
                        }
                        delay(KERNEL_NOTICE_DURATION_MS)
                    }
                }
            }
        }

    fun report(
        message: String,
        source: String = message,
    ) {
        pending.update { it + (source to message) }
        wakeUp.trySend(Unit)
    }
}

internal val kernelStartupNotices = StartupNoticeQueue()

/** Large batches intentionally summarize all details, including startup, in the kernel log. */
internal fun renderKernelNotices(batch: Map<String, String>): String {
    val repairs = batch.keys.count { it != STARTUP_NOTICE_SOURCE }
    if (repairs > 2) return "$repairs services need attention. Check service logs for details."
    return batch.entries.sortedBy { it.key == STARTUP_NOTICE_SOURCE }.joinToString(" · ") { it.value }
}
