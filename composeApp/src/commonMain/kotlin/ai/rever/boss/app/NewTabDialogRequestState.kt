package ai.rever.boss.app

import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.events.DashboardNewTabEvent
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Transient request owned by one window, separate from the dialog's editable form. */
internal class NewTabDialogRequestState(
    private val windowId: String,
) {
    var initialType by mutableStateOf<TabType?>(null)
    var requestedType by mutableStateOf<TabTypeId?>(null)
    var sourcePanelId by mutableStateOf<String?>(null)
        private set

    fun receive(
        event: DashboardNewTabEvent,
        panelId: String,
    ): Boolean {
        if (event.sourceWindowId != windowId) return false
        requestedType = event.requestedType
        initialType = null
        sourcePanelId = panelId
        return true
    }

    fun dismiss(cancelPendingSplit: () -> Unit) {
        initialType = null
        requestedType = null
        sourcePanelId = null
        cancelPendingSplit()
    }
}
