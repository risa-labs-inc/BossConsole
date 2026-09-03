package ai.rever.boss.plugin.browser

import java.awt.datatransfer.Transferable

/**
 * Bookkeeping for paste-without-formatting's clipboard round trip (issue #205).
 *
 * The shortcut overwrites the system clipboard with plain text, lets Chromium paste, and
 * restores the original content a moment later. The restore is the dangerous half. An
 * unconditional timer-based restore clobbers anything the user copies in the window. And a
 * naive string-equality guard cannot distinguish "our plain text is still on the clipboard"
 * from "a previous press already restored the rich original" - whose own string projection
 * is, by construction, the very same text. That second state is what made two rapid presses
 * permanently downgrade a rich clipboard to plain text: the second press's "original" is the
 * first press's plain write, so once the first restore landed, the second restore's guard
 * matched again and put plain text back over it.
 *
 * The fix is identity, not text. Every press registers the exact [Transferable] it installed,
 * and a restore fires only while the clipboard's CURRENT contents is one of those instances
 * (AWT hands back the same instance it was given, until someone replaces it). A restore puts
 * back the content that preceded the FIRST outstanding write of the window, so a burst of
 * presses converges on the rich original rather than on the last press's plain text. A user
 * copy in the window is a foreign Transferable, so it wins and the whole session retires.
 *
 * This also settles why not AWT's `lostOwnership`: that callback fires on ANY replacement,
 * including our own second press's write - which would cancel the first press's restore and
 * reintroduce the downgrade. Identity checked at restore time gives the precise signal.
 *
 * Pure given its suppliers: the clipboard's current contents is read through
 * [currentContents] and the restore installed through [install], so tests run with fake
 * Transferables and no AWT clipboard at all. All state is guarded by [this]; the pending map
 * empties on every exit path (restore hit, foreign content, nothing pending), so a burst
 * holds nothing afterwards. Residual race: a write landing between [tryRestore]'s read and
 * install is microseconds wide and best-effort by design - the old code exposed an
 * unconditional 200ms one.
 */
internal class PasteWithoutFormattingSession(
    private val currentContents: () -> Transferable?,
    private val install: (Transferable) -> Unit,
) {
    // written -> the content to put back for it. Identity-keyed on purpose: StringSelection
    // has no value equality, and two presses writing the same text must stay distinct.
    private val pending = LinkedHashMap<Transferable, Transferable>()
    private var windowOriginal: Transferable? = null

    /**
     * Record that [written] is about to be installed as this press's plain-text clipboard.
     * The first outstanding write of a window also captures the content to restore to;
     * later writes in the same window inherit it, which is what keeps a burst converging
     * on the rich original instead of on the previous press's plain text.
     */
    fun registerWrite(written: Transferable) {
        synchronized(this) {
            if (windowOriginal == null) windowOriginal = currentContents() ?: written
            pending[written] = windowOriginal ?: written
        }
    }

    /**
     * Attempt the deferred restore. Returns true when it installed anything, false when the
     * clipboard had already moved on - a user copy, another application, or every write
     * already restored - in which case the session retires and later presses start fresh.
     */
    fun tryRestore(): Boolean {
        synchronized(this) {
            val target = windowOriginal
            if (pending.isEmpty() || target == null) return false
            val current = currentContents()
            val isOurs = pending.keys.any { it === current }
            pending.clear()
            windowOriginal = null
            if (!isOurs) return false
            install(target)
            return true
        }
    }
}
