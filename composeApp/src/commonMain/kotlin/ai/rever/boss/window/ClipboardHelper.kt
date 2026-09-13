package ai.rever.boss.window

/**
 * Multiplatform interface for clipboard operations (copy, paste, cut, select all, copyText).
 * Desktop implementations use Java AWT Robot and Toolkit system clipboard.
 */
expect object ClipboardHelper {
    fun copy()

    fun paste()

    fun cut()

    fun selectAll()

    /**
     * Directly copies [text] to the system clipboard.
     * Returns true if successfully copied, false otherwise.
     */
    fun copyText(text: String): Boolean

    /**
     * Safely copies [text] to the system clipboard with non-blocking retry logic,
     * ensuring the UI / Event Dispatch Thread is never starved or frozen on lock contention.
     */
    suspend fun copyTextSafe(
        text: String,
        retries: Int = 4,
    ): Boolean
}
