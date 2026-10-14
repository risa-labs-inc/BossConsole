package ai.rever.boss.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

internal const val GIT_CLONE_TIMEOUT_MILLIS = 600_000L
private const val CLONE_PROCESS_KILL_TIMEOUT_SECONDS = 5L
private const val CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS = 5_000L

private class CloneOutputReader(
    val thread: Thread,
    val lines: Channel<String>,
)

internal class CloneOutputLifecycle(
    val onClosed: () -> Unit = {},
    val onFailure: (Throwable) -> Unit = {},
)

internal fun cloneOutputReaderThreadName(process: Process): String {
    val processId = runCatching { process.pid() }.getOrDefault(-1L)
    return "boss-git-clone-output-$processId"
}

/**
 * Streams advisory progress while independently awaiting process exit. The pipe
 * reader only queues lines; callbacks belong to this coroutine and are settled
 * before return. Callbacks must be prompt and must not suppress interruption.
 * Interruptible callbacks allow cancellation without holding a cleanup lock.
 */
internal suspend fun runCloneProcess(
    process: Process,
    timeoutMillis: Long,
    onOutputLine: (String) -> Unit,
    onCancellation: () -> Unit,
    outputLifecycle: CloneOutputLifecycle = CloneOutputLifecycle(),
): Int {
    val processResult = CompletableDeferred<Int>()
    val outputReader = createCloneOutputReader(process)
    var processExitObserved = false
    var callbacksEnabled = true
    val publish: (String) -> Unit = { line ->
        if (callbacksEnabled) {
            runCatching { onOutputLine(line) }.onFailure { failure ->
                if (failure is CancellationException || failure is InterruptedException) throw failure
                callbacksEnabled = false
                runCatching { outputLifecycle.onFailure(failure) }
            }
        }
    }
    try {
        registerCloneProcessExit(process, processResult)
        outputReader.thread.start()
        val exitCode =
            withTimeout(timeoutMillis) {
                awaitCloneResult(process, processResult, outputReader.lines, publish)
            }
        processExitObserved = true
        // Git's result is authoritative. Draining trailing advisory output has its
        // own bound; a slow callback is interrupted and settled before completion.
        runCatching {
            withTimeout(CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS) {
                for (line in outputReader.lines) {
                    runInterruptible(Dispatchers.IO) { publish(line) }
                }
            }
        }.onFailure { failure ->
            currentCoroutineContext().ensureActive()
            runCatching { outputLifecycle.onFailure(failure) }
        }
        return exitCode
    } finally {
        outputReader.lines.cancel()
        runCatching { outputLifecycle.onClosed() }
        cleanupCloneProcess(
            process = process,
            outputReader = outputReader.thread,
            cancellationCleanup = if (processExitObserved) null else onCancellation,
        )
    }
}

private suspend fun awaitCloneResult(
    process: Process,
    processResult: CompletableDeferred<Int>,
    lines: Channel<String>,
    onOutputLine: (String) -> Unit,
): Int {
    var outputOpen = true
    while (true) {
        val exitCode =
            select<Int?> {
                // Prefer a known exit over further advisory output, including a
                // reader error racing EOF after a successful process exit.
                processResult.onAwait { it }
                if (outputOpen) {
                    lines.onReceiveCatching { result ->
                        val failure = result.exceptionOrNull()
                        val line = result.getOrNull()
                        if (failure != null) {
                            // onExit delivery can lag the actual OS exit. A pipe
                            // error must not delete a checkout Git already completed.
                            runCatching { process.exitValue() }.getOrElse { throw failure }
                        } else if (line == null) {
                            outputOpen = false
                            null
                        } else {
                            deliverCloneProgress(processResult, line, onOutputLine)
                        }
                    }
                }
            }
        if (exitCode != null) return exitCode
    }
}

private suspend fun deliverCloneProgress(
    processResult: CompletableDeferred<Int>,
    line: String,
    onOutputLine: (String) -> Unit,
): Int? =
    coroutineScope {
        val delivery =
            async {
                runCatching { runInterruptible(Dispatchers.IO) { onOutputLine(line) } }
            }
        try {
            select {
                processResult.onAwait { it }
                delivery.onAwait { result ->
                    result.getOrThrow()
                    null
                }
            }
        } finally {
            delivery.cancelAndJoin()
        }
    }

private fun createCloneOutputReader(process: Process): CloneOutputReader {
    // Progress is advisory: bound memory and retain recent updates if a producer
    // outruns its consumer. The pipe thread must never block on user code.
    val lines = Channel<String>(64, BufferOverflow.DROP_OLDEST)
    val outputReader =
        Thread(
            {
                val failure =
                    runCatching {
                        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                            var line = reader.readLine()
                            while (line != null && lines.trySend(line).isSuccess) {
                                line = reader.readLine()
                            }
                        }
                    }.exceptionOrNull()
                lines.close(failure)
            },
            cloneOutputReaderThreadName(process),
        ).apply { isDaemon = true }
    return CloneOutputReader(outputReader, lines)
}

/**
 * Kept clone-local because this source survives Windows ARM64 filtering while
 * the process helpers in the kernel package do not.
 */
private fun snapshotCloneProcessDescendants(process: Process): List<ProcessHandle> =
    runCatching {
        process
            .toHandle()
            .descendants()
            .use { it.toList() }
    }.getOrDefault(emptyList())

private fun killCloneProcessDescendants(descendants: List<ProcessHandle>) {
    descendants.forEach { descendant ->
        runCatching {
            if (descendant.isAlive) {
                descendant.destroyForcibly()
            }
        }
    }
}

private fun awaitCloneProcessDescendantsExit(descendants: List<ProcessHandle>) {
    val deadlineNanos =
        System.nanoTime() +
            TimeUnit.SECONDS.toNanos(CLONE_PROCESS_KILL_TIMEOUT_SECONDS)

    descendants.forEach { descendant ->
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            return
        }

        runCatching {
            descendant.onExit().get(remainingNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private suspend fun cleanupCloneProcess(
    process: Process,
    outputReader: Thread,
    cancellationCleanup: (() -> Unit)?,
) {
    withContext(NonCancellable + Dispatchers.IO) {
        val descendants = snapshotCloneProcessDescendants(process)

        if (process.isAlive) {
            killCloneProcessDescendants(descendants)
            runCatching { process.destroyForcibly() }
            runCatching {
                process.waitFor(
                    CLONE_PROCESS_KILL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }

        killCloneProcessDescendants(descendants)
        awaitCloneProcessDescendantsExit(descendants)
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }

        outputReader.interrupt()
        runCatching {
            outputReader.join(CLONE_OUTPUT_READER_JOIN_TIMEOUT_MILLIS)
        }

        if (cancellationCleanup != null) {
            runCatching { cancellationCleanup() }
        }
    }
}

private fun registerCloneProcessExit(
    process: Process,
    processResult: CompletableDeferred<Int>,
) {
    val exitFuture =
        runCatching { process.onExit() }
            .getOrElse { error ->
                processResult.completeExceptionally(error)
                return
            }

    exitFuture.whenComplete { completedProcess, error ->
        if (error == null) {
            processResult.complete(completedProcess.exitValue())
        } else {
            processResult.completeExceptionally(error)
        }
    }
}
