package ai.rever.boss.plugin.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf

/**
 * Browser-owned navigation state presented by an optional native host toolbar.
 * Preserve this constructor when extending the contract; add compatible overloads instead.
 */
// Constructor shape is the published plugin ABI; keep it compatible.
@Suppress("LongParameterList")
class BrowserTitleBarState(
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val loading: Boolean,
    val bookmarked: Boolean,
    val navigate: (String) -> Unit,
    val back: () -> Unit,
    val forward: () -> Unit,
    val reloadOrStop: () -> Unit,
    val bookmark: () -> Unit,
    val share: (() -> Unit)? = null,
    val address: BrowserAddressBarState? = null,
)

/**
 * UI-thread-only handshake; [focus] invokes its callback synchronously on the caller thread.
 * The browser package is shared parent-first. On newer hosts the host-compiled copy runs;
 * older hosts use the API JAR copy, which remains unclaimed and keeps the in-tab toolbar.
 * API 1.0.94 makes these types available; native hosting additionally requires a supporting host.
 * Future member additions need a host release and minBossVersion gating.
 *
 * Plugins pair every [publish] with [remove] during composition disposal, including unload.
 * Hosts independently own window claims and focus registrations and must release both on disposal.
 * Browser publication cleanup deliberately cannot release a host-owned focus registration.
 * A browser hides its toolbar when its window or handle is hosted.
 */
object BrowserTitleBarBridge {
    private class Entry(
        val owner: Any,
        val state: BrowserTitleBarState,
    )

    private val entries = mutableStateMapOf<String, Entry>()
    private val windows = mutableStateMapOf<String, Boolean>()

    fun hostWindow(
        windowId: String,
        enabled: Boolean,
    ) {
        if (enabled) windows[windowId] = true else windows.remove(windowId)
    }

    fun isWindowHosted(windowId: String): Boolean = windows[windowId] == true

    private class Host(
        val owner: Any,
        val focus: () -> Unit,
    )

    private val hosts = mutableStateMapOf<String, Host>()
    private val legacyHostOwner = Any()

    fun state(handleId: String): BrowserTitleBarState? = entries[handleId]?.state

    fun publish(
        handleId: String,
        owner: Any,
        state: BrowserTitleBarState,
    ) {
        entries[handleId] = Entry(owner, state)
    }

    fun remove(
        handleId: String,
        owner: Any,
    ) {
        if (entries[handleId]?.owner === owner) {
            entries.remove(handleId)
        }
    }

    fun host(
        handleId: String,
        focus: (() -> Unit)?,
    ) = host(handleId, legacyHostOwner, focus)

    /** A disposed window cannot unregister the focus handler of a newer host. */
    fun host(
        handleId: String,
        owner: Any,
        focus: (() -> Unit)?,
    ) {
        if (focus != null) {
            hosts[handleId] = Host(owner, focus)
        } else if (hosts[handleId]?.owner === owner) {
            hosts.remove(handleId)
        }
    }

    fun isHosted(handleId: String): Boolean = hosts.containsKey(handleId)

    fun focus(handleId: String): Boolean {
        val focus = hosts[handleId]?.focus ?: return false
        focus()
        return true
    }
}

/**
 * Editing and suggestions remain owned by the browser plugin, including keyboard semantics.
 * Commands: submit, next, previous, accept, right, cancel, delete. Unknown commands are ignored.
 * Revision changes acknowledge commands even when text is unchanged; hasSelectedSuggestion
 * indicates that keyboard navigation currently selects a suggestion.
 * Preserve this constructor when extending the contract; add compatible overloads instead.
 */
// Constructor shape is the published plugin ABI; keep it compatible.
@Suppress("LongParameterList")
class BrowserAddressBarState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val completion: String?,
    val showSuggestions: Boolean,
    val hasSelectedSuggestion: Boolean,
    val revision: Int,
    val onEdit: (String, Int, Int) -> Unit,
    val onCommand: (String) -> Unit,
    val onFocusLost: () -> Unit,
    val onDismiss: () -> Unit,
    val suggestions: @Composable () -> Unit,
)
