package ai.rever.boss.services

import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop implementation of FileHandlerService.
 *
 * Delegates to CLICommandHandler for file event queueing management.
 * Tracks file processing to prevent New Tab Dialog race condition.
 * Uses hybrid AtomicInteger + mutableStateOf for thread safety and Compose reactivity.
 */
actual object FileHandlerService {
    private val logger = BossLogger.forComponent("FileHandlerService")
    private val processingCount = AtomicInteger(0)
    private val _isProcessing = mutableStateOf(false)

    actual fun markReady() {
        CLICommandHandler.getInstance().markFileHandlerReady()
    }

    /**
     * Check if file events are currently being processed.
     * Used by BossApp to prevent showing New Tab Dialog during file tab creation.
     * Returns Compose State to trigger recomposition.
     */
    actual fun isProcessingFiles(): Boolean {
        return _isProcessing.value
    }

    /**
     * Increment processing counter when file event starts.
     * Should be called before emitting file event.
     * Updates both AtomicInteger (thread-safe) and mutableStateOf (Compose reactive).
     */
    internal fun incrementProcessing() {
        val count = processingCount.incrementAndGet()
        _isProcessing.value = (count > 0)
        logger.debug(LogCategory.FILE, "incrementProcessing", mapOf("count" to count, "isProcessing" to _isProcessing.value))
    }

    /**
     * Decrement processing counter when file event completes.
     * Should be called after file tab is created.
     * Updates both AtomicInteger (thread-safe) and mutableStateOf (Compose reactive).
     */
    internal fun decrementProcessing() {
        val count = processingCount.decrementAndGet()
        _isProcessing.value = (count > 0)
        logger.debug(LogCategory.FILE, "decrementProcessing", mapOf("count" to count, "isProcessing" to _isProcessing.value))
    }
}
