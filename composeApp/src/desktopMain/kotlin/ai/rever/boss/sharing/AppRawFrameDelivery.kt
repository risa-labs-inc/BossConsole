package ai.rever.boss.sharing

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded raw-response workers, isolated from the HTTP server's asset and control workers. */
internal class AppRawFrameDelivery(
    workerFactory: ThreadFactory =
        ThreadFactory { work ->
            Thread(work, "boss-app-share-raw").apply { isDaemon = true }
        },
) : AutoCloseable {
    enum class Admission { ACCEPTED, DUPLICATE, SATURATED, RETIRED }

    private val closed = AtomicBoolean()
    private val pages = ConcurrentHashMap.newKeySet<Page>()
    private val workers =
        ThreadPoolExecutor(
            16,
            16,
            0,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            workerFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val deadlines =
        ScheduledThreadPoolExecutor(1) { work ->
            Thread(work, "boss-app-share-raw-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    @Synchronized
    fun newPage(): Page {
        check(!closed.get()) { "Raw delivery is closed" }
        return Page().also { pages.add(it) }
    }

    inner class Page internal constructor() : AutoCloseable {
        private var retired = false
        private var pending: Pending? = null

        /**
         * Caller must authenticate host/token/origin, validate headers, and require a zero request
         * body (no Transfer-Encoding; Content-Length absent or zero) before admission. The action
         * may await the latest mailbox sequence for at most 250 ms, then write one bounded frame.
         * Only ACCEPTED transfers exchange ownership; every other result leaves it with the caller.
         */
        fun submit(
            exchange: HttpExchange,
            action: () -> Unit,
        ): Admission =
            synchronized(this) {
                if (retired || closed.get()) return@synchronized Admission.RETIRED
                if (pending != null) return@synchronized Admission.DUPLICATE
                val request = Pending(exchange, action)
                pending = request
                try {
                    workers.execute(request)
                } catch (_: RejectedExecutionException) {
                    pending = null
                    return@synchronized if (closed.get()) Admission.RETIRED else Admission.SATURATED
                }
                // Never arm before execute: rejected work must not close a caller-owned exchange.
                request.armDeadline()
                Admission.ACCEPTED
            }

        override fun close() {
            val request =
                synchronized(this) {
                    if (retired) return
                    retired = true
                    pending
                }
            pages.remove(this)
            request?.cancel()
        }

        private inner class Pending(
            private val exchange: HttpExchange,
            private val action: () -> Unit,
        ) : Runnable {
            private val due = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            private val started = AtomicBoolean()
            private val cancelled = AtomicBoolean()
            private val cleaning = AtomicBoolean()
            private val completed = AtomicBoolean()
            private val exchangeClosed = AtomicBoolean()
            private var deadline: ScheduledFuture<*>? = null
            private val task =
                FutureTask<Unit> {
                    started.set(true)
                    try {
                        val live = synchronized(this@Page) { !retired && pending === this && !closed.get() }
                        if (live && !cancelled.get() && System.nanoTime() < due) action()
                    } finally {
                        finish()
                    }
                }

            override fun run() {
                try {
                    task.run()
                } finally {
                    // FutureTask skips its callable if cancelled before execution.
                    if (!started.get()) finish()
                }
            }

            fun armDeadline() {
                synchronized(this) {
                    if (completed.get() || cancelled.get()) return
                    try {
                        deadline =
                            deadlines.schedule(
                                ::cancel,
                                (due - System.nanoTime()).coerceAtLeast(0),
                                TimeUnit.NANOSECONDS,
                            )
                    } catch (_: RejectedExecutionException) {
                        cancel()
                    }
                }
            }

            fun cancel() {
                cancelled.set(true)
                // Interrupt this FutureTask, never a saved Thread which may have been reused.
                // JDK HttpServer writes a blocking SocketChannel; interrupt closes that channel.
                task.cancel(true)
                closeExchange()
            }

            private fun closeExchange() {
                if (exchangeClosed.compareAndSet(false, true)) runCatching { exchange.close() }
            }

            private fun finish() {
                if (!cleaning.compareAndSet(false, true)) return
                try {
                    closeExchange()
                } finally {
                    completed.set(true)
                    synchronized(this) { deadline?.cancel(false) }
                    // Cancellation marks a Future done before its writer unwinds. Retain the slot
                    // until actual cleanup, rather than releasing it from FutureTask.done().
                    synchronized(this@Page) { if (pending === this) pending = null }
                }
            }
        }
    }

    override fun close() {
        val retiring =
            synchronized(this) {
                if (!closed.compareAndSet(false, true)) return
                pages.toList().also { pages.clear() }
            }
        retiring.forEach { it.close() }
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
}
