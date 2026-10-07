package ai.rever.boss.window

import java.util.concurrent.atomic.AtomicBoolean

/** Window transitions may synchronously call AWT. Never wait on AppKit while it waits on the EDT. */
@Suppress("TooGenericExceptionCaught") // Restore the queue guard before propagating any native-dispatch failure.
internal fun queueNativeWindowAction(
    validUntilMillis: Long,
    pending: AtomicBoolean,
    authorized: () -> Boolean,
    validate: () -> Boolean,
    action: () -> Unit,
): Boolean {
    if (!pending.compareAndSet(false, true)) return false
    val deadline = minOf(validUntilMillis, System.currentTimeMillis() + 150)
    val current = { System.currentTimeMillis() < deadline && authorized() && validate() }
    return try {
        if (!scopedNativeToolbarCall(deadline, current)) {
            pending.set(false)
            false
        } else {
            MacToolbarRuntime.dispatch {
                try {
                    // Revoke/geometry/session teardown invalidates the sink's authority epoch.
                    // Re-check borrowed native ownership and expiry at execution, not just enqueue.
                    if (current() && System.currentTimeMillis() < deadline && authorized()) action()
                } finally {
                    pending.set(false)
                }
            }
            true // Admitted for dispatch; the caller observes the actual window transition separately.
        }
    } catch (failure: Exception) {
        pending.set(false)
        throw failure
    }
}
