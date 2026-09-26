package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeymapActions
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * A shortcut key owned by the AWT interceptor until its physical key-up arrives.
 *
 * [releaseActionArmed] is meaningful only for the browser-print exception. Normal actions run
 * on key-down; browser print remains armed so its native JxBrowser callback can claim the press
 * before the primary key comes up. Modifier releases update [modifiers] but never throw this
 * record away, which preserves enough identity to reject a stale claim from another window or
 * from a later chord with different modifiers.
 */
internal data class HeldShortcut(
    val keyCode: Int,
    val windowId: String,
    val hostBinding: AWTKeyboardInterceptor.BindingMatch? = null,
    val pluginActionId: String? = null,
    val modifiers: AwtModifierSnapshot = AwtModifierSnapshot(),
    val releaseActionArmed: Boolean = true,
) {
    val firesOnRelease: Boolean
        get() = hostBinding?.binding?.actionId == KeymapActions.BROWSER_PRINT

    fun matches(
        event: KeyEvent,
        candidateWindowId: String,
    ): Boolean = windowId == candidateWindowId && modifiers == AwtModifierSnapshot.from(event)

    fun usedModifier(keyCode: Int): Boolean = modifiers.includes(keyCode)
}

/** Modifier state captured with a claimed physical key. */
internal data class AwtModifierSnapshot(
    val metaDown: Boolean = false,
    val controlDown: Boolean = false,
    val shiftDown: Boolean = false,
    val altDown: Boolean = false,
) {
    fun includes(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.VK_META -> metaDown
            KeyEvent.VK_CONTROL -> controlDown
            KeyEvent.VK_SHIFT -> shiftDown
            KeyEvent.VK_ALT -> altDown
            else -> false
        }

    companion object {
        fun from(event: KeyEvent): AwtModifierSnapshot =
            AwtModifierSnapshot(
                metaDown = event.isMetaDown,
                controlDown = event.isControlDown,
                shiftDown = event.isShiftDown,
                altDown = event.isAltDown,
            )
    }
}

/**
 * Atomic per-key ownership for AWT shortcuts.
 *
 * Native print cancellation arrives from JxBrowser while normal key events arrive on the EDT,
 * so every mutation that can race is expressed as a [ConcurrentHashMap] per-key operation.
 */
internal class HeldShortcutRegistry {
    private val held = ConcurrentHashMap<Int, HeldShortcut>()

    fun isEmpty(): Boolean = held.isEmpty()

    operator fun get(keyCode: Int): HeldShortcut? = held[keyCode]

    fun claim(
        shortcut: HeldShortcut,
        event: KeyEvent? = null,
    ): HeldShortcut {
        val claimed = if (event == null) shortcut else shortcut.copy(modifiers = AwtModifierSnapshot.from(event))
        held[claimed.keyCode] = claimed
        return claimed
    }

    /**
     * Returns true for an auto-repeat of the same physical chord. A record from another window
     * or modifier combination is stale and is atomically discarded so the new press can match.
     */
    fun claimsRepeat(
        event: KeyEvent,
        windowId: String,
    ): Boolean {
        var repeat = false
        held.compute(event.keyCode) { _, current ->
            if (current != null && current.matches(event, windowId)) {
                repeat = true
                current
            } else {
                null
            }
        }
        return repeat
    }

    fun unclaim(shortcut: HeldShortcut) {
        held.remove(shortcut.keyCode, shortcut)
    }

    fun release(keyCode: Int): HeldShortcut? = held.remove(keyCode)

    /**
     * Preserve key ownership when one of a chord's own modifiers comes up, while updating the
     * identity expected from subsequent OS repeats. Unrelated and lock-key releases do nothing.
     */
    fun modifierReleased(event: KeyEvent) {
        val newModifiers = AwtModifierSnapshot.from(event)
        for (keyCode in held.keys.toList()) {
            held.computeIfPresent(keyCode) { _, current ->
                if (current.usedModifier(event.keyCode)) current.copy(modifiers = newModifiers) else current
            }
        }
    }

    /**
     * The native browser callback owns this print. Keep the physical key claimed until key-up,
     * but atomically disarm the AWT release action so the preview cannot open twice.
     */
    fun disarmNativePrint(windowId: String) {
        held.computeIfPresent(KeyEvent.VK_P) { _, current ->
            if (current.windowId == windowId && current.firesOnRelease) {
                current.copy(releaseActionArmed = false)
            } else {
                current
            }
        }
    }

    fun removeWindow(windowId: String) {
        held.entries.removeIf { it.value.windowId == windowId }
    }

    fun clear() {
        held.clear()
    }
}
