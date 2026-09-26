package ai.rever.boss.keymap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Retained until acknowledged, including when its owning window closes before showing it. */
internal class KeymapRecoveryNotice(
    val preservedFile: String?,
)

internal data class KeymapRecoveryClaim(
    val notice: KeymapRecoveryNotice,
    val owner: Any? = null,
)

/** One process-local notice. Closing a window releases ownership; only user acknowledgement retires it. */
internal object KeymapRecoveryNotices {
    private val _pending = MutableStateFlow<KeymapRecoveryClaim?>(null)
    val pending = _pending.asStateFlow()

    fun publish(preservedFile: String?) {
        _pending.value = KeymapRecoveryClaim(KeymapRecoveryNotice(preservedFile))
    }

    /** Call from a committed composition's effect, never from remember's calculation. */
    fun claim(owner: Any): KeymapRecoveryNotice? {
        var current = _pending.value
        while (current != null && current.owner == null) {
            if (_pending.compareAndSet(current, current.copy(owner = owner))) return current.notice
            current = _pending.value
        }
        return null
    }

    fun release(owner: Any) {
        _pending.update { if (it?.owner === owner) it.copy(owner = null) else it }
    }

    fun acknowledge(owner: Any) {
        _pending.update { if (it?.owner === owner) null else it }
    }
}
