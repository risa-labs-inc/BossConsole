package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewReleaseStateTest {
    @Test
    fun `release is unseen only when newer than saved marker`() {
        assertTrue(isUnseenRelease(version("9.5.9"), "9.5.8"))
        assertFalse(isUnseenRelease(version("9.5.8"), "9.5.8"))
        assertFalse(isUnseenRelease(version("9.5.7"), "9.5.8"))
    }

    @Test
    fun `missing or invalid marker treats release as unseen`() {
        assertTrue(isUnseenRelease(version("9.5.8"), null))
        assertTrue(isUnseenRelease(version("9.5.8"), "invalid-version"))
    }

    @Test
    fun `seen marker advances but never moves backwards`() {
        assertEquals(
            "9.5.9",
            advanceLastSeenReleaseVersion("9.5.8", version("9.5.9")),
        )
        assertEquals(
            "9.5.8",
            advanceLastSeenReleaseVersion("9.5.8", version("9.5.7")),
        )
    }

    private fun version(value: String): Version = requireNotNull(Version.parse(value))
}
