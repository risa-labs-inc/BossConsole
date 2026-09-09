package ai.rever.boss.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the rule that decides whether a panel column carries the host's actions at all.
 *
 * Every case here is a measurement taken from the layout it describes, not an estimate: the
 * numbers came out of mounting `PanelColumn` at each size and reading the rendered bounds back.
 * They are in the assertion messages so a future change to the row's geometry fails with the
 * number it broke rather than with a bare false.
 *
 * The rule exists because drawing the row unconditionally had two failure modes on two axes, and
 * both of them took the panel rather than the icons: at 20x400 the plugin kept 211dp behind 188dp
 * of chrome whose icons were 4dp WIDE, and at 20x200 it kept 11dp.
 */
class PanelFooterFitTest {
    private fun fits(
        width: Int,
        height: Int,
        count: Int = EVERY_ACTION,
    ) = panelFooterFitsColumn(
        columnWidth = width.dp,
        columnHeight = height.dp,
        actionCount = count,
        sideInset = SM,
        gap = XS,
    )

    @Test
    fun `the default panel hosts them on one line`() {
        // 250x400 measured: a 44dp row under 355dp of plugin. This is the case the placement is
        // aimed at, and it must never be the one the rule rejects.
        assertTrue(fits(250, 400), "the 250dp default is where this placement lives")
    }

    @Test
    fun `a narrowed panel still hosts them on two lines`() {
        // 150x600 measured: an 80dp row under 519dp of plugin. Wrapping is the point of using a
        // FlowRow, so the rule has to allow the wrap it was chosen for.
        assertTrue(fits(150, 600), "two lines is chrome, not a tower")
        assertTrue(fits(120, 600), "120dp is three per line, the floor of the two-line band")
    }

    @Test
    fun `a sliver does not, because the icons would be slivers too`() {
        // 20x400 measured: icons 4dp wide. `Modifier.size` coerces to the incoming constraint on
        // the width axis as well, so a column narrower than one icon plus its inset renders
        // shrunken buttons rather than clipped ones - invisible in a different way.
        assertFalse(fits(20, 400), "a 20dp column renders 4dp icons")
        assertFalse(fits(47, 400), "one 32dp icon plus 8dp either side is 48dp, so 47 is out")
        assertTrue(fits(48, 400, count = 1), "and 48dp is in, for a row that only needs one line")
    }

    @Test
    fun `a column that would need three lines does not`() {
        // 60x300 measured: a 188dp row under 111dp of plugin, with every icon at its full 32dp.
        // Nothing is squeezed here - the row simply took two thirds of the column, which is the
        // failure the bottom column was ruled out for, reached on the other axis.
        assertFalse(fits(60, 300), "five buttons one per line is 188dp of chrome")
        assertFalse(fits(100, 600), "100dp is two per line, so five buttons need three lines")
    }

    @Test
    fun `a short column does not, however wide it is`() {
        // The height half of the rule. A 45dp row in a 100dp column is nearly half of it.
        assertFalse(fits(250, 100), "one line is 45dp, which is not a third of 100dp")
        assertTrue(fits(250, 135), "45dp is exactly a third of 135dp")
        assertFalse(fits(150, 200), "two lines is 81dp, over a third of 200dp")
    }

    @Test
    fun `the launcher can be what tips a column over`() {
        // The count is not a constant: with both icon strips off the tools launcher joins this
        // group, which is why `PanelFooterHostActions` is told the count rather than the list.
        assertTrue(fits(120, 600, count = FOCUS_QUICK_ACTION_COUNT), "four buttons fit two lines at 120dp")
        assertTrue(fits(120, 600, count = EVERY_ACTION), "and so do five, three per line")
        assertEquals(
            false,
            fits(88, 600, count = EVERY_ACTION),
            "at 88dp two per line, five buttons need three lines where four need two",
        )
        assertTrue(fits(88, 600, count = FOCUS_QUICK_ACTION_COUNT), "the same column, one button fewer")
    }
}

/** Every button the row can hold: the four, plus the tools launcher with both strips off. */
private const val EVERY_ACTION = FOCUS_QUICK_ACTION_COUNT + 1

/** `BossSpacing`'s defaults, which are the only values ever provided - see BossTheme.kt. */
private val SM = 8.dp
private val XS = 4.dp
