package ai.rever.boss.components.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class HomeNavigationButtonTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Home favorite activates and exposes selected state`() {
        val selected = mutableStateOf(false)
        var clicks = 0
        rule.setContent {
            HomeNavigationButton(selected = selected.value) {
                clicks++
                selected.value = true
            }
        }
        rule.onNodeWithContentDescription("Home").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        rule.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test
    fun `collapsed Home retains accessible name and action`() {
        var clicks = 0
        rule.setContent { HomeNavigationButton(selected = true, compact = true) { clicks++ } }
        rule.onNodeWithContentDescription("Home").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        rule.onNodeWithContentDescription("Home").assertIsSelected()
    }
}
