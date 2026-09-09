package ai.rever.boss.plugin.tab.composer

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab info for an AI Composer tab.
 *
 * The composer session's state (messages, proposals) lives in the editor-tab
 * plugin's plugin storage, keyed by [sessionId]. Only the id is persisted in
 * the workspace so a restored tab can reload its session; when the plugin (and
 * its store) is absent the host DROPS this tab and restores the rest of the
 * layout - `addTab` refuses a type with no registered factory. The layout
 * survives; the tab does not.
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title (the task, truncated)
 * @param icon Tab icon vector
 * @param tabIcon Tab icon wrapper
 * @param sessionId Opaque session id the editor-tab plugin resolves against its storage
 */
data class ComposerTabInfo(
    override val id: String,
    override val typeId: TabTypeId = ComposerTabType.typeId,
    override val title: String,
    override val icon: ImageVector = Icons.Outlined.SmartToy,
    override val tabIcon: TabIcon? = null,
    val sessionId: String = "",
) : TabInfo {
    companion object {
        /**
         * Build a composer tab for the given session. [title] defaults to the generic name.
         *
         * The tab id IS the session id: the plugin side cannot see this class
         * (the api jar filters it out) and reads the id through the plain
         * TabInfo interface to resolve its session.
         */
        fun create(
            sessionId: String,
            title: String = "Composer",
        ): ComposerTabInfo =
            ComposerTabInfo(
                id = sessionId,
                title = title,
                sessionId = sessionId,
            )
    }
}
