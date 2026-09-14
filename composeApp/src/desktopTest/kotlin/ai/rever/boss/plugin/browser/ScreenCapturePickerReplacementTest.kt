package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ScreenCapturePickerReplacementTest {
    @get:Rule
    val rule = createComposeRule()

    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @After
    fun restoreRenderer() {
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    @Test
    fun `replacement requires a fresh selection even when the source index is unchanged`() {
        val requestId = mutableStateOf("first")
        BossOverlayHost.useHeavyweightOverlays = true
        // Render the real picker in the test scene without creating a native application window.
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                ScreenCapturePickerDialog(
                    requestId = requestId.value,
                    screens =
                        listOf(
                            ScreenCaptureNotifier.CaptureSourceItem(
                                "Screen ${requestId.value}",
                                ScreenCaptureNotifier.CaptureSourceItem.Category.SCREEN,
                                0,
                            ),
                        ),
                    windows = emptyList(),
                    browsers = emptyList(),
                    onDismiss = {},
                    onSelect = { _, _ -> },
                )
            }
        }
        rule.onNodeWithText("Share", substring = false).assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(250)
        rule.onNodeWithText("Screen first").performClick()
        rule.onNodeWithText("Share", substring = false).assertIsEnabled()
        rule.runOnIdle { requestId.value = "second" }
        rule.onNodeWithText("Screen second").assertExists()
        rule.onNodeWithText("Share", substring = false).assertIsNotEnabled()
    }
}
