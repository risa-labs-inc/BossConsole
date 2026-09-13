package ai.rever.boss.components

import ai.rever.boss.components.dialogs.engineDownloadStatus
import ai.rever.boss.components.settings.sections.message
import ai.rever.boss.components.settings.sections.offersRestart
import ai.rever.boss.components.settings.sections.stagedInstallOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two engine-download messages users actually act on.
 *
 * Both gaps these cover were found by running the app, not by reading it — the
 * download dialog never said which engine it was fetching, and Settings offered a
 * note where it needed a restart. Neither path had any coverage, so nothing would
 * have caught the wording silently reverting.
 */
class EngineDownloadUxTest {
    /** An exception whose message really is null — the case the fallback exists for. */
    private class NoMessageException : RuntimeException(null as String?)

    private val label = "BOSS Browser Engine 9.4.0"

    @Test
    fun `every download phase names the engine version`() {
        // The whole point: an unnamed dialog blocking the app for several hundred MB
        // is what prompted this. Each phase must carry the label.
        val phases =
            listOf(
                engineDownloadStatus(label, isExtracting = false, totalBytes = 0),
                engineDownloadStatus(label, isExtracting = false, totalBytes = 1),
                engineDownloadStatus(label, isExtracting = true, totalBytes = 1),
            )

        phases.forEach { status ->
            assertTrue(status.contains("9.4.0"), "phase must name the version: $status")
        }
        assertEquals(phases.size, phases.toSet().size, "each phase must be distinguishable")
    }

    @Test
    fun `extracting takes precedence over byte progress`() {
        // isExtracting arrives with totalBytes still set from the download, so the
        // order of the branches is load-bearing — reversed, the dialog would claim
        // it is still downloading while it unpacks.
        assertTrue(
            engineDownloadStatus(label, isExtracting = true, totalBytes = 999).startsWith("Extracting"),
        )
    }

    @Test
    fun `a staged install offers the restart that completes it`() {
        val outcome = stagedInstallOutcome("9.4.0", "9.4.0", Result.success(Unit))

        assertTrue(outcome.offersRestart(), "a staged engine is inert until BOSS restarts")
        assertTrue(outcome.message("9.4.0").contains("9.4.0"))
        assertTrue(
            outcome.message("9.4.0").contains("not in use", ignoreCase = true),
            "the message must state the present fact, not just instruct",
        )
    }

    @Test
    fun `a non-default version is staged but must not offer a restart`() {
        // A developer target cannot change the native toolkit bundled with this build.
        val outcome = stagedInstallOutcome("9.3.0", "9.4.0", Result.success(Unit))

        assertFalse(outcome.offersRestart(), "restarting would not apply this engine")
        val message = outcome.message("9.4.0")
        assertTrue(message.contains("9.3.0") && message.contains("9.4.0"), "name both versions: $message")
        assertTrue(message.contains("cannot be used"), "explain the compatibility failure: $message")
    }

    @Test
    fun `a failed install does not offer a restart`() {
        // Nothing was staged, so restarting changes nothing — offering it would read
        // as "we fixed it" when we did not.
        val failure = IllegalStateException("disk full")
        val outcome = stagedInstallOutcome("9.4.0", "9.4.0", Result.failure<Unit>(failure))

        assertFalse(outcome.offersRestart())
        assertTrue(outcome.message("9.4.0").contains("disk full"), "the reason must survive")
    }

    @Test
    fun `a failure with no message still says something`() {
        val outcome = stagedInstallOutcome("9.4.0", "9.4.0", Result.failure<Unit>(NoMessageException()))

        assertFalse(outcome.offersRestart())
        assertTrue(outcome.message("9.4.0").contains("unknown error"))
    }
}
