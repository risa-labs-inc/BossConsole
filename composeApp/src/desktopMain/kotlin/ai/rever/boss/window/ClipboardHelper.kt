package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Robot
import java.awt.event.KeyEvent

/**
 * Helper for clipboard operations (copy, paste, cut, select all) triggered from MenuBar.
 *
 * Uses Java AWT Robot to send keyboard shortcuts programmatically, which works universally
 * with any focused text input including:
 * - JxBrowser text fields (in Fluck browser tabs)
 * - Terminal emulator text
 * - Compose TextField components
 *
 * Automatically detects platform and uses correct modifier key:
 * - macOS: Cmd (Meta)
 * - Windows/Linux: Ctrl
 */
object ClipboardHelper {
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

    /**
     * Trigger "Copy" operation (Cmd+C on macOS, Ctrl+C on Windows/Linux)
     */
    fun copy() {
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

    /**
     * Trigger "Paste" operation (Cmd+V on macOS, Ctrl+V on Windows/Linux)
     */
    fun paste() {
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

    /**
     * Trigger "Cut" operation (Cmd+X on macOS, Ctrl+X on Windows/Linux)
     */
    fun cut() {
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

    /**
     * Trigger "Select All" operation (Cmd+A on macOS, Ctrl+A on Windows/Linux)
     */
    fun selectAll() {
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
}
