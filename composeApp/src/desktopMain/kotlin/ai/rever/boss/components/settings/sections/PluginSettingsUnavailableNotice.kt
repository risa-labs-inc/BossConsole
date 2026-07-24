package ai.rever.boss.components.settings.sections

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown in a settings section whose panel is served by a plugin that hasn't
 * registered its API (not installed yet, still loading, or an older version).
 */
@Composable
internal fun PluginSettingsUnavailableNotice(message: String) {
    Text(
        text = message,
        color = BossTheme.colors.textMuted,
        fontSize = 13.sp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
    )
}
