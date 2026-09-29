package ai.rever.boss.plugin.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Tab lifetime, unlike ActiveBrowserRegistry's webpage-view composition lifetime. */
internal object BrowserTabOwnership {
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    val handleIds = bindings.asStateFlow()

    fun bind(
        tabId: String,
        handleId: String,
    ) {
        bindings.update { previous -> previous.filterValues { it != handleId } + (tabId to handleId) }
    }

    /** Closing an old handle must not remove a replacement that now owns the same tab. */
    fun unbind(handleId: String) {
        bindings.update { previous -> previous.filterValues { it != handleId } }
    }
}
