package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PluginRecoveryNavigation(
    target: PluginRecoveryTarget?,
    targetPresent: Boolean,
    working: Boolean,
    onOpenToolbox: (() -> Boolean)?,
    onDismiss: () -> Unit,
) {
    var navigationFailed by remember(target) { mutableStateOf(false) }
    if (target != null) {
        Spacer(Modifier.height(10.dp))
        if (target.reason != null) Text("Why recovery was opened", fontSize = 12.sp)
        Text(target.reason ?: "Review this installed tool before enabling or reloading it.")
        Text(target.pluginId, fontSize = 11.sp, color = BossTheme.colors.textSecondary)
        if (!targetPresent) {
            Text("This plugin is no longer listed in this window. Check the Toolbox for its current status.")
        }
        onOpenToolbox?.let { open ->
            TextButton(
                enabled = !working,
                onClick = {
                    if (open()) onDismiss() else navigationFailed = true
                },
            ) { Text("Open Toolbox") }
        }
        if (navigationFailed) Text("The Toolbox is not available in this window yet.")
    }
}
