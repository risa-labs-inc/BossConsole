package ai.rever.boss.theme

import ai.rever.boss.components.sidebar.integratedSidebarSurface
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossBlueprintLightColorScheme
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.LocalBossColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GlassSurfaceRenderingTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `real sidebar cuts out main tint and stays continuous through its header`() {
        org.junit.Assume.assumeTrue(ai.rever.boss.utils.SystemUtils.isMacOS)
        val light = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(
                LocalBossColors provides if (light.value) BossBlueprintLightColorScheme else BossBlueprintColorScheme,
                LocalWindowGlass provides WindowGlass(installed = true, coverage = "window"),
            ) {
                Box(Modifier.size(120.dp).background(Color.Red).testTag("glass")) {
                    GlassAppSurfaces {
                        Box(
                            Modifier
                                .offset(y = 40.dp)
                                .size(60.dp, 80.dp)
                                .then(integratedSidebarSurface(enabled = true, extendsIntoTitleBar = true)),
                        )
                    }
                }
            }
        }
        for (isLight in listOf(false, true)) {
            rule.runOnIdle { light.value = isLight }
            val pixels = rule.onNodeWithTag("glass").captureToImage().toPixelMap()
            val sidebarX = pixels.width / 4
            val mainX = pixels.width * 3 / 4
            val headerY = pixels.height / 5
            val bodyY = pixels.height / 2
            assertEquals(pixels[sidebarX, headerY], pixels[sidebarX, bodyY])
            assertEquals(pixels[mainX, headerY], pixels[mainX, bodyY])
            assertNotEquals(pixels[sidebarX, bodyY], pixels[mainX, bodyY])
        }
    }

    @Test
    fun `sidebar content and unpainted header share one fill in both palettes`() {
        val light = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(
                LocalBossColors provides if (light.value) BossBlueprintLightColorScheme else BossBlueprintColorScheme,
                LocalWindowGlass provides WindowGlass(installed = true, coverage = "window"),
            ) {
                Box(Modifier.size(60.dp).background(Color.Red).testTag("glass")) {
                    GlassAppSurfaces {
                        // One child surface and two overlapping surfaces must match the header gap.
                        Box(Modifier.offset(20.dp).size(20.dp).background(BossTheme.colors.panel))
                        Box(Modifier.offset(40.dp).size(20.dp).background(BossTheme.colors.ink)) {
                            Box(Modifier.size(20.dp).background(BossTheme.colors.panel))
                        }
                    }
                }
            }
        }
        for (isLight in listOf(false, true)) {
            rule.runOnIdle { light.value = isLight }
            val pixels = rule.onNodeWithTag("glass").captureToImage().toPixelMap()
            val y = pixels.height / 6
            val header = pixels[pixels.width / 6, y]
            assertNotEquals(Color.Red, header)
            assertEquals(header, pixels[pixels.width / 2, y])
            assertEquals(header, pixels[pixels.width * 5 / 6, y])
        }
    }
}
