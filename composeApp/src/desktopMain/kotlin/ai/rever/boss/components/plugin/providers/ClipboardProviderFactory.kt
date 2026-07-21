package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

private val logger = BossLogger.forComponent("ClipboardProvider")

/**
 * Desktop implementation of ClipboardProvider factory.
 */
actual fun createClipboardProvider(): ClipboardProvider? {
    return DesktopClipboardProvider()
}

/**
 * Desktop clipboard provider using AWT system clipboard.
 *
 * This runs in the host classloader context where AWT is available,
 * bridging clipboard access for dynamically loaded plugins that
 * cannot access AWT directly due to classloader isolation.
 */
private class DesktopClipboardProvider : ClipboardProvider {

    private val clipboard by lazy {
        try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to access system clipboard", error = e)
            null
        }
    }

    override fun readText(): String? {
        return try {
            val cb = clipboard ?: return null
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                cb.getData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to read clipboard text", error = e)
            null
        }
    }

    override fun setText(text: String): Boolean {
        return try {
            val cb = clipboard ?: return false
            cb.setContents(StringSelection(text), null)
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to set clipboard text", error = e)
            false
        }
    }

    override fun hasText(): Boolean {
        return try {
            val cb = clipboard ?: return false
            cb.isDataFlavorAvailable(DataFlavor.stringFlavor)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to check clipboard content", error = e)
            false
        }
    }
}
