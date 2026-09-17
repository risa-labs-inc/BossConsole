package ai.rever.boss.plugin.sandbox.notification

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The toast Copy button writes the toast's text to the clipboard, so an error or id in a toast that
 * may auto-dismiss is not lost.
 */
class PluginToastCopyUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeClipboard : ClipboardManager {
        private var captured: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            captured = annotatedString
        }

        override fun getText(): AnnotatedString? = captured
    }

    @Test
    fun `copy button puts the toast text on the clipboard`() {
        val clipboard = FakeClipboard()
        var dismissed = false

        rule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                PluginToast(
                    message =
                        ToastMessage(
                            type = ToastType.ERROR,
                            title = "Build failed",
                            message = "Exit code 1",
                        ),
                    onDismiss = { dismissed = true },
                )
            }
        }

        rule.onNodeWithContentDescription("Copy notification text").performClick()
        rule.waitForIdle()

        assertEquals("Build failed\nExit code 1", clipboard.getText()?.text)
        assertFalse(dismissed, "Copy must not dismiss the toast")
    }
}
