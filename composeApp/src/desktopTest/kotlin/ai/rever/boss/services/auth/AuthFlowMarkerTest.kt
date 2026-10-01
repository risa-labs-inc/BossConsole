package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthFlowMarkerTest {
    @TempDir
    lateinit var root: File

    private val kind = AuthFlowMarker.Kind.MAGIC_LINK

    @AfterEach
    fun cleanUp() {
        AuthFlowMarker.clear()
    }

    @Test
    fun `a marked flow is pending with the account it was sent to, and clear ends it`() {
        AuthFlowMarker.mark(kind, "Someone@Example.com ")
        val flow = assertNotNull(AuthFlowMarker.pending(BossDirectories.rootDir, kind))
        assertEquals(AuthFlowMarker.hashEmail("someone@example.com"), flow.emailHash, "emails are normalised")
        assertTrue(AuthFlowMarker.awaitsMagicLink())
        AuthFlowMarker.clear()
        assertNull(AuthFlowMarker.pending(BossDirectories.rootDir, kind))
        assertFalse(AuthFlowMarker.awaitsMagicLink())
    }

    @Test
    fun `each kind waits as long as its own link is valid`() {
        AuthFlowMarker.mark(kind, "a@example.com", now = 1_000L)
        assertNotNull(AuthFlowMarker.pending(BossDirectories.rootDir, kind, now = 1_000L + 59 * 60 * 1000L))
        assertNull(AuthFlowMarker.pending(BossDirectories.rootDir, kind, now = 1_000L + kind.maxAgeMs + 1))
        val oauth = AuthFlowMarker.Kind.OAUTH
        assertNull(AuthFlowMarker.pending(BossDirectories.rootDir, oauth, now = 2_000L), "kinds do not mix")
    }

    @Test
    fun `garbled or missing markers are not pending`() {
        assertNull(AuthFlowMarker.pending(root, kind))
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText("12345")
        assertNull(AuthFlowMarker.pending(root, kind))
    }

    @Test
    fun `a flow is claimed exactly once, even by concurrent claims`() {
        AuthFlowMarker.mark(kind, "a@example.com")
        val claims = AtomicInteger()
        val start = CountDownLatch(1)
        val threads =
            (1..8).map {
                thread {
                    start.await()
                    if (AuthFlowMarker.claim(kind) != null) claims.incrementAndGet()
                }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, claims.get())
        assertNull(AuthFlowMarker.pending(BossDirectories.rootDir, kind), "a claimed flow no longer waits")
    }

    @Test
    fun `the claimed flow decides which account a link must sign in`() {
        AuthFlowMarker.mark(kind, "a@example.com")
        AuthFlowMarker.claim(kind)
        assertEquals(AuthFlowMarker.hashEmail("a@example.com"), AuthFlowMarker.expectedEmailHash())
        assertTrue(AuthFlowMarker.awaitsMagicLink(), "a claimed flow is still the one the link is checked against")
    }

    @Test
    fun `an expired flow cannot be claimed`() {
        AuthFlowMarker.mark(kind, "a@example.com", now = 1_000L)
        assertNull(AuthFlowMarker.claim(kind, now = 1_000L + kind.maxAgeMs + 1))
    }

    @Test
    fun `a profile refuses a link it did not ask for before exchanging it`() {
        assertTrue(AuthFlowMarker.refuseBeforeExchange(isProfile = true, awaitsLink = false))
        assertFalse(AuthFlowMarker.refuseBeforeExchange(isProfile = true, awaitsLink = true))
        // The main profile keeps the behaviour it had before profiles existed.
        assertFalse(AuthFlowMarker.refuseBeforeExchange(isProfile = false, awaitsLink = false))
    }

    @Test
    fun `an exchange that signed in another account is refused`() {
        val a = AuthFlowMarker.hashEmail("a@example.com")
        val b = AuthFlowMarker.hashEmail("b@example.com")
        assertTrue(AuthFlowMarker.isWrongAccount(a, b))
        assertTrue(AuthFlowMarker.isWrongAccount(a, null), "an account that cannot be read is not the expected one")
        assertFalse(AuthFlowMarker.isWrongAccount(a, a))
        assertFalse(AuthFlowMarker.isWrongAccount(null, b), "nothing to check when no link was asked for")
    }
}
