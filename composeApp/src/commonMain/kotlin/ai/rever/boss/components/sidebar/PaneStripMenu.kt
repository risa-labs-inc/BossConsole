package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Background actions for the pane strip itself, independent of the vertical sidebar. */
@Composable
internal fun rememberPaneStripMenu(openNewTab: () -> Unit): List<ContextMenuItem> {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val scope = rememberCoroutineScope()
    return listOf(
        ContextMenuItem("New Tab in This Pane", Icons.Default.Add, onClick = openNewTab),
        ContextMenuItem(isDivider = true),
        ContextMenuItem(
            if (settings.paneTabStripOnlyWhenSplit) "Show Strip in Every Pane" else "Show Strip Only When Split",
            Icons.Outlined.Tab,
            onClick = {
                scope.launch {
                    val current = WindowAppearanceSettingsManager.currentSettings.value
                    WindowAppearanceSettingsManager.updateSettings(
                        current.copy(paneTabStripOnlyWhenSplit = !current.paneTabStripOnlyWhenSplit),
                    )
                }
            },
        ),
        ContextMenuItem(
            "Hide Pane Tab Strip",
            Icons.Outlined.Tab,
            onClick = {
                scope.launch {
                    val current = WindowAppearanceSettingsManager.currentSettings.value
                    WindowAppearanceSettingsManager.updateSettings(current.copy(showPaneTabStrip = false))
                }
            },
        ),
    )
}
