package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker.Kind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
    private val root get() = BossDirectories.rootDir

    @BeforeEach
    @AfterEach
    fun cleanUp() {
        AuthFlowMarker.fileFor(root).delete()
        AuthFlowMarker.resetForTest()
    }

    @Test
    fun `a marked flow is pending with the account it was sent to and a fresh generation`() {
        val first = AuthFlowMarker.mark(Kind.MAGIC_LINK, "Someone@Example.com ")
        val pending = assertNotNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK))
        assertEquals(AuthFlowMarker.hashEmail("someone@example.com"), pending.emailHash, "emails are normalised")
        val second = AuthFlowMarker.mark(Kind.MAGIC_LINK, "someone@example.com")
        assert(first.id != second.id) { "every mark is a new generation" }
    }

    @Test
    fun `a flow waits as long as its link is valid`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        assertNotNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK, now = 1_000L + 59 * 60 * 1000L))
        assertNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK, now = 1_000L + Kind.MAGIC_LINK.maxAgeMs + 1))
    }

    @Test
    fun `a flow is taken exactly once, even by concurrent exchanges`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        val takes = AtomicInteger()
        val start = CountDownLatch(1)
        val threads =
            (1..8).map {
                thread {
                    start.await()
                    if (AuthFlowMarker.takeForExchange("t") != null) takes.incrementAndGet()
                }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, takes.get())
    }

    @Test
    fun `a claim is bound to its token and redeemed once`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        val claimed = assertNotNull(AuthFlowMarker.claim(Kind.MAGIC_LINK, token = "token-1"))
        assertNull(AuthFlowMarker.takeForExchange("token-2"), "another token cannot redeem the claim")
        assertEquals(claimed, AuthFlowMarker.takeForExchange("token-1"))
        assertNull(AuthFlowMarker.takeForExchange("token-1"), "a claim is spent once")
    }

    @Test
    fun `an expired flow cannot be claimed or taken`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        val late = 1_000L + Kind.MAGIC_LINK.maxAgeMs + 1
        assertNull(AuthFlowMarker.claim(Kind.MAGIC_LINK, "t", now = late))
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        assertNull(AuthFlowMarker.takeForExchange("t", now = late))
    }

    @Test
    fun `a claim that expires before its exchange cannot be redeemed`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        assertNotNull(AuthFlowMarker.claim(Kind.MAGIC_LINK, "t", now = 2_000L))
        assertNull(AuthFlowMarker.takeForExchange("t", now = 1_000L + Kind.MAGIC_LINK.maxAgeMs + 1))
    }

    @Test
    fun `restoring an old flow never replaces a newer one`() {
        val old = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        assertNotNull(AuthFlowMarker.takeForExchange("t"))
        val newer = AuthFlowMarker.mark(Kind.MAGIC_LINK, "b@example.com")
        AuthFlowMarker.restore(old)
        assertEquals(newer.id, AuthFlowMarker.pending(root, Kind.MAGIC_LINK)?.id)
    }

    @Test
    fun `a flow restored after a failed exchange waits again`() {
        val flow = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        assertNotNull(AuthFlowMarker.takeForExchange("t"))
        assertNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK))
        AuthFlowMarker.restore(flow)
        assertEquals(flow.id, AuthFlowMarker.pending(root, Kind.MAGIC_LINK)?.id)
    }

    @Test
    fun `a superseded flow is never restored, even once the newer one was consumed too`() {
        val old = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        assertNotNull(AuthFlowMarker.takeForExchange("t1"))
        val newer = AuthFlowMarker.mark(Kind.MAGIC_LINK, "b@example.com")
        assertNotNull(AuthFlowMarker.takeForExchange("t2"))
        AuthFlowMarker.restore(old)
        assertNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK), "the old generation must not come back")
        AuthFlowMarker.restore(newer)
        assertEquals(newer.id, AuthFlowMarker.pending(root, Kind.MAGIC_LINK)?.id)
    }

    @Test
    fun `restores racing new marks never overwrite a newer flow`() {
        repeat(200) {
            AuthFlowMarker.fileFor(root).delete()
            val old = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
            assertNotNull(AuthFlowMarker.takeForExchange("t"))
            val start = CountDownLatch(1)
            var newer: AuthFlowMarker.Flow? = null
            val marker =
                thread {
                    start.await()
                    newer = AuthFlowMarker.mark(Kind.MAGIC_LINK, "b@example.com")
                }
            val restorer =
                thread {
                    start.await()
                    AuthFlowMarker.restore(old)
                }
            start.countDown()
            marker.join()
            restorer.join()
            assertEquals(newer?.id, AuthFlowMarker.pending(root, Kind.MAGIC_LINK)?.id)
        }
    }

    @Test
    fun `a new mark keeps an accepted claim bound to its own token and flow`() {
        val first = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        assertNotNull(AuthFlowMarker.claim(Kind.MAGIC_LINK, token = "token-a"))
        val second = AuthFlowMarker.mark(Kind.MAGIC_LINK, "b@example.com")
        // The old link redeems its own claim, never the new flow, and the new flow keeps waiting.
        assertEquals(first.id, AuthFlowMarker.takeForExchange("token-a")?.id)
        assertEquals(second.id, AuthFlowMarker.pending(root, Kind.MAGIC_LINK)?.id)
        // ...and it is superseded, so it is not exchanged.
        assertFalse(AuthFlowMarker.isCurrent(first))
        assertTrue(AuthFlowMarker.isCurrent(second))
    }

    @Test
    fun `the process remembers a live link it asked for after the flow is consumed`() {
        assertFalse(AuthFlowMarker.hasIssuedLive())
        val flow = AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        assertNotNull(AuthFlowMarker.takeForExchange("t", now = 2_000L))
        assertTrue(AuthFlowMarker.hasIssuedLive(now = 2_000L))
        assertFalse(AuthFlowMarker.hasIssuedLive(now = flow.startedAtMs + Kind.MAGIC_LINK.maxAgeMs + 1))
    }
}
