package ai.rever.boss.app

import androidx.compose.runtime.mutableStateListOf

/** Window-owned FIFO, accessed only on the UI thread. Closing the window drops its requests. */
internal class UrlOpenApprovalQueue {
    private val requests = mutableStateListOf<PendingUrlOpen>()

    val current: PendingUrlOpen?
        get() = requests.firstOrNull()

    val size: Int
        get() = requests.size

    fun enqueue(request: PendingUrlOpen): Boolean {
        if (requests.size >= MAX_PENDING) return false
        requests.add(request)
        return true
    }

    companion object {
        const val MAX_PENDING = 16
    }

    /** Only the request actually shown can be consumed, once, even for identical URLs. */
    fun consume(request: PendingUrlOpen): Boolean {
        if (current !== request) return false
        requests.removeAt(0)
        return true
    }
}
