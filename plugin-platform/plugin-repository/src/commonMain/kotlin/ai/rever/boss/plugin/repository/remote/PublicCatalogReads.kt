package ai.rever.boss.plugin.repository.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** Public catalog metadata only. Download grants, signatures and permission decisions bypass this cache. */
internal class PublicCatalogReads(
    private val now: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
    private val ttlMs: Long = 30_000,
) {
    private data class Entry(
        val epoch: Long,
        val until: Long,
        val value: Any?,
    )

    private val epoch = MutableStateFlow(0L)
    private val mutex = Mutex()
    private val answers = LinkedHashMap<List<Any?>, Entry>()

    /** Realtime and configuration changes invalidate synchronously, including reads already in flight. */
    fun invalidate() {
        epoch.update { it + 1 }
    }

    suspend fun <T> read(
        key: List<Any?>,
        fetch: suspend () -> T,
    ): T =
        mutex.withLock {
            val generation = epoch.value
            val at = now()
            answers.entries.removeAll { it.value.epoch != generation || it.value.until <= at }
            answers[key]?.let {
                @Suppress("UNCHECKED_CAST")
                return@withLock it.value as T
            }
            val value = fetch()
            if (epoch.value == generation) {
                if (answers.size >= 128) answers.remove(answers.keys.first())
                answers[key] = Entry(generation, at + ttlMs, value)
            }
            value
        }

    companion object {
        private val origin = TimeSource.Monotonic.markNow()
    }
}
