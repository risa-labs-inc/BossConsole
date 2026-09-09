package ai.rever.boss.html

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform

/** A window owns its queue, so startup and a busy dialog cannot lose or redirect an open. */
internal class HtmlFileOpenQueue {
    private val requests = Channel<HtmlFileOpenRequest>(Channel.BUFFERED)
    private var pending by mutableStateOf(0)
    val hasPending: Boolean get() = pending > 0
    val events =
        requests.receiveAsFlow().transform { request ->
            try {
                emit(request)
            } finally {
                complete()
            }
        }

    suspend fun enqueue(
        filePath: String,
        fileName: String,
    ) {
        pending++
        var sent = false
        try {
            requests.send(HtmlFileOpenRequest(filePath, fileName))
            sent = true
        } finally {
            if (!sent) complete()
        }
    }

    private fun complete() {
        pending = (pending - 1).coerceAtLeast(0)
    }

    fun close() {
        requests.cancel()
        pending = 0
    }
}

/** Identity distinguishes repeated opens of the same file when resetting the dialog's checkbox. */
internal class HtmlFileOpenRequest(
    val filePath: String,
    val fileName: String,
)
