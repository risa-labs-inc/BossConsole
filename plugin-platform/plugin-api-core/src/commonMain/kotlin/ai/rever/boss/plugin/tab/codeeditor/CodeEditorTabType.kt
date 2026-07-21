package ai.rever.boss.plugin.tab.codeeditor

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code

/**
 * Code Editor tab type info.
 *
 * This tab type provides a full-featured code editor with
 * syntax highlighting, code folding, and run gutter icons.
 */
object CodeEditorTabType : TabTypeInfo {
    override val typeId = TabTypeId("editor")
    override val displayName = "Code Editor"
    override val icon = Icons.Outlined.Code
}
