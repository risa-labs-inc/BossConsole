package ai.rever.boss.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector

// Host mirror omits the API JAR's documentation-only HostImplemented marker to avoid an API dependency cycle.

/**
 * Native control: [symbol] is an SF Symbol name, [icon] its Compose/vector fallback, and [active]
 * is the service's selected state. Preserve this constructor; add compatible overloads instead.
 */
class TerminalTitleBarAction(
    val id: String,
    val label: String,
    val symbol: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

/**
 * UI-thread-only bridge shared parent-first with BossConsole. API 1.0.95 introduces these types;
 * native hosting requires a supporting host. Future members require a host release/minBossVersion.
 * Unsupported hosts leave windows unclaimed, retaining terminal floating controls.
 *
 * The terminal plugin registers one provider, pairing registration with unregister on unload.
 * Each composed provider pairs publish/remove and publishCallBar/removeCallBar on disposal.
 * Publish from effects with stable callbacks. Owners are compared by identity, never equals.
 * The most recently changed registration/publication wins when owners overlap; removing it
 * restores the previous owner. Entry [publish] active means eligible, unlike action selected state.
 * Hosts release their window claims on disposal; plugin cleanup cannot release a host claim.
 */
// One cohesive public bridge has three paired lifecycle protocols; keep existing ABI call sites.
@Suppress("TooManyFunctions")
object TerminalTitleBarBridge {
    private data class Entry(
        val windowId: String,
        val active: Boolean,
        val actions: List<TerminalTitleBarAction>,
    )

    private data class CallBar(
        val windowId: String,
        val content: @Composable () -> Unit,
    )

    private val providers = TerminalOwners<@Composable (String) -> Unit>()
    private val callBars = TerminalOwners<CallBar>()
    private val entries = TerminalOwners<Entry>()
    private val windows = mutableStateMapOf<String, Boolean>()

    fun registerProvider(
        owner: Any,
        content: @Composable (String) -> Unit,
    ) {
        providers.put(owner, content)
    }

    fun unregisterProvider(owner: Any) {
        providers.remove(owner)
    }

    @Composable
    fun Content(windowId: String) {
        if (isHosted(windowId)) {
            providers.last()?.let { entry ->
                key(entry.key, windowId) { entry.value(windowId) }
            }
        }
    }

    fun publishCallBar(
        windowId: String,
        owner: Any,
        content: @Composable () -> Unit,
    ) {
        callBars.put(owner, CallBar(windowId, content))
    }

    fun removeCallBar(owner: Any) {
        callBars.remove(owner)
    }

    fun hasCallBar(windowId: String): Boolean = callBars.last { it.windowId == windowId } != null

    @Composable
    fun CallBarContent(windowId: String) {
        callBars.last { it.windowId == windowId }?.let { entry ->
            key(entry.key, windowId) { entry.value.content() }
        }
    }

    fun hostWindow(
        windowId: String,
        enabled: Boolean,
    ) {
        if (enabled) windows[windowId] = true else windows.remove(windowId)
    }

    fun isHosted(windowId: String): Boolean = windows[windowId] == true

    fun publish(
        windowId: String,
        owner: Any,
        active: Boolean,
        actions: List<TerminalTitleBarAction>,
    ) {
        entries.put(owner, Entry(windowId, active, actions))
    }

    fun remove(owner: Any) {
        entries.remove(owner)
    }

    fun actions(windowId: String): List<TerminalTitleBarAction> =
        entries
            .last { it.windowId == windowId && it.active }
            ?.value
            ?.actions
            .orEmpty()
}

/** Identity-owned, explicitly ordered state. Unchanged effects do not invalidate composition. */
private class TerminalOwners<T> {
    // Wrapper identity also gives Compose key() identity semantics for equal-but-distinct owners.
    class Owned<T>(
        val owner: Any,
        val value: T,
        val key: Any = Any(),
    )

    private val values = mutableStateListOf<Owned<T>>()

    fun put(
        owner: Any,
        value: T,
    ) {
        val index = values.indexOfFirst { it.owner === owner }
        if (index >= 0 && values[index].value == value) return
        val key = if (index >= 0) values.removeAt(index).key else Any()
        values.add(Owned(owner, value, key))
    }

    fun remove(owner: Any) {
        val index = values.indexOfFirst { it.owner === owner }
        if (index >= 0) values.removeAt(index)
    }

    fun last(predicate: (T) -> Boolean = { true }): Owned<T>? = values.lastOrNull { predicate(it.value) }
}
