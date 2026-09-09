package ai.rever.boss.plugin.browser

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * One process-wide clipboard round trip for paste without formatting (#205).
 *
 * AWT's system clipboard wraps installed Transferables, so their object identity is not an
 * ownership signal. A JVM-local marker survives that wrapper without being pasted as text.
 * Each write gets a new marker; only its own timer can restore it. Consecutive owned writes
 * retain the first rich original, but a foreign copy starts a fresh round trip even if its
 * text is identical. Clipboard access failures retire the pending restore rather than letting
 * a later paste resurrect stale content.
 *
 * Access through this session is serialized. AWT offers no atomic compare-and-set against
 * other processes: a native copy between the final ownership read and restore can still win
 * or lose. The 200ms consumption delay remains a heuristic, not a Chromium acknowledgment.
 * Tests inject clipboard suppliers and never access the user's system clipboard.
 */
internal class PasteWithoutFormattingSession(
    private val currentContents: () -> Transferable?,
    private val install: (Transferable) -> Unit,
) {
    private data class Pending(
        val ticket: Any,
        val original: Transferable,
    )

    private var pending: Pending? = null

    /** Installs plain text and returns the opaque ticket for that write's deferred restore. */
    @Synchronized
    fun beginPaste(): Any? {
        val previous = pending
        pending = null
        val current = currentContents() ?: return null
        val text =
            if (current.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                current.getTransferData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        return if (text != null) {
            val original =
                if (previous != null && marker(current) === previous.ticket) previous.original else current
            val ticket = Any()
            install(PlainTextWithMarker(text, ticket))
            pending = Pending(ticket, original)
            ticket
        } else {
            null
        }
    }

    /** Stale timers cannot retire a newer write, even after a foreign copy or an earlier restore. */
    @Synchronized
    fun tryRestore(ticket: Any): Boolean {
        val active = pending?.takeIf { it.ticket === ticket } ?: return false
        pending = null
        val current = currentContents()
        return if (current != null && marker(current) === ticket) {
            install(active.original)
            true
        } else {
            false
        }
    }

    private fun marker(contents: Transferable): Any? =
        if (contents.isDataFlavorSupported(MARKER_FLAVOR)) contents.getTransferData(MARKER_FLAVOR) else null

    private class PlainTextWithMarker(
        text: String,
        private val ticket: Any,
    ) : Transferable {
        private val plain = StringSelection(text)

        override fun getTransferDataFlavors(): Array<DataFlavor> = plain.transferDataFlavors + MARKER_FLAVOR

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

        override fun getTransferData(flavor: DataFlavor): Any =
            when {
                flavor == MARKER_FLAVOR -> ticket
                plain.isDataFlavorSupported(flavor) -> plain.getTransferData(flavor)
                else -> throw UnsupportedFlavorException(flavor)
            }
    }

    private companion object {
        val MARKER_FLAVOR =
            DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.Object", "BOSS plain paste")
    }
}
