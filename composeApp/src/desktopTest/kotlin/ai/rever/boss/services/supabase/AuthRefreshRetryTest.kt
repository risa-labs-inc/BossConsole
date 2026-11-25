package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogListener
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Drives supabase-kt 3.8.0's real scheduled refresh (AuthImpl.tryImportingSession) against a
 * MockEngine, with [AuthRefreshRetry] installed the way [SupabaseConfig] installs it.
 *
 * Incident 2026-11-25: under load api.risaboss.com answered the refresh with 429 and Cloudflare 525,
 * supabase-kt treated both as "refresh token revoked", cleared the session and every plugin unloaded.
 */
class AuthRefreshRetryTest {
    private val logs = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val listener = LogListener { logs += it }
    private val clients = mutableListOf<SupabaseClient>()

    @BeforeTest
    fun listen() {
        BossLogger.addListener(listener)
    }

    @AfterTest
    fun tearDown() {
        BossLogger.removeListener(listener)
        runBlocking { clients.forEach { it.close() } }
        // The refresh token is in every request body; it must not reach any log line.
        assertFalse(
            logs.any { entry -> entry.toString().contains(OLD_REFRESH_TOKEN) },
            "the refresh token must never be logged",
        )
    }

    @Test
    fun `429 then 200 keeps the session and imports the refreshed one`(): Unit =
        runBlocking {
            val (client, sessions, requests) = clientAnswering(listOf(status(429), success()))

            val status =
                client.awaitStatus { it is SessionStatus.Authenticated && it.session.accessToken == NEW_ACCESS_TOKEN }

            assertIs<SessionStatus.Authenticated>(status)
            assertEquals(2, requests.get(), "one 429, then the retried refresh")
            assertEquals(NEW_REFRESH_TOKEN, sessions.loadSession().refreshToken)
        }

    @Test
    fun `repeated 429 keeps the session`(): Unit = assertTransientKeepsSession(429)

    @Test
    fun `repeated 525 keeps the session`(): Unit = assertTransientKeepsSession(525)

    @Test
    fun `repeated 503 keeps the session`(): Unit = assertTransientKeepsSession(503)

    @Test
    fun `repeated 408 keeps the session`(): Unit = assertTransientKeepsSession(408)

    @Test
    fun `a timeout keeps the session`(): Unit =
        runBlocking {
            val (client, sessions, requests) =
                clientAnswering(emptyList(), fallback = { throw java.io.IOException("Connect timeout has expired") })

            awaitRequests(requests, MAX_ATTEMPTS)
            // supabase-kt keeps retrying every RETRY_DELAY; the cooldown answers those locally.
            delay(RETRY_DELAY * 5)

            assertEquals(MAX_ATTEMPTS, requests.get(), "the cooldown must keep later ticks off the server")
            assertNotNotAuthenticated(client)
            assertEquals(OLD_REFRESH_TOKEN, sessions.loadSession().refreshToken)
        }

    @Test
    fun `after the cooldown a 400 invalid_grant still clears the session`(): Unit =
        runBlocking {
            val clock = AtomicLong(Clock.System.now().toEpochMilliseconds())
            val (client, sessions, requests) =
                clientAnswering(listOf(status(429), status(429), invalidGrant()), clock = clock)

            awaitRequests(requests, MAX_ATTEMPTS)
            delay(RETRY_DELAY * 3)
            assertEquals(MAX_ATTEMPTS, requests.get(), "still cooling down")
            assertNotNotAuthenticated(client)

            clock.addAndGet(16.seconds.inWholeMilliseconds) // past the first 15s cooldown
            client.awaitStatus { it is SessionStatus.NotAuthenticated }

            assertEquals(MAX_ATTEMPTS + 1, requests.get())
            assertNull(runCatching { sessions.loadSession() }.getOrNull(), "session store must be cleared")
        }

    @Test
    fun `400 invalid_grant still clears the session`(): Unit =
        runBlocking {
            val (client, sessions, requests) =
                clientAnswering(listOf(invalidGrant()))

            client.awaitStatus { it is SessionStatus.NotAuthenticated }

            assertEquals(1, requests.get(), "a real rejection is not retried")
            assertNull(runCatching { sessions.loadSession() }.getOrNull(), "session store must be cleared")
        }

    @Test
    fun `a long Retry-After defers later refreshes without hitting the server`(): Unit =
        runBlocking {
            val (client, sessions, requests) =
                clientAnswering(emptyList(), fallback = status(429, retryAfter = "120"))

            // supabase-kt retries every RETRY_DELAY; the plugin answers those locally until 120s pass.
            delay(RETRY_DELAY * 5)

            assertEquals(1, requests.get(), "Retry-After: 120 must keep us off the server")
            assertNotNotAuthenticated(client)
            assertEquals(OLD_REFRESH_TOKEN, sessions.loadSession().refreshToken)
        }

    @Test
    fun `without the plugin supabase-kt clears the session on 429 and 525`(): Unit =
        runBlocking {
            // Pins the library behaviour this plugin exists for. If a supabase-kt bump fixes it,
            // this fails and AuthRefreshRetry can be reconsidered.
            for (code in listOf(429, 525)) {
                val (client, sessions, _) = clientAnswering(emptyList(), fallback = status(code), installPlugin = false)
                client.awaitStatus { it is SessionStatus.NotAuthenticated }
                assertNull(runCatching { sessions.loadSession() }.getOrNull(), "HTTP $code cleared the session")
            }
        }

    @Test
    fun `non-refresh requests are passed through untouched`(): Unit =
        runBlocking {
            val requests = AtomicInteger()
            val http =
                HttpClient(
                    MockEngine {
                        requests.incrementAndGet()
                        respond("", HttpStatusCode.TooManyRequests)
                    },
                ) { install(AuthRefreshRetry) { sleep = {} } }

            val response = http.post("$URL/auth/v1/token?grant_type=password")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals(1, requests.get())
            http.close()
        }

    @Test
    fun `the cooldown blocks network calls until it passes, and success resets it`(): Unit =
        runBlocking {
            val clock = AtomicLong(1_000_000L)
            val answers = ArrayDeque(listOf(429, 503, 200, 429, 429, 200))
            val requests = AtomicInteger()
            val http =
                HttpClient(
                    MockEngine {
                        requests.incrementAndGet()
                        respond("{}", HttpStatusCode.fromValue(answers.removeFirst()))
                    },
                ) {
                    install(AuthRefreshRetry) {
                        sleep = {}
                        jitter = { it }
                        nowMillis = { clock.get() }
                    }
                }

            suspend fun refresh() = runCatching { http.post("$URL/auth/v1/token?grant_type=refresh_token") }

            // 429 then 503: both attempts spent, 15s cooldown starts.
            assertIs<TransientAuthRefreshException>(refresh().exceptionOrNull())
            assertEquals(2, requests.get())

            // Inside the cooldown nothing is sent.
            clock.addAndGet(14_000)
            assertIs<TransientAuthRefreshException>(refresh().exceptionOrNull())
            assertEquals(2, requests.get(), "refused locally during the cooldown")

            // Past it, the refresh goes out and succeeds, which resets the backoff.
            clock.addAndGet(2_000)
            assertEquals(HttpStatusCode.OK, refresh().getOrThrow().status)
            assertEquals(3, requests.get())

            // A new failure starts again from the first 15s step, not 30s.
            assertIs<TransientAuthRefreshException>(refresh().exceptionOrNull())
            assertEquals(5, requests.get())
            clock.addAndGet(15_001)
            assertEquals(HttpStatusCode.OK, refresh().getOrThrow().status)
            assertEquals(6, requests.get())
            http.close()
        }

    @Test
    fun `the cooldown doubles per consecutive failure, caps at 5 minutes, honours Retry-After and resets`() {
        val now = AtomicLong(0L)
        val config =
            AuthRefreshRetryConfig().apply {
                jitter = { it }
                nowMillis = { now.get() }
            }
        val cooldown = RefreshCooldown(config)

        val steps = (1..7).map { cooldown.onTransientFailure(retryAfter = null) }
        assertEquals(listOf(15, 30, 60, 120, 240, 300, 300).map { it.seconds }, steps)
        assertEquals(300_000L, cooldown.remainingMs())

        cooldown.onAnswer()
        assertEquals(0L, cooldown.remainingMs())
        assertEquals(15.seconds, cooldown.onTransientFailure(retryAfter = null), "reset to the first step")

        // A longer Retry-After wins over the computed step; a shorter one does not shorten it.
        assertEquals(120.seconds, cooldown.onTransientFailure(retryAfter = 120.seconds))
        assertEquals(60.seconds, cooldown.onTransientFailure(retryAfter = 5.seconds))

        // Full jitter: the wait is drawn from 0..ceiling.
        val ceilings = mutableListOf<Long>()
        val jittered =
            RefreshCooldown(
                AuthRefreshRetryConfig().apply {
                    jitter = { ceiling -> ceiling.also { ceilings += it } / 4 }
                    nowMillis = { 0L }
                },
            )
        assertEquals(3_750.milliseconds, jittered.onTransientFailure(retryAfter = null))
        assertEquals(listOf(15_000L), ceilings)
    }

    @Test
    fun `Retry-After parses delta-seconds and HTTP-dates and is capped`() {
        val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        val config = AuthRefreshRetryConfig().apply { nowMillis = { now } }

        assertEquals(30.seconds, parseRetryAfter("30", config))
        assertEquals(5.minutes, parseRetryAfter("86400", config), "capped at maxRetryAfter")
        assertEquals(0.seconds, parseRetryAfter("-5", config))
        assertNull(parseRetryAfter("soon", config))
        assertEquals(60.seconds, parseRetryAfter("Tue, 14 Nov 2023 22:14:20 GMT", config))
    }

    private fun assertTransientKeepsSession(code: Int): Unit =
        runBlocking {
            val (client, sessions, requests) = clientAnswering(emptyList(), fallback = status(code))

            awaitRequests(requests, MAX_ATTEMPTS)
            // supabase-kt keeps retrying every RETRY_DELAY; the cooldown answers those locally.
            delay(RETRY_DELAY * 5)

            assertEquals(MAX_ATTEMPTS, requests.get(), "HTTP $code: the cooldown must keep later ticks off the server")
            assertNotNotAuthenticated(client)
            assertEquals(
                OLD_REFRESH_TOKEN,
                sessions.loadSession().refreshToken,
                "HTTP $code must not clear the stored session",
            )
        }

    private data class Harness(
        val client: SupabaseClient,
        val sessions: MemorySessionManager,
        val requests: AtomicInteger,
    )

    @OptIn(SupabaseInternal::class)
    private fun clientAnswering(
        responses: List<MockRequestHandleScope.() -> HttpResponseData>,
        fallback: (MockRequestHandleScope.() -> HttpResponseData)? = null,
        installPlugin: Boolean = true,
        clock: AtomicLong? = null,
    ): Harness {
        val requests = AtomicInteger()
        val sessions = MemorySessionManager(expiredSession())
        val engine =
            MockEngine { request ->
                val n = requests.getAndIncrement()
                assertRefreshRequest(request)
                val answer = responses.getOrNull(n) ?: fallback ?: error("unexpected request #${n + 1}")
                answer()
            }
        val client =
            createSupabaseClient(URL, "test-anon-key") {
                install(Auth) {
                    alwaysAutoRefresh = true
                    autoLoadFromStorage = true
                    retryDelay = RETRY_DELAY
                    sessionManager = sessions
                    enableLifecycleCallbacks = false
                }
                httpEngine = engine
                if (installPlugin) {
                    httpConfig {
                        install(AuthRefreshRetry) {
                            maxAttempts = MAX_ATTEMPTS
                            sleep = {}
                            jitter = { it } // deterministic: always the full cooldown
                            if (clock != null) nowMillis = { clock.get() }
                        }
                    }
                }
            }
        clients += client
        return Harness(client, sessions, requests)
    }

    private fun assertRefreshRequest(request: HttpRequestData) {
        assertEquals("/auth/v1/token", request.url.encodedPath)
        assertEquals("refresh_token", request.url.parameters["grant_type"])
        assertTrue((request.body as TextContent).text.contains(OLD_REFRESH_TOKEN))
    }

    private suspend fun SupabaseClient.awaitStatus(predicate: (SessionStatus) -> Boolean): SessionStatus =
        withTimeout(10.seconds) { auth.sessionStatus.first(predicate) }

    private suspend fun awaitRequests(
        requests: AtomicInteger,
        atLeast: Int,
    ) = withTimeout(10.seconds) {
        while (requests.get() < atLeast) delay(10.milliseconds)
    }

    private fun assertNotNotAuthenticated(client: SupabaseClient) {
        val status = client.auth.sessionStatus.value
        assertFalse(status is SessionStatus.NotAuthenticated, "session was cleared: $status")
    }

    private companion object {
        const val URL = "https://project.supabase.co"
        const val OLD_REFRESH_TOKEN = "old-refresh-token-must-never-be-logged"
        const val NEW_REFRESH_TOKEN = "new-refresh-token"
        const val NEW_ACCESS_TOKEN = "new-access-token"
        const val MAX_ATTEMPTS = 2
        val RETRY_DELAY = 50.milliseconds

        /** Past its expiry, so supabase-kt refreshes it as soon as it loads it from storage. */
        fun expiredSession() =
            UserSession(
                accessToken = "old-access-token",
                refreshToken = OLD_REFRESH_TOKEN,
                expiresIn = 3600,
                tokenType = "bearer",
                expiresAt = Clock.System.now() - 1.minutes,
            )

        fun status(
            code: Int,
            retryAfter: String? = null,
        ): MockRequestHandleScope.() -> HttpResponseData =
            {
                respond(
                    content =
                        """{"code":$code,"error_code":"over_request_rate_limit",""" +
                            """"msg":"Request rate limit reached"}""",
                    status = HttpStatusCode.fromValue(code),
                    headers =
                        if (retryAfter == null) {
                            headersOf(HttpHeaders.ContentType, "application/json")
                        } else {
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.RetryAfter to listOf(retryAfter),
                            )
                        },
                )
            }

        fun json(
            status: HttpStatusCode,
            body: String,
        ): MockRequestHandleScope.() -> HttpResponseData = { respond(body, status, JSON_HEADERS) }

        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

        fun invalidGrant() =
            json(
                HttpStatusCode.BadRequest,
                """{"code":400,"error_code":"refresh_token_not_found",""" +
                    """"msg":"Invalid Refresh Token: Refresh Token Not Found"}""",
            )

        fun success() =
            json(
                HttpStatusCode.OK,
                """{"access_token":"$NEW_ACCESS_TOKEN","refresh_token":"$NEW_REFRESH_TOKEN",""" +
                    """"expires_in":3600,"token_type":"bearer"}""",
            )
    }
}
