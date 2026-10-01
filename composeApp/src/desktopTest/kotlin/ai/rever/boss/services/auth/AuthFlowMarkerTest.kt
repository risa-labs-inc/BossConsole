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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthFlowMarkerTest {
    private val root get() = BossDirectories.rootDir

    @BeforeEach
    @AfterEach
    fun cleanUp() {
        AuthFlowMarker.fileFor(root).delete()
        // Drop any claim a test left behind: a fresh mark resets it.
        AuthFlowMarker.mark(Kind.OAUTH)
        AuthFlowMarker.fileFor(root).delete()
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
    fun `each kind waits as long as its own link is valid, and kinds do not mix`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com", now = 1_000L)
        assertNotNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK, now = 1_000L + 59 * 60 * 1000L))
        assertNull(AuthFlowMarker.pending(root, Kind.MAGIC_LINK, now = 1_000L + Kind.MAGIC_LINK.maxAgeMs + 1))
        assertNull(AuthFlowMarker.pending(root, Kind.OAUTH, now = 2_000L))
    }

    @Test
    fun `a callback of one kind never consumes a flow of another`() {
        AuthFlowMarker.mark(Kind.OAUTH)
        assertNull(AuthFlowMarker.claim(Kind.MAGIC_LINK, token = "t"))
        assertNull(AuthFlowMarker.takeForExchange("t"))
        assertNotNull(AuthFlowMarker.pending(root, Kind.OAUTH), "the other kind's flow is untouched")
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
}
