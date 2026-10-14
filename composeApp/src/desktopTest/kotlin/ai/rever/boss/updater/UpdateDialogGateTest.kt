package ai.rever.boss.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [shouldShowUpdateDialog], the gate that keeps the autonomous app-update prompt from stacking
 * a second always-on-top window over another heavyweight modal (BossConsole#696).
 *
 * The convergence argument the composable relies on is checked here as a sequence: the prompt opens
 * only when nothing else is up, keeps itself up once it is the only modal, steps aside when another
 * modal opens over it, and comes back when that modal closes - with no oscillation, because it
 * subtracts its own window out of the count.
 */
class UpdateDialogGateTest {
    @Test
    fun `the base conditions still gate it`() {
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = false,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 0,
                updateDialogShowing = false,
            ),
            "no request means no dialog",
        )
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = false,
                updateAvailable = true,
                openHeavyweightModals = 0,
                updateDialogShowing = false,
            ),
            "a non-owning window never hosts it",
        )
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = false,
                openHeavyweightModals = 0,
                updateDialogShowing = false,
            ),
            "no available update means no dialog",
        )
    }

    @Test
    fun `it opens when nothing else is on screen`() {
        assertTrue(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 0,
                updateDialogShowing = false,
            ),
        )
    }

    @Test
    fun `it does not open over a modal already up`() {
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 1,
                updateDialogShowing = false,
            ),
        )
    }

    @Test
    fun `once it is the only modal it stays - its own window is not counted against it`() {
        assertTrue(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 1,
                updateDialogShowing = true,
            ),
        )
    }

    @Test
    fun `it steps aside when a second modal opens over it`() {
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 2,
                updateDialogShowing = true,
            ),
        )
    }

    @Test
    fun `it stays hidden while the other modal is up, then returns when it closes`() {
        // The other modal is still up and the prompt has already stepped aside (showing = false).
        assertFalse(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 1,
                updateDialogShowing = false,
            ),
        )
        // The other modal closes.
        assertTrue(
            shouldShowUpdateDialog(
                wantDialog = true,
                isOwner = true,
                updateAvailable = true,
                openHeavyweightModals = 0,
                updateDialogShowing = false,
            ),
        )
    }
}
