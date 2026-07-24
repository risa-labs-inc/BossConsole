package ai.rever.boss.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long callers wait for a dynamically-loading plugin to register its UI
 * contributions (tab types, panels) before proceeding without them. Plugins —
 * including the built-in browser/terminal/editor tab types — load
 * asynchronously at startup, so consumers that resolve registry entries early
 * (session restore, startup panel opens) race against registration.
 */
const val PLUGIN_REGISTRATION_TIMEOUT_MS = 15_000L

/**
 * Suspend until [condition] is true, driven by a registry change listener
 * ([TabRegistry][ai.rever.boss.plugin.api.TabRegistry] and
 * [PanelRegistry][ai.rever.boss.plugin.api.PanelRegistry] share the same
 * listener shape), or until [timeoutMs] elapses.
 *
 * Call from Dispatchers.Main only. The registries' listener lists are plain
 * (unsynchronized) MutableLists and notifyChange() iterates them directly, so
 * this helper is only safe because listener add/remove (here) and plugin
 * registration (notifyChange) are all confined to the main thread — the
 * finally-removal below resumes on the caller's dispatcher. A background
 * caller would race notifyChange() and risk ConcurrentModificationException.
 *
 * @return true if the condition held (immediately or after changes),
 *         false if the timeout expired first.
 */
suspend fun awaitRegistryCondition(
    addListener: (() -> Unit) -> Unit,
    removeListener: (() -> Unit) -> Unit,
    timeoutMs: Long = PLUGIN_REGISTRATION_TIMEOUT_MS,
    condition: () -> Boolean,
): Boolean {
    if (condition()) return true

    val satisfied = CompletableDeferred<Unit>()
    // Completing the deferred is the only work done in the callback: removing
    // the listener from inside it would mutate the registry's listener list
    // while notifyChange() is iterating it.
    val listener: () -> Unit = { if (condition()) satisfied.complete(Unit) }
    addListener(listener)
    return try {
        // Re-check after subscribing: the condition may have become true
        // between the first check and the listener registration.
        if (condition()) {
            true
        } else {
            withTimeoutOrNull(timeoutMs) { satisfied.await() } != null
        }
    } finally {
        removeListener(listener)
    }
}
