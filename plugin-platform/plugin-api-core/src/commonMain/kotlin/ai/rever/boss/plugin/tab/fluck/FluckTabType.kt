package ai.rever.boss.plugin.tab.fluck

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language

/**
 * Fluck (browser) tab type info.
 *
 * This tab type provides an embedded browser using JxBrowser
 * with full web browsing capabilities.
 */
object FluckTabType : TabTypeInfo {
    override val typeId = TabTypeId("fluck")
    override val displayName = "Browser"
    override val icon = Icons.Outlined.Language
}
