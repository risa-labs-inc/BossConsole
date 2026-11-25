package ai.rever.boss.service.auth

import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The out-of-process twin of composeApp's AuthRefreshRetryTest. KEEP IN LOCKSTEP with it and with
 * the plugin copy in this module: drives [SupabaseAuthClient]'s real supabase-kt scheduled refresh
 * against a MockEngine, so it also checks the plugin is wired into this client.
 */
class AuthRefreshRetryTest {
    @Test
    fun `429 then 200 keeps the session and imports the refreshed one`(): Unit =
        runBlocking {
            val (client, sessions, requests) = serviceAnswering(listOf(429, 200))

            withTimeout(10.seconds) {
                client.client.auth.sessionStatus.first {
                    it is SessionStatus.Authenticated && it.session.accessToken == NEW_ACCESS_TOKEN
                }
            }
            assertEquals(2, requests.get())
            assertEquals(NEW_REFRESH_TOKEN, sessions.loadSession().refreshToken)
        }

    @Test
    fun `repeated 429 and 525 keep the session and the cooldown stops further requests`(): Unit =
        runBlocking {
            for (code in listOf(429, 525)) {
                val (client, sessions, requests) = serviceAnswering(List(10) { code })

                awaitRequests(requests, 2)
                delay(300.milliseconds)

                assertEquals(2, requests.get(), "HTTP $code: two attempts, then the cooldown")
                val status = client.client.auth.sessionStatus.value
                assertFalse(status is SessionStatus.NotAuthenticated, "HTTP $code cleared the session")
                assertEquals(OLD_REFRESH_TOKEN, sessions.loadSession().refreshToken)
            }
        }

    @Test
    fun `400 invalid_grant still clears the session`(): Unit =
        runBlocking {
            val (client, sessions, requests) = serviceAnswering(listOf(400))

            withTimeout(10.seconds) {
                client.client.auth.sessionStatus
                    .first { it is SessionStatus.NotAuthenticated }
            }
            assertEquals(1, requests.get())
            assertNull(runCatching { sessions.loadSession() }.getOrNull())
        }

    @Test
    fun `the cooldown doubles per consecutive failure, caps at 5 minutes and resets`() {
        val now = AtomicLong(0L)
        val cooldown =
            RefreshCooldown(
                AuthRefreshRetryConfig().apply {
                    jitter = { it }
                    nowMillis = { now.get() }
                },
            )

        val steps = (1..7).map { cooldown.onTransientFailure(retryAfter = null) }
        assertEquals(listOf(15, 30, 60, 120, 240, 300, 300).map { it.seconds }, steps)
        assertEquals(5.minutes.inWholeMilliseconds, cooldown.remainingMs())

        cooldown.onAnswer()
        assertEquals(0L, cooldown.remainingMs())
        assertEquals(15.seconds, cooldown.onTransientFailure(retryAfter = null))
        assertEquals(120.seconds, cooldown.onTransientFailure(retryAfter = 120.seconds))
    }

    @Test
    fun `transient statuses are exactly 408, 429 and 5xx`() {
        listOf(408, 429, 500, 501, 503, 520, 525, 527, 530).forEach { assertTrue(isTransientRefreshStatus(it), "$it") }
        listOf(200, 400, 401, 403, 404, 422).forEach { assertFalse(isTransientRefreshStatus(it), "$it") }
    }

    private data class Harness(
        val client: SupabaseAuthClient,
        val sessions: MemorySessionManager,
        val requests: AtomicInteger,
    )

    private fun serviceAnswering(statuses: List<Int>): Harness {
        val requests = AtomicInteger()
        val sessions = MemorySessionManager(expiredSession())
        val engine =
            MockEngine { request ->
                val n = requests.getAndIncrement()
                assertEquals("refresh_token", request.url.parameters["grant_type"])
                assertTrue((request.body as TextContent).text.contains(OLD_REFRESH_TOKEN))
                val code = statuses.getOrNull(n) ?: error("unexpected request #${n + 1}")
                val body =
                    when (code) {
                        200 -> {
                            """{"access_token":"$NEW_ACCESS_TOKEN","refresh_token":"$NEW_REFRESH_TOKEN",""" +
                                """"expires_in":3600,"token_type":"bearer"}"""
                        }

                        400 -> {
                            """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""
                        }

                        else -> {
                            """{"code":$code,"msg":"transient"}"""
                        }
                    }
                respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val client = SupabaseAuthClient(URL, "test-anon-key", httpEngine = engine, sessionManager = sessions)
        return Harness(client, sessions, requests)
    }

    private suspend fun awaitRequests(
        requests: AtomicInteger,
        atLeast: Int,
    ) = withTimeout(10.seconds) {
        while (requests.get() < atLeast) delay(10.milliseconds)
    }

    private companion object {
        const val URL = "https://project.supabase.co"
        const val OLD_REFRESH_TOKEN = "old-refresh-token"
        const val NEW_REFRESH_TOKEN = "new-refresh-token"
        const val NEW_ACCESS_TOKEN = "new-access-token"

        fun expiredSession() =
            UserSession(
                accessToken = "old-access-token",
                refreshToken = OLD_REFRESH_TOKEN,
                expiresIn = 3600,
                tokenType = "bearer",
                expiresAt = Clock.System.now() - 1.minutes,
            )
    }
}
