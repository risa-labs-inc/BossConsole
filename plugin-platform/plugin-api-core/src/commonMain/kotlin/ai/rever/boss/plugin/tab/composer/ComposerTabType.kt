package ai.rever.boss.plugin.tab.composer

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy

/**
 * Composer tab type (host-provided type, plugin-provided UI).
 *
 * The AI composer tab is rendered by the editor-tab plugin (it owns the
 * buffers the composer edits and the diff machinery for the review), but the
 * tab TYPE and its persistable [ComposerTabInfo] live here so workspace
 * restore can rebuild the tab without the plugin's classes: the host persists
 * the session id, waits for the plugin to register its factory, and drops the
 * tab gracefully when the plugin is absent.
 */
object ComposerTabType : TabTypeInfo {
    override val typeId = TabTypeId("composer")
    override val displayName = "Composer"
    override val icon = Icons.Outlined.SmartToy
}
