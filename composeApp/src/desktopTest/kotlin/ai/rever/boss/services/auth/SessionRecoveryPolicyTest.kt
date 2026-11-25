package ai.rever.boss.services.auth

import io.github.jan.supabase.auth.exception.TokenExpiredException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [SessionRecoveryPolicy] — the retry-vs-clear decision behind
 * CoreAuthService's session recovery loop.
 */
class SessionRecoveryPolicyTest {
    @Test
    fun `4xx rejections clear the session`() {
        assertEquals(SessionRecoveryPolicy.Action.ClearSession, SessionRecoveryPolicy.actionForStatus(400))
        assertEquals(SessionRecoveryPolicy.Action.ClearSession, SessionRecoveryPolicy.actionForStatus(401))
        assertEquals(SessionRecoveryPolicy.Action.ClearSession, SessionRecoveryPolicy.actionForStatus(403))
    }

    @Test
    fun `server errors and cloudflare codes retry`() {
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(500))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(502))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(520))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(530))
        // supabase-kt 3.8.0's own list stops at 524 and skips 501/505+; ours covers every 5xx.
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(501))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(525))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(526))
    }

    @Test
    fun `transient statuses are exactly 408, 429 and 5xx`() {
        for (code in listOf(408, 429, 500, 501, 503, 520, 525, 527, 530, 599)) {
            assertTrue(SessionRecoveryPolicy.isTransientStatus(code), "$code")
        }
        for (code in listOf(200, 302, 400, 401, 403, 404, 409, 422)) {
            assertFalse(SessionRecoveryPolicy.isTransientStatus(code), "$code")
        }
    }

    @Test
    fun `transient 4xx codes retry instead of clearing`() {
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(408))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionForStatus(429))
    }

    @Test
    fun `non-rest exceptions retry`() {
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionFor(IOException("Connect timeout has expired")))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionFor(IllegalStateException("boom")))
        assertEquals(SessionRecoveryPolicy.Action.Retry, SessionRecoveryPolicy.actionFor(TokenExpiredException()))
    }

    @Test
    fun `backoff doubles and caps at max`() {
        assertEquals(20.seconds, SessionRecoveryPolicy.nextBackoff(10.seconds))
        assertEquals(40.seconds, SessionRecoveryPolicy.nextBackoff(20.seconds))
        assertEquals(60.seconds, SessionRecoveryPolicy.nextBackoff(40.seconds))
        assertEquals(60.seconds, SessionRecoveryPolicy.nextBackoff(60.seconds))
    }

    @Test
    fun `a session replaced during recovery is not cleared`() {
        val now = Clock.System.now()
        // The import landed while recovery's refresh of the old token was in flight.
        assertTrue(SessionRecoveryPolicy.replacedSince("old", "new", now + 1.hours, now))
        assertTrue(SessionRecoveryPolicy.replacedSince("old", "new", now - 1.hours, now))
        assertTrue(SessionRecoveryPolicy.replacedSince("old", "old", now + 1.hours, now))
        // Still the expired session recovery diagnosed, or nothing left: clear as before.
        assertFalse(SessionRecoveryPolicy.replacedSince("old", "old", now - 1.hours, now))
        assertFalse(SessionRecoveryPolicy.replacedSince("old", null, null, now))
    }
}
