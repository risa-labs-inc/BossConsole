package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.ArrayDeque

/** Bounded recent output is replayed to late subscribers; slow live readers receive an explicit gap error. */
internal class TerminalOutputBuffer {
    private data class Entry(
        val sequence: Long,
        val chunk: TerminalOutputChunk,
    )

    private data class Batch(
        val revision: Long,
        val next: Long,
        val chunks: List<TerminalOutputChunk>,
        val finished: Boolean,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val updates = MutableStateFlow(0L)
    private var bytes = 0
    private var nextSequence = 0L
    private var finished = false

    fun append(chunk: TerminalOutputChunk) {
        synchronized(lock) {
            check(!finished) { "Terminal output is already complete" }
            require(chunk.serializedSize <= 1_048_576) { "Terminal chunk exceeds buffer capacity" }
            entries.addLast(Entry(nextSequence++, chunk))
            bytes += chunk.serializedSize
            while (entries.size > 256 || bytes > 1_048_576) bytes -= entries.removeFirst().chunk.serializedSize
            finished = chunk.isExit
            updates.value = nextSequence
        }
    }

    fun stream(): Flow<TerminalOutputChunk> =
        flow {
            var cursor: Long? = null
            var complete = false
            while (!complete) {
                val batch =
                    synchronized(lock) {
                        val oldest = entries.firstOrNull()?.sequence ?: nextSequence
                        val start = cursor ?: oldest
                        if (start < oldest) {
                            throw Status.OUT_OF_RANGE
                                .withDescription(
                                    "Terminal output exceeded the replay window; reconnect to resume",
                                ).asRuntimeException()
                        }
                        // A collector never keeps a second full replay window while the client is slow.
                        val available =
                            entries
                                .asSequence()
                                .filter { it.sequence >= start }
                                .take(16)
                                .toList()
                        val next = available.lastOrNull()?.sequence?.plus(1) ?: start
                        Batch(nextSequence, next, available.map { it.chunk }, finished && next == nextSequence)
                    }
                batch.chunks.forEach { emit(it) }
                cursor = batch.next
                complete = batch.finished
                if (!complete && batch.next == batch.revision) updates.first { it != batch.revision }
            }
        }
}
