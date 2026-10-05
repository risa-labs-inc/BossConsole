package ai.rever.boss.filetypes

import ai.rever.boss.utils.DefaultHandlerState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultAppsOfferTest {
    @get:Rule
    val rule = createComposeRule()

    private val statuses = FileTypeCategories.categories.map {
        DefaultAppStatus(it, DefaultHandlerState.Other(null))
    }

    @Test
    fun `startup offer only shows and claims web links`() {
        var claimed = emptyList<String>()
        rule.setContent {
            DefaultAppsOfferDialog(
                unclaimed = statuses,
                onClose = {},
                claimCategories = { categories ->
                    claimed = categories.map { it.id }
                    ClaimOutcome.Claimed
                },
                recordDeclined = {},
            )
        }
        statuses.filter { it.category.id != "web-links" }.forEach {
            rule.onNodeWithText(it.category.displayName).assertDoesNotExist()
        }
        rule.onNodeWithText("Make default browser").performClick()
        rule.runOnIdle { assertEquals(listOf("web-links"), claimed) }
    }

    @Test
    fun `skip closes the offer without claiming defaults`() {
        var closed = false
        var claimed = false
        rule.setContent {
            DefaultAppsOfferDialog(
                unclaimed = statuses,
                onClose = { closed = true },
                claimCategories = { claimed = true; ClaimOutcome.Claimed },
                recordDeclined = {},
            )
        }
        rule.onNodeWithText("Skip").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertTrue(closed)
            assertEquals(false, claimed)
        }
    }

    @Test
    fun `skip and escape remain available during a pending browser request`() {
        var closes = 0
        rule.setContent {
            DefaultAppsOfferDialog(
                unclaimed = statuses,
                onClose = { closes++ },
                claimCategories = { awaitCancellation() },
                recordDeclined = {},
            )
        }
        rule.onNodeWithText("Make default browser").performClick()
        rule.onNodeWithText("Skip").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, closes) }
        rule.onNodeWithTag("default-browser-offer").performKeyInput { pressKey(Key.Escape) }
        rule.runOnIdle { assertEquals(2, closes) }
    }
}
