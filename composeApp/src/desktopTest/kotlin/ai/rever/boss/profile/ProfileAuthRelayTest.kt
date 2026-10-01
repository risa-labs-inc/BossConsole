package ai.rever.boss.profile

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs in the main profile of composeApp's hermetic test home. */
class ProfileAuthRelayTest {
    private val now = 10_000_000L

    @BeforeEach
    @AfterEach
    fun cleanUp() {
        BossDirectories.profilesDir().deleteRecursively()
        AuthFlowMarker.fileFor(BossDirectories.rootDir).delete()
    }

    private fun markProfile(
        id: String,
        at: Long,
    ) = mark(BossDirectories.profileRoot(id), at)

    private fun mark(
        root: File,
        at: Long,
    ) {
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText(at.toString())
    }

    @Test
    fun `only the callbacks a marker stands for are relayable`() {
        assertTrue(ProfileAuthRelay.isRelayable("boss://auth/verify?token=x"))
        assertTrue(ProfileAuthRelay.isRelayable("boss://auth/callback?code=x"))
        assertTrue(ProfileAuthRelay.isRelayable("boss://auth/verify/?token=x"))
        // Passkey ceremonies and other auth links never leave the main process.
        assertFalse(ProfileAuthRelay.isRelayable("boss://passkey/authenticated?session=x"))
        assertFalse(ProfileAuthRelay.isRelayable("boss://auth/recovery?token=x"))
        assertFalse(ProfileAuthRelay.isRelayable("boss://auth"))
        assertFalse(ProfileAuthRelay.isRelayable("boss://workspace?path=/x"))
        assertFalse(ProfileAuthRelay.isRelayable("https://auth/verify"))
        assertFalse(ProfileAuthRelay.isRelayable("not a uri"))
    }

    @Test
    fun `nothing is waiting when no profile has a pending sign-in`() {
        File(BossDirectories.profilesDir(), "idle").mkdirs()
        assertEquals(emptyList(), ProfileAuthRelay.waitingProfileIds(now))
        assertFalse(ProfileAuthRelay.mightRelay("boss://auth/verify?token=x"))
    }

    @Test
    fun `the most recent pending sign-in comes first`() {
        markProfile("older", now - 60_000)
        markProfile("newer", now - 1_000)
        assertEquals(listOf("newer", "older"), ProfileAuthRelay.waitingProfileIds(now))
    }

    @Test
    fun `the main process keeps a callback when its own sign-in is the most recent`() {
        markProfile("older", now - 60_000)
        mark(BossDirectories.rootDir, now - 1_000)
        assertEquals(emptyList(), ProfileAuthRelay.waitingProfileIds(now))
    }

    @Test
    fun `a sign-in past the time limit no longer claims callbacks`() {
        markProfile("stale", now - AuthFlowMarker.MAX_AGE_MS - 1)
        assertEquals(emptyList(), ProfileAuthRelay.waitingProfileIds(now))
    }

    @Test
    fun `a directory that is not a valid profile id is ignored`() {
        mark(File(BossDirectories.profilesDir(), "Not Valid"), now - 1_000)
        assertEquals(emptyList(), ProfileAuthRelay.waitingProfileIds(now))
    }

    @Test
    fun `a relay with no running profile keeps the link here`() {
        markProfile("not-running", System.currentTimeMillis())
        assertFalse(ProfileAuthRelay.relayIfAwaitedElsewhere("boss://auth/verify?token=x"))
    }
}
