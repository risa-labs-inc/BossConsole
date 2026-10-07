package ai.rever.boss.updater

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

@Composable
internal fun PluginAutomaticUpdatePreferences() {
    val manager = DynamicPluginManager.anyActiveManager() ?: return
    val plugins by manager.pluginStates.collectAsState()
    val optOuts by UpdateSettings.pluginAutoUpdateOptOuts.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide per-plugin preferences" else "Manage per-plugin automatic updates")
    }
    if (expanded) {
        Column {
            Text("All plugins update automatically unless unchecked below.", fontSize = 12.sp)
            plugins.values
                .filterNot {
                    it.manifest.pluginId in PluginDependencyResolution.NOT_USER_INSTALLABLE
                }.sortedBy { it.manifest.displayName }
                .forEach { plugin ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(plugin.manifest.displayName, color = BossTheme.colors.textPrimary)
                        Checkbox(
                            checked = plugin.manifest.pluginId !in optOuts,
                            onCheckedChange = { enabled ->
                                UpdateSettings.setPluginAutomaticUpdates(plugin.manifest.pluginId, enabled)
                                UpdatePreferenceWriter.instance.requestSave()
                            },
                        )
                    }
                }
        }
    }
}
