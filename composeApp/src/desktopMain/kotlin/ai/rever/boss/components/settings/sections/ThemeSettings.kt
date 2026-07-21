package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.plugin.ui.BossAppTheme
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.theme.AppThemeSettingsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App theme picker — mirrors BossTerm's theme settings. Lists the host themes
 * (Operator / Daylight / Clean); selecting one applies it live and persists it.
 */
@Composable
fun ThemeSettings() {
    val selectedId = BossThemeController.currentId // reactive — recomposes on switch

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(
            title = "App Theme",
            description = "Choose the BOSS look. Applies instantly across the app.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BossThemes.all.forEach { theme ->
                    ThemeCard(
                        theme = theme,
                        isSelected = theme.id == selectedId,
                        onClick = { AppThemeSettingsManager.select(theme.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: BossAppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val preview = theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.line,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Mini preview of the theme's own palette (so each card previews itself).
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(preview.ink)
                .border(1.dp, preview.line, RoundedCornerShape(6.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(preview.panel)
            Swatch(preview.signal)
            Swatch(preview.data)
            Swatch(preview.textPrimary)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.name,
                color = BossTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                text = theme.blurb,
                color = BossTheme.colors.textSecondary,
                fontSize = 12.sp,
            )
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = BossTheme.colors.signal,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}
