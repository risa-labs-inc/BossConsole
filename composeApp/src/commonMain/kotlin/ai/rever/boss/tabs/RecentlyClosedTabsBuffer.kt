package ai.rever.boss.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Metadata capturing a closed tab state for historical restoration.
 */
data class ClosedTabInfo(
    val id: String,
    val title: String,
    val typeId: String,
    val url: String? = null,
    val windowId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Thread-safe buffer tracking recently closed tabs across windows (max capacity: 10).
 */
object RecentlyClosedTabsBuffer {
    private const val MAX_CAPACITY = 10

    private val lock = Any()
    private val buffer = ArrayDeque<ClosedTabInfo>()

    private val _closedTabsFlow = MutableStateFlow<List<ClosedTabInfo>>(emptyList())
    val closedTabsFlow: StateFlow<List<ClosedTabInfo>> = _closedTabsFlow.asStateFlow()

    /**
     * Records a closed tab into the buffer stack.
     */
    fun recordClosedTab(
        id: String,
        title: String,
        typeId: String,
        url: String? = null,
        windowId: String? = null,
    ) {
        if (title.isBlank()) return
        synchronized(lock) {
            val item =
                ClosedTabInfo(
                    id = id,
                    title = title,
                    typeId = typeId,
                    url = url,
                    windowId = windowId,
                )
            buffer.addFirst(item)
            while (buffer.size > MAX_CAPACITY) {
                buffer.removeLast()
            }
            _closedTabsFlow.value = buffer.toList()
        }
    }

    /**
     * Pops and returns the most recently closed tab from the stack.
     */
    fun popMostRecent(): ClosedTabInfo? {
        return synchronized(lock) {
            if (buffer.isEmpty()) return null
            val item = buffer.removeFirst()
            _closedTabsFlow.value = buffer.toList()
            item
        }
    }

    /**
     * Returns a snapshot list of recently closed tabs.
     */
    fun getHistory(): List<ClosedTabInfo> =
        synchronized(lock) {
            buffer.toList()
        }

    /**
     * Clears all recorded closed tab history.
     */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _closedTabsFlow.value = emptyList()
        }
    }
}
