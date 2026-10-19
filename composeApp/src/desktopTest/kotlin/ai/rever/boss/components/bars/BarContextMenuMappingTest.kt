package ai.rever.boss.components.bars

import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [ChromeBar] to settings-field mapping, which three separate surfaces read through:
 * each bar's right-click "Hide", the View menu's checkmarks, and the Settings toggles.
 *
 * The failure this rules out is silent and confusing rather than loud: a [withBarVisible] that
 * wrote the wrong field would hide a *different* bar than the one right-clicked, and an
 * [isBarVisible] that read the wrong one would leave the View menu's checkmark disagreeing with
 * what is actually on screen. Nothing crashes either way, and neither is visible to any test that
 * only looks at one bar - which is why these assert round-trips across all four at once.
 */
class BarContextMenuMappingTest {
    @Test
    fun `only the bottom status bar starts visible`() {
        // These defaults apply on every desktop platform. The View menu can change each bar.
        // The status bar is on by default since #1828; the top bar and icon strips stay off.
        val defaults = WindowAppearanceSettings()

        ChromeBar.entries.forEach { bar ->
            val expected = bar == ChromeBar.BOTTOM
            assertEquals(expected, defaults.isBarVisible(bar), "${bar.name} default visibility")
        }
    }

    @Test
    fun `hiding a bar round-trips through the same field it reads`() {
        ChromeBar.entries.forEach { bar ->
            val hidden = allVisible().withBarVisible(bar, visible = false)

            assertFalse(hidden.isBarVisible(bar), "${bar.name} should read back hidden")
            assertTrue(hidden.withBarVisible(bar, visible = true).isBarVisible(bar))
        }
    }

    @Test
    fun `hiding one bar leaves the other three alone`() {
        // The wrong-field bug: writing showBottomBar from ChromeBar.TOP passes a single-bar test
        // and is caught here.
        //
        // Starts from all-visible explicitly rather than from the defaults. What this asserts is
        // that each ChromeBar reads and writes its OWN field, which is true whatever the product
        // happens to default to - and leaning on the defaults made it fail the day one changed.
        ChromeBar.entries.forEach { bar ->
            val hidden = allVisible().withBarVisible(bar, visible = false)

            ChromeBar.entries.filter { it != bar }.forEach { other ->
                assertTrue(
                    hidden.isBarVisible(other),
                    "hiding ${bar.name} must not disturb ${other.name}",
                )
            }
        }
    }

    @Test
    fun `the title bar is not one of these and is left untouched`() {
        // showTitleBar is the pre-existing flag for a different strip (the 26dp OS-style title bar),
        // and it deliberately has no ChromeBar member - it is not right-clickable and its toggle
        // already lives in Settings. Hiding every bar here must not switch it off as a side effect.
        // Switched ON explicitly: the flag now defaults to false like everything else, and this
        // test is about withBarVisible leaving it alone rather than about what it defaults to.
        val start = WindowAppearanceSettings(showTitleBar = true)
        val allHidden =
            ChromeBar.entries.fold(start) { acc, bar ->
                acc.withBarVisible(bar, visible = false)
            }

        assertTrue(allHidden.showTitleBar)
    }

    @Test
    fun `each bar has its own wording`() {
        // These strings are user-visible in three places and are what the View menu and the Hide
        // item are built from, so a duplicate would produce two identically labelled menu entries.
        val names = ChromeBar.entries.map { it.displayName() }

        assertEquals(names.size, names.distinct().size, "display names must be distinct: $names")
        // By codepoint, not by printing the character - the same dodge AGENTS.md uses where it
        // defines this rule, so a future doc-wide guard cannot flag the assertion that enforces it.
        assertTrue(names.none { it.contains('\u2014') }, "no em-dashes in user-visible strings")
    }

    /** Every bar shown, whatever the product defaults to. The starting point these tests mean. */
    private fun allVisible() =
        WindowAppearanceSettings(
            showTopBar = true,
            showBottomBar = true,
            showLeftStrip = true,
            showRightStrip = true,
        )
}
