package ai.rever.boss.keymap

import java.util.concurrent.atomic.AtomicReference

/** A startup recovery is recorded before a window exists; the first window claims its notice. */
internal data class KeymapRecoveryNotice(
    val preservedFile: String?,
)

internal object KeymapRecoveryNotices {
    private val pending = AtomicReference<KeymapRecoveryNotice?>()

    fun publish(preservedFile: String?) {
        pending.set(KeymapRecoveryNotice(preservedFile))
    }

    fun claim(): KeymapRecoveryNotice? = pending.getAndSet(null)
}
