package ai.rever.boss.components.window_panel

import ai.rever.boss.components.buttons.BossTabButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the pairing of [BossTabButton]'s context-menu visibility reports.
 *
 * The listener counts them as deltas - several tabs can each have a menu up, so a single Boolean
 * would be cleared by whichever closed first. That only works if a row reports a transition and
 * never a state: the effect previously reported `showContextMenu` unconditionally, so every row
 * emitted a `false` on its FIRST composition and decremented a count it had never incremented.
 *
 * The consequence was not academic. The window bar's rows share one `LazyColumn` across every
 * pane, so a menu open in the hover-revealed drawer was cancelled by any other row mounting - a
 * scroll, or a background pane opening a tab - which dropped the count to zero, retracted the
 * drawer, and took the open menu's composition with it. That is the exact failure the callback
 * was added to prevent.
 *
 * Composing rows is therefore the whole test: the correct number of reports here is none.
 */
class TabMenuVisibilityReportTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `composing tab rows reports no menu visibility at all`() {
        val reports = mutableListOf<Boolean>()

        compose.setContent {
            Column {
                repeat(3) { index ->
                    BossTabButton(
                        fileName = "tab-$index",
                        onClick = {},
                        vertical = true,
                        onContextMenuVisibilityChange = { open -> reports += open },
                    )
                }
            }
        }
        compose.waitForIdle()

        assertEquals(
            emptyList(),
            reports,
            "A row with no menu open must report nothing. Reporting `false` here decrements a " +
                "count it never incremented, closing a menu another row legitimately has open.",
        )
    }

    @Test
    fun `rows leaving composition report no menu visibility either`() {
        val reports = mutableListOf<Boolean>()
        var rows by mutableStateOf(3)

        compose.setContent {
            Column {
                repeat(rows) { index ->
                    BossTabButton(
                        fileName = "tab-$index",
                        onClick = {},
                        vertical = true,
                        onContextMenuVisibilityChange = { open -> reports += open },
                    )
                }
            }
        }
        compose.waitForIdle()

        // Closing a tab, or the item-key churn a reorder causes, disposes rows. A row whose menu
        // was never open owes nothing on the way out.
        rows = 1
        compose.waitForIdle()

        assertEquals(emptyList(), reports, "Disposing a row with no menu open must report nothing.")
    }
}
