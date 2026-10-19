package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.plugin.ui.BossAppTheme
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.theme.isGlassTheme
import ai.rever.boss.utils.SystemUtils
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App theme picker — mirrors BossTerm's theme settings. Lists the host themes
 * from [BossThemes.all]; selecting one applies it live and persists it.
 */
@Composable
fun ThemeSettings() {
    val selectedId = BossThemeController.currentId // reactive — recomposes on switch

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isGlassTheme(selectedId)) GlassThemeSettings()
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
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.line,
                    shape = RoundedCornerShape(8.dp),
                ).clickable(onClick = onClick)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Mini preview of the theme's own palette (so each card previews itself).
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(preview.ink)
                    .border(1.dp, preview.line, RoundedCornerShape(6.dp))
                    .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Each swatch carries the theme's own hairline: in the light themes
            // `panel` and `raised` are pure white on a near-white `ink`, so an
            // unbordered swatch renders as nothing at all (1.05:1).
            Swatch(preview.panel, preview.line)
            Swatch(preview.signal, preview.line)
            Swatch(preview.data, preview.line)
            Swatch(preview.textPrimary, preview.line)
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
                tint = BossTheme.colors.signalText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    border: Color,
) {
    Box(
        modifier =
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .border(1.dp, border, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun GlassThemeSettings() {
    val settings by AppThemeSettingsManager.settings.collectAsState()
    var tint by remember(settings.glassTint) { mutableStateOf(settings.glassTint) }
    SettingsSection(
        title = "Glass",
        description =
            if (SystemUtils.isMacOS) {
                "Native Liquid Glass on macOS 26, vibrancy on earlier macOS. " +
                    "Browser pages and opaque plugin content keep their backgrounds."
            } else {
                "Native glass currently requires macOS. " +
                    "Surfaces use the opaque glass palette on this platform."
            },
    ) {
        val coverage = linkedMapOf("Off" to "off", "Sidebar and bars" to "sidebar", "App surfaces" to "window")
        SettingsDropdown(
            label = "Glass coverage",
            options = coverage.keys.toList(),
            selectedOption =
                coverage.entries.firstOrNull { it.value == settings.glassCoverage }?.key ?: "App surfaces",
            onOptionSelected = {
                AppThemeSettingsManager.updateGlass(coverage.getValue(it), settings.glassStyle, settings.glassTint)
            },
        )
        SettingsDropdown(
            label = "Glass style",
            options = listOf("Regular", "Clear"),
            selectedOption = if (settings.glassStyle == "clear") "Clear" else "Regular",
            onOptionSelected = {
                AppThemeSettingsManager.updateGlass(settings.glassCoverage, it.lowercase(), settings.glassTint)
            },
            description = "Regular and Clear use the native macOS 26 material.",
        )
        SettingsSlider(
            label = "Glass tint",
            value = tint,
            onValueChange = { tint = it },
            onValueChangeFinished = {
                AppThemeSettingsManager.updateGlass(settings.glassCoverage, settings.glassStyle, tint)
            },
            valueRange = 0f..1f,
            valueDisplay = { "${(it * 100).toInt()}%" },
            description = "Color over the glass. Lower values show more of the native backdrop.",
        )
        GlassBackgroundOpacity()
    }
}

@Composable
private fun GlassBackgroundOpacity() {
    val settings by AppThemeSettingsManager.settings.collectAsState()
    var opacity by remember(settings.glassOpacity) { mutableStateOf(settings.glassOpacity) }
    if (settings.glassCoverage != "window") return
    SettingsSlider(
        label = "Background opacity",
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = {
            AppThemeSettingsManager.updateGlass(
                settings.glassCoverage,
                settings.glassStyle,
                settings.glassTint,
                opacity,
            )
        },
        valueRange = 0f..1f,
        valueDisplay = { "${(it * 100).toInt()}%" },
        description = "Opacity of app content; Glass tint adds theme color above it, as in BossTerm.",
    )
}
