package ai.rever.boss.components.plugin

import androidx.compose.runtime.mutableStateMapOf

/** UI-thread state keyed by stable tab identity, including plugin-defined tab models. */
object TabAudioRegistry {
    private data class Entry(
        val owner: Any,
        val playing: Boolean,
    )

    private val entries = mutableStateMapOf<String, Entry>()

    fun isPlaying(tabId: String?): Boolean = entries[tabId]?.playing == true

    internal fun claim(
        tabId: String,
        owner: Any,
        playing: Boolean,
    ) {
        entries[tabId] = Entry(owner, playing)
    }

    internal fun update(
        tabId: String,
        owner: Any,
        playing: Boolean,
    ) {
        if (entries[tabId]?.owner === owner) entries[tabId] = Entry(owner, playing)
    }

    internal fun release(
        tabId: String,
        owner: Any,
    ) {
        if (entries[tabId]?.owner === owner) entries.remove(tabId)
    }
}

/**
 * One browser's playback state. Native callbacks may arrive on any thread; only [dispatch]
 * publishes snapshot state. The registry retains a token, never a browser or panel callback.
 * Queued deliveries read the latest level, so close and pause cannot be undone by stale work.
 */
internal class TabAudioSource(
    private val dispatch: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private val token = Any()
    private var tabId: String? = null
    private var publishedId: String? = null
    private var playing = false
    private var closed = false
    private var revision = 0L

    fun bind(id: String) {
        synchronized(lock) {
            if (!closed) tabId = id.takeIf { it.isNotEmpty() }
        }
        publish()
    }

    fun update(value: Boolean) {
        synchronized(lock) {
            if (!closed) {
                playing = value
                revision++
            }
        }
        publish()
    }

    /** Subscribe first, then seed: an event delivered during the native read wins. */
    fun seed(read: () -> Boolean) {
        val before = synchronized(lock) { revision }
        val value = read()
        synchronized(lock) {
            if (!closed && revision == before) playing = value
        }
        publish()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            tabId = null
        }
        publish()
    }

    private fun publish() {
        dispatch {
            synchronized(lock) {
                if (publishedId != tabId) {
                    publishedId?.let { TabAudioRegistry.release(it, token) }
                    publishedId = tabId
                    tabId?.let { TabAudioRegistry.claim(it, token, playing) }
                } else {
                    tabId?.let { TabAudioRegistry.update(it, token, playing) }
                }
            }
        }
    }
}
