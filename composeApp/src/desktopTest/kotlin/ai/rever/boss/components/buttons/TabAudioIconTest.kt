package ai.rever.boss.components.buttons

import ai.rever.boss.components.plugin.TabAudioSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TabAudioIconTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `both favicon sizes fade immediately without moving the title`() {
        val source = TabAudioSource { it() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Column {
                listOf(14, 16).forEach { size ->
                    Row(Modifier.width(180.dp)) {
                        TabAudioIcon("ui-audio", Modifier.size(size.dp).testTag("icon-$size")) {
                            Box(Modifier.size(size.dp).background(Color.Red))
                        }
                        Text("An unchanged tab title", Modifier.testTag("title-$size"))
                    }
                }
            }
        }
        val before = listOf(14, 16).map { rule.onNodeWithTag("title-$it").getBoundsInRoot() }
        val silent = rule.onNodeWithTag("icon-16").captureToImage().toPixelMap()[0, 0]
        try {
            rule.runOnIdle {
                source.bind("ui-audio")
                source.update(true)
            }
            rule.mainClock.advanceTimeBy(80)
            rule.waitForIdle()
            val fading = rule.onNodeWithTag("icon-16").captureToImage().toPixelMap()[0, 0]
            assertNotEquals(silent, fading, "favicon begins fading before the old one-second refresh")
            rule.mainClock.advanceTimeBy(240)
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("Playing audio")[0].assertIsDisplayed()
            rule.onAllNodesWithContentDescription("Playing audio")[1].assertIsDisplayed()
            val playing = rule.onNodeWithTag("icon-16").captureToImage().toPixelMap()[0, 0]
            assertNotEquals(fading, playing, "fade has an intermediate visual state")
            assertEquals(before, listOf(14, 16).map { rule.onNodeWithTag("title-$it").getBoundsInRoot() })
            rule.runOnIdle { source.update(false) }
            rule.mainClock.advanceTimeBy(80)
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("Playing audio")[0].assertIsDisplayed()
            rule.mainClock.advanceTimeBy(240)
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Playing audio").assertDoesNotExist()
            assertEquals(before, listOf(14, 16).map { rule.onNodeWithTag("title-$it").getBoundsInRoot() })
            assertEquals(silent, rule.onNodeWithTag("icon-16").captureToImage().toPixelMap()[0, 0])
        } finally {
            rule.runOnIdle { source.close() }
        }
    }
}
