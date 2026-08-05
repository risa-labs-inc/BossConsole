package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossOverlayHost
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [BossAlertDialog] has to keep doing after replacing Material 2's `AlertDialog`.
 *
 * Material's own body could not be reused - `AlertDialogContent` and its baseline layout are
 * `internal`, and the desktop `dialogProvider` hook that once allowed injecting a container was
 * removed - so the card is rebuilt here from the design-system tokens. That makes it OUR layout, and
 * 25 host call sites now depend on it presenting the same three regions Material did. These tests
 * pin exactly that contract: title, text, and both buttons all reach the screen, and each button
 * still invokes its own lambda.
 *
 * Deliberately on the LIGHTWEIGHT path. The heavyweight path is a separate always-on-top OS window,
 * which a Compose test scene has no way to host; which of the two is chosen is pinned separately and
 * without a display by `BossDialogRoutingTest`.
 */
class BossAlertDialogComposeTest {
    @get:Rule
    val rule = createComposeRule()

    private var savedUseHeavyweight = false

    @Before
    fun forceLightweightPath() {
        savedUseHeavyweight = BossOverlayHost.useHeavyweightOverlays
        BossOverlayHost.useHeavyweightOverlays = false
    }

    @After
    fun restoreOverlayMode() {
        BossOverlayHost.useHeavyweightOverlays = savedUseHeavyweight
    }

    @Test
    fun `title, text and both buttons are all shown`() {
        rule.setContent {
            BossAlertDialog(
                onDismissRequest = {},
                title = { Text("Reset Browser") },
                text = { Text("This clears cookies and cache.") },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                confirmButton = { TextButton(onClick = {}) { Text("Reset") } },
            )
        }

        rule.onNodeWithText("Reset Browser").assertIsDisplayed()
        rule.onNodeWithText("This clears cookies and cache.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Reset").assertIsDisplayed()
    }

    @Test
    fun `each button invokes its own action, and neither is the dismiss request`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            BossAlertDialog(
                onDismissRequest = { clicks += "dismissRequest" },
                title = { Text("Discard changes?") },
                dismissButton = { TextButton(onClick = { clicks += "cancel" }) { Text("Cancel") } },
                confirmButton = { TextButton(onClick = { clicks += "discard" }) { Text("Discard") } },
            )
        }

        rule.onNodeWithText("Discard").performClick()
        rule.onNodeWithText("Cancel").performClick()

        // Order matters as much as membership: the confirm button is rendered last (rightmost), and a
        // shim that swapped the two would put the destructive action where Cancel belongs.
        assertEquals(listOf("discard", "cancel"), clicks)
    }

    @Test
    fun `a dialog with no text renders from the title alone`() {
        // Material allows a title-only or text-only dialog and several host call sites use it, so the
        // rebuilt card must not assume both regions are present.
        rule.setContent {
            BossAlertDialog(
                onDismissRequest = {},
                title = { Text("Git Operation Successful") },
                confirmButton = { TextButton(onClick = {}) { Text("OK") } },
            )
        }

        rule.onNodeWithText("Git Operation Successful").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun `the buttons overload hands the whole action area to the caller`() {
        // GenericDialogHost's three-button plugin dialog is the reason this overload exists.
        val clicks = mutableListOf<String>()
        rule.setContent {
            BossAlertDialog(
                onDismissRequest = {},
                title = { Text("Unsaved work") },
                buttons = {
                    TextButton(onClick = { clicks += "save" }) { Text("Save") }
                    TextButton(onClick = { clicks += "discard" }) { Text("Discard") }
                    TextButton(onClick = { clicks += "cancel" }) { Text("Keep editing") }
                },
            )
        }

        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        assertTrue(clicks == listOf("cancel"), "expected only the clicked button to fire, got $clicks")
    }
}
