package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossThemeController
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Resolve from the base palette, before GlassAppSurfaces makes window panels transparent. */
internal val dialogPanelColor: Color
    @Composable get() = opaqueDialogPanel(BossThemeController.current.colors)

/** Preserve a palette's translucent tint by blending it over its opaque base. */
internal fun opaqueDialogPanel(colors: BossColorScheme): Color = colors.panel.compositeOver(colors.ink.copy(alpha = 1f))
