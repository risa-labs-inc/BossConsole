package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

/**
 * Desktop implementation of [ClipboardHelper] using Java AWT Robot and Toolkit system clipboard.
 */
actual object ClipboardHelper {
    private val logger = BossLogger.forComponent("ClipboardHelper")

    private val robot by lazy {
        try {
            Robot()
        } catch (e: Exception) {
            logger.warn(
                LogCategory.UI,
                "Failed to initialize Robot for clipboard operations - may need accessibility permissions",
                error = e,
            )
            null
        }
    }

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val modifierKey = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL

    actual fun copy() {
        robot?.let {
            try {
                it.keyPress(modifierKey)
                it.keyPress(KeyEvent.VK_C)
                it.keyRelease(KeyEvent.VK_C)
                it.keyRelease(modifierKey)
                logger.debug(LogCategory.UI, "Copy operation triggered")
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Copy operation failed", error = e)
            }
        }
    }

    actual fun paste() {
        robot?.let {
            try {
                it.keyPress(modifierKey)
                it.keyPress(KeyEvent.VK_V)
                it.keyRelease(KeyEvent.VK_V)
                it.keyRelease(modifierKey)
                logger.debug(LogCategory.UI, "Paste operation triggered")
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Paste operation failed", error = e)
            }
        }
    }

    actual fun cut() {
        robot?.let {
            try {
                it.keyPress(modifierKey)
                it.keyPress(KeyEvent.VK_X)
                it.keyRelease(KeyEvent.VK_X)
                it.keyRelease(modifierKey)
                logger.debug(LogCategory.UI, "Cut operation triggered")
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Cut operation failed", error = e)
            }
        }
    }

    actual fun selectAll() {
        robot?.let {
            try {
                it.keyPress(modifierKey)
                it.keyPress(KeyEvent.VK_A)
                it.keyRelease(KeyEvent.VK_A)
                it.keyRelease(modifierKey)
                logger.debug(LogCategory.UI, "Select All operation triggered")
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Select All operation failed", error = e)
            }
        }
    }

    /**
     * Directly copies [text] to the system clipboard synchronously.
     *
     * Guards against headless testing environments and avoids blocking invokeAndWait calls.
     */
    actual fun copyText(text: String): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        val selection = StringSelection(text)
        return try {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            toolkit.systemClipboard.setContents(selection, selection)
            if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                try {
                    toolkit.systemSelection?.setContents(selection, selection)
                } catch (_: Exception) {
                }
            }
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.UI, "Failed to copy text to system clipboard", error = e)
            false
        }
    }

    /**
     * Safely copies [text] to the system clipboard with non-blocking retry logic,
     * ensuring the UI / Event Dispatch Thread is never starved or frozen on lock contention.
     */
    actual suspend fun copyTextSafe(
        text: String,
        retries: Int,
    ): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (GraphicsEnvironment.isHeadless()) return@withContext false
            val selection = StringSelection(text)
            for (attempt in 0..retries) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val toolkit = java.awt.Toolkit.getDefaultToolkit()
                        toolkit.systemClipboard.setContents(selection, selection)
                        if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
                            try {
                                toolkit.systemSelection?.setContents(selection, selection)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    return@withContext true
                } catch (e: IllegalStateException) {
                    if (attempt == retries) {
                        logger.warn(
                            LogCategory.UI,
                            "Clipboard locked by another process after $retries retries",
                            error = e,
                        )
                        return@withContext false
                    }
                    kotlinx.coroutines.delay(35L * (attempt + 1))
                } catch (e: Exception) {
                    logger.warn(LogCategory.UI, "Failed to copy text to system clipboard", error = e)
                    return@withContext false
                }
            }
            false
        }
}
