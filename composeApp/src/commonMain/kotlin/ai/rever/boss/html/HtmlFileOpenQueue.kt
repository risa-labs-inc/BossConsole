package ai.rever.boss.html

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** A window owns its queue, so startup and a busy dialog cannot lose or redirect an open. */
internal class HtmlFileOpenQueue {
    private val requests = Channel<HtmlFileOpenRequest>(Channel.BUFFERED)
    val events = requests.receiveAsFlow()

    suspend fun enqueue(filePath: String, fileName: String) {
        requests.send(HtmlFileOpenRequest(filePath, fileName))
    }

    fun close() {
        requests.cancel()
    }
}

/** Identity distinguishes repeated opens of the same file when resetting the dialog's checkbox. */
internal class HtmlFileOpenRequest(val filePath: String, val fileName: String)
