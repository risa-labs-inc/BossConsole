package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeymapActions
import java.awt.event.KeyEvent

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

    fun without(keyCode: Int): AwtModifierSnapshot =
        when (keyCode) {
            KeyEvent.VK_META -> copy(metaDown = false)
            KeyEvent.VK_CONTROL -> copy(controlDown = false)
            KeyEvent.VK_SHIFT -> copy(shiftDown = false)
            KeyEvent.VK_ALT -> copy(altDown = false)
            else -> this
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
 * Native print callbacks arrive from JxBrowser while normal key events arrive on the EDT. A
 * single monitor linearizes both sources. Browser print additionally retains a short-lived
 * winner marker after either source wins: it covers a native callback that precedes the AWT
 * press and one that follows the AWT release. Native key-up makes the marker claimable by the
 * next native press without forgetting that a delayed AWT press from the prior chord must stay
 * disarmed.
 */
internal class HeldShortcutRegistry {
    private sealed interface KeyState {
        val windowId: String

        data class Held(
            val shortcut: HeldShortcut,
            val lastEventTime: Long = 0L,
        ) : KeyState {
            override val windowId: String = shortcut.windowId
        }

        data class NativePrintWon(
            override val windowId: String,
        ) : KeyState

        data class NativePrintReleased(
            override val windowId: String,
        ) : KeyState

        data class NativePrintCompleted(
            override val windowId: String,
        ) : KeyState

        data class AwtPrintWon(
            override val windowId: String,
        ) : KeyState
    }

    private val lock = Any()
    private val states = mutableMapOf<Int, KeyState>()

    val hasNoHeldKeys: Boolean
        get() = synchronized(lock) { states.values.none { it is KeyState.Held } }

    operator fun get(keyCode: Int): HeldShortcut? = synchronized(lock) { (states[keyCode] as? KeyState.Held)?.shortcut }

    companion object {
        internal const val REPEAT_TIMEOUT_MS = 1000L
    }

    fun claim(
        shortcut: HeldShortcut,
        event: KeyEvent? = null,
    ): HeldShortcut {
        val candidate = if (event == null) shortcut else shortcut.copy(modifiers = AwtModifierSnapshot.from(event))
        val eventTime = if (event != null && event.`when` > 0L) event.`when` else 0L
        return synchronized(lock) {
            val previous = states[candidate.keyCode]
            val nativeAlreadyWon =
                when (previous) {
                    is KeyState.NativePrintWon -> previous.windowId == candidate.windowId
                    is KeyState.NativePrintReleased -> previous.windowId == candidate.windowId
                    else -> false
                }
            val claimed =
                if (candidate.firesOnRelease && nativeAlreadyWon) {
                    candidate.copy(releaseActionArmed = false)
                } else {
                    candidate
                }
            states[claimed.keyCode] = KeyState.Held(claimed, lastEventTime = eventTime)
            claimed
        }
    }

    /**
     * Returns true for an auto-repeat of the same physical chord. A record from another window
     * or modifier combination is stale and is atomically discarded so the new press can match.
     *
     * A press arriving after [REPEAT_TIMEOUT_MS] since the last event is treated as a fresh
     * press rather than an auto-repeat, recovering gracefully if an intervening key-up was lost.
     */
    fun claimsRepeat(
        event: KeyEvent,
        windowId: String,
    ): Boolean =
        synchronized(lock) {
            when (val current = states[event.keyCode]) {
                is KeyState.Held -> {
                    val timeDelta =
                        if (current.lastEventTime > 0L && event.`when` > 0L) {
                            event.`when` - current.lastEventTime
                        } else {
                            0L
                        }
                    if (timeDelta > REPEAT_TIMEOUT_MS) {
                        states.remove(event.keyCode)
                        false
                    } else if (current.shortcut.matches(event, windowId)) {
                        val nextTime = if (event.`when` > 0L) event.`when` else current.lastEventTime
                        states[event.keyCode] = current.copy(lastEventTime = nextTime)
                        true
                    } else {
                        states.remove(event.keyCode)
                        false
                    }
                }

                // Preserve a native-first marker until claim() can turn it into a disarmed
                // physical-key record. An AWT winner belongs to the prior press and is replaced.
                is KeyState.NativePrintWon,
                is KeyState.NativePrintReleased,
                -> {
                    if (current.windowId != windowId) states.remove(event.keyCode)
                    false
                }

                is KeyState.NativePrintCompleted,
                is KeyState.AwtPrintWon,
                -> {
                    states.remove(event.keyCode)
                    false
                }

                null -> {
                    false
                }
            }
        }

    fun unclaim(shortcut: HeldShortcut) {
        synchronized(lock) {
            if ((states[shortcut.keyCode] as? KeyState.Held)?.shortcut == shortcut) {
                states.remove(shortcut.keyCode)
            }
        }
    }

    /**
     * Release ownership of [keyCode] when AWT reports KEY_RELEASED.
     *
     * If the key was held as an armed browser-print chord, it transitions to [KeyState.AwtPrintWon]
     * to refuse a lagging native callback; if held as a disarmed chord, it transitions to
     * [KeyState.NativePrintCompleted]. When `shortcut == null` (i.e. the key was not actively [KeyState.Held],
     * such as when a native winner marker lingers), removing the entry cleans up the marker so subsequent
     * presses start from a fresh slate.
     */
    fun release(keyCode: Int): HeldShortcut? =
        synchronized(lock) {
            val current = states[keyCode]
            val shortcut = (current as? KeyState.Held)?.shortcut
            when {
                shortcut == null -> {
                    states.remove(keyCode)
                }

                shortcut.firesOnRelease && shortcut.releaseActionArmed -> {
                    states[keyCode] = KeyState.AwtPrintWon(shortcut.windowId)
                }

                shortcut.firesOnRelease -> {
                    states[keyCode] = KeyState.NativePrintCompleted(shortcut.windowId)
                }

                else -> {
                    states.remove(keyCode)
                }
            }
            shortcut
        }

    /**
     * Preserve key ownership when one of a chord's own modifiers comes up, while updating the
     * identity expected from subsequent OS repeats. Unrelated and lock-key releases do nothing.
     */
    fun modifierReleased(event: KeyEvent) {
        val newModifiers = AwtModifierSnapshot.from(event).without(event.keyCode)
        synchronized(lock) {
            states.replaceAll { _, state ->
                if (state is KeyState.Held && state.shortcut.usedModifier(event.keyCode)) {
                    state.copy(shortcut = state.shortcut.copy(modifiers = newModifiers))
                } else {
                    state
                }
            }
        }
    }

    /**
     * Atomically claim a browser-print press for the native callback.
     *
     * A native-first callback leaves a marker that makes the later AWT press disarmed. An AWT
     * release-first marker refuses the native callback. Duplicate callbacks also lose. Returns
     * true only to the one callback that owns the print side effect.
     */
    fun claimNativePrint(windowId: String): Boolean =
        synchronized(lock) {
            when (val current = states[KeyEvent.VK_P]) {
                is KeyState.Held -> {
                    val shortcut = current.shortcut
                    if (shortcut.windowId == windowId && shortcut.firesOnRelease && shortcut.releaseActionArmed) {
                        states[KeyEvent.VK_P] = current.copy(shortcut = shortcut.copy(releaseActionArmed = false))
                        true
                    } else {
                        false
                    }
                }

                is KeyState.NativePrintWon,
                is KeyState.NativePrintCompleted,
                is KeyState.AwtPrintWon,
                -> {
                    if (current.windowId == windowId) {
                        false
                    } else {
                        states[KeyEvent.VK_P] = KeyState.NativePrintWon(windowId)
                        true
                    }
                }

                is KeyState.NativePrintReleased -> {
                    states[KeyEvent.VK_P] = KeyState.NativePrintWon(windowId)
                    true
                }

                null -> {
                    states[KeyEvent.VK_P] = KeyState.NativePrintWon(windowId)
                    true
                }
            }
        }

    /**
     * Mark this window's native winner released when JxBrowser reports physical key-up.
     *
     * The next native press may replace this state immediately. A lagging AWT press from the
     * released chord still becomes a disarmed held record, preventing a second print.
     */
    fun releaseNativePrint(windowId: String) {
        synchronized(lock) {
            when (val current = states[KeyEvent.VK_P]) {
                is KeyState.NativePrintWon -> {
                    if (current.windowId == windowId) {
                        states[KeyEvent.VK_P] = KeyState.NativePrintReleased(windowId)
                    }
                }

                is KeyState.NativePrintCompleted,
                is KeyState.AwtPrintWon,
                -> {
                    if (current.windowId == windowId) states.remove(KeyEvent.VK_P)
                }

                // KeyState.Held is deliberately a no-op: an AWT KEY_PRESSED has already claimed
                // ownership and will complete or transition this chord through [release].
                is KeyState.NativePrintReleased,
                is KeyState.Held,
                null,
                -> {
                    Unit
                }
            }
        }
    }

    fun removeWindow(windowId: String) {
        synchronized(lock) { states.entries.removeIf { it.value.windowId == windowId } }
    }

    fun clear() {
        synchronized(lock) { states.clear() }
    }
}
