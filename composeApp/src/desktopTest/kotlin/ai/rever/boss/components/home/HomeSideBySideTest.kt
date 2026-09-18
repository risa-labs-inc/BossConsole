package ai.rever.boss.components.home

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSideBySideTest {
    @Test
    fun `two browsers can open without terminal`() {
        assertNull(
            sideBySideUnavailableReason(browserAvailable = true, terminalAvailable = false, withTerminal = false),
        )
    }

    @Test
    fun `both layouts explain an unavailable browser`() {
        listOf(false, true).forEach { withTerminal ->
            val reason =
                sideBySideUnavailableReason(
                    browserAvailable = false,
                    terminalAvailable = true,
                    withTerminal = withTerminal,
                )
            assertTrue(assertNotNull(reason).contains("Browser"))
        }
    }

    @Test
    fun `browser and terminal needs both providers before replacing the layout`() {
        val terminalMissing =
            sideBySideUnavailableReason(browserAvailable = true, terminalAvailable = false, withTerminal = true)
        assertTrue(assertNotNull(terminalMissing).contains("Terminal"))
        val bothMissing =
            sideBySideUnavailableReason(browserAvailable = false, terminalAvailable = false, withTerminal = true)
        assertTrue(assertNotNull(bothMissing).contains("Browser and Terminal"))
        assertNull(sideBySideUnavailableReason(browserAvailable = true, terminalAvailable = true, withTerminal = true))
    }
}
