package ai.rever.boss.components.bars.horizontal

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the top bar's three right-hand buttons still reach their callbacks.
 *
 * Sign Out is the one that needed a test. `BossTopRightBar` used to own the confirmation dialog
 * outright - its own `showLogoutDialog` and its own `LogoutConfirmationDialog` - and that moved to
 * `BossAppState` so the focus-mode quick actions could raise the same dialog without there being
 * two of them. Ownership moves like that break quietly: the button still renders, still hovers,
 * still has its hint, and does nothing at all. Nothing else in the build would notice.
 *
 * The buttons are icon-only (`BossActionButton` in `imageVector` mode draws no text), so `text` is
 * the content description and that is what they are found by here.
 */
class BossTopRightBarTest {
    @get:Rule
    val rule = createComposeRule()

    private fun clickAndRecord(contentDescription: String): List<String> {
        val fired = mutableListOf<String>()
        rule.setContent {
            Row {
                BossTopRightBar(
                    onShowSettings = { fired += "settings" },
                    onShowSearch = { fired += "search" },
                    onSignOut = { fired += "signOut" },
                )
            }
        }
        rule.onNodeWithContentDescription(contentDescription).performClick()
        rule.waitForIdle()
        return fired
    }

    @Test
    fun `sign out reaches its callback rather than a dialog this bar no longer owns`() {
        assertEquals(listOf("signOut"), clickAndRecord("Sign Out"))
    }

    @Test
    fun `search reaches its callback`() {
        assertEquals(listOf("search"), clickAndRecord("Search"))
    }

    @Test
    fun `settings reaches its callback`() {
        assertEquals(listOf("settings"), clickAndRecord("Settings"))
    }

    @Test
    fun `the bar raises no confirmation of its own`() {
        // The whole point of moving ownership: the quick actions offer the same action, and two
        // owners would stack two confirmations. This bar must now raise none.
        //
        // Anchored on the dialog's own title rather than on the button label. The buttons are
        // icon-only, so "Sign Out" is a content description and never appears as TEXT here - an
        // earlier version of this test asserted on the text and was really asserting that the
        // button exists, which it does not.
        val fired = clickAndRecord("Sign Out")

        assertTrue(fired.isNotEmpty(), "the callback is the only path left, so it has to fire")
        assertEquals(
            0,
            rule.onAllNodesWithText(LOGOUT_DIALOG_TITLE).fetchSemanticsNodes().size,
            "this bar drew a confirmation again, so the cluster's would be a second one",
        )
    }

    private companion object {
        /** `LogoutConfirmationDialog`'s title - the cheapest proof that it is or is not up. */
        const val LOGOUT_DIALOG_TITLE = "Confirm Logout"
    }
}
