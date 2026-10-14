package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalWelcomeWizardEntryTest {
    @Test
    fun `available terminal provider opens welcome wizard`() {
        var opened = false
        var unavailable = false

        val accepted =
            requestTerminalWelcomeWizard(
                providerAvailable = true,
                onOpen = { opened = true },
                onUnavailable = { unavailable = true },
            )

        assertTrue(accepted)
        assertTrue(opened)
        assertFalse(unavailable)
    }

    @Test
    fun `missing terminal provider reports unavailable instead of silently opening`() {
        var opened = false
        var unavailable = false

        val accepted =
            requestTerminalWelcomeWizard(
                providerAvailable = false,
                onOpen = { opened = true },
                onUnavailable = { unavailable = true },
            )

        assertFalse(accepted)
        assertFalse(opened)
        assertTrue(unavailable)
    }
}
