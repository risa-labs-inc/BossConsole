package ai.rever.boss.llm

import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A credential broker: an endpoint that exchanges the signed-in BOSS session for a
 * short-lived credential scoped to something downstream.
 *
 * [tokenUrl] lives here, in the host, and is **never** supplied by a caller. A plugin asks
 * for a broker by [id], so the worst it can do is name one this host does not have. An
 * `exchange(url)` shape would instead hand every installed plugin a way to post the user's
 * session token to a host of its choosing.
 */
internal data class CredentialBroker(
    val id: String,
    val displayName: String,
    val tokenUrl: String,
    /**
     * The endpoint prefix the issued credential is meant for, published to plugins so a
     * careful one can check before it posts a bearer token somewhere.
     */
    val scopedTo: String?,
)

/**
 * Every broker this build knows about.
 *
 * A registry rather than a constant so a second one costs an entry, and so
 * [RisaLlmTokenCommand] and the plugin-facing provider resolve the same way instead of
 * each carrying its own copy of the URL.
 */
internal object CredentialBrokers {
    const val RISA_GLM: String = "risa-glm"

    private const val RISA_TOKEN_URL = "https://llm.risa.inc/auth/token"
    private const val RISA_API_BASE = "https://llm.risa.inc/v1"

    /** Overrides RISA's token endpoint, for pointing a dev build at a staging gateway. */
    private const val RISA_TOKEN_URL_ENV = "RISA_LLM_TOKEN_URL"

    fun all(): List<CredentialBroker> =
        listOf(
            CredentialBroker(
                id = RISA_GLM,
                displayName = "RISA Codex GLM",
                tokenUrl =
                    System
                        .getenv(RISA_TOKEN_URL_ENV)
                        ?.takeIf { it.isNotBlank() }
                        ?: RISA_TOKEN_URL,
                scopedTo = RISA_API_BASE,
            ),
        )

    fun find(id: String): CredentialBroker? = all().firstOrNull { it.id == id }
}

/** What a broker returned. Mirrors the api's `BrokeredCredential` without depending on it. */
internal data class BrokeredToken(
    val token: String,
    val refreshAfterSeconds: Long,
    val expiresAt: String?,
)

/**
 * Exchanges the current session for a broker credential.
 *
 * The session never leaves this process: callers get the downstream credential and nothing
 * else. That is the whole reason the exchange is host-side - nothing on `PluginContext`
 * exposes the Supabase access token, and this keeps it that way.
 */
@OptIn(ExperimentalTime::class)
internal object CredentialBrokerClient {
    private val logger = BossLogger.forComponent("CredentialBroker")
    private val responseJson = Json { ignoreUnknownKeys = true }

    private const val REQUEST_TIMEOUT_MS = 90_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val SESSION_WAIT_ATTEMPTS = 100
    private const val SESSION_WAIT_DELAY_MS = 100L

    /** Whether a broker could be used right now, i.e. somebody is signed in. */
    fun isSignedIn(): Boolean =
        runCatching {
            SupabaseConfig.isInitialized.value &&
                SupabaseConfig.client.auth
                    .currentSessionOrNull() != null
        }.getOrDefault(false)

    suspend fun exchange(brokerId: String): Result<BrokeredToken> {
        val broker =
            CredentialBrokers.find(brokerId)
                ?: return Result.failure(
                    IllegalStateException("This BOSS build does not know a credential broker called '$brokerId'."),
                )
        return runCatching { exchangeOrThrow(broker) }
    }

    private suspend fun exchangeOrThrow(broker: CredentialBroker): BrokeredToken {
        if (!SupabaseConfig.isInitialized.value) {
            SupabaseConfig.initializeFromEnvironment()
        }

        var session =
            waitForSession()
                ?: error("Open BOSS and sign in with your RISA account, then retry.")

        val client =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
        return try {
            var response = post(client, broker, session)
            var body = response.second

            // A locally unexpired access token can still be rejected after a server-side
            // revocation or session migration. Refresh once and retry; never loop, and never
            // mask an authorization or entitlement failure as something retryable.
            if (response.first == HTTP_UNAUTHORIZED) {
                session = refreshedSession()
                response = post(client, broker, session)
                body = response.second
            }

            if (response.first != HTTP_OK) {
                error(parseBrokerError(body))
            }
            parseToken(body)
        } finally {
            client.close()
        }
    }

    private suspend fun post(
        client: HttpClient,
        broker: CredentialBroker,
        session: UserSession,
    ): Pair<Int, String> {
        val response =
            client.post(broker.tokenUrl) {
                header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
                header(HttpHeaders.Accept, "application/json")
            }
        return response.status.value to response.bodyAsText()
    }

    /**
     * Refreshes through [CoreAuthService], which owns the single-flight lock.
     *
     * Supabase rotates the refresh token, so a second refresh overlapping the app's own
     * presents an already-used one, and a rejected refresh token is what drops the user to
     * the login screen.
     */
    private suspend fun refreshedSession(): UserSession {
        try {
            CoreAuthService.refreshSession()
        } catch (_: Exception) {
            error("Your BOSS session expired. Open BOSS, sign in again, and retry.")
        }
        return SupabaseConfig.client.auth
            .currentSessionOrNull()
            ?: error("Your BOSS session expired. Open BOSS, sign in again, and retry.")
    }

    private suspend fun waitForSession(): UserSession? {
        var attempts = 0
        while (attempts < SESSION_WAIT_ATTEMPTS) {
            val session = SupabaseConfig.client.auth.currentSessionOrNull()
            if (session != null) {
                return if (session.expiresAt > Clock.System.now()) session else refreshedSession()
            }
            delay(SESSION_WAIT_DELAY_MS)
            attempts += 1
        }
        return null
    }

    private fun parseToken(body: String): BrokeredToken {
        val root = responseJson.parseToJsonElement(body).jsonObject
        val token =
            root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("The credential broker returned an empty token.")
        return BrokeredToken(
            token = token,
            // Absent means "do not reuse": a broker that did not say is not promising a
            // window, and inventing one risks holding a credential past its life.
            refreshAfterSeconds = root["refresh_after_seconds"]?.jsonPrimitive?.longOrNull ?: 0L,
            expiresAt = root["expires_at"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * The broker's own `error.message` when it sent one, never the raw body.
     *
     * A broker's error body is written for a person; anything else it contains is not, and
     * this string is shown in a panel and returned over the single-instance channel.
     */
    internal fun parseBrokerError(body: String): String =
        try {
            Json
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: DEFAULT_BROKER_ERROR
        } catch (_: Exception) {
            DEFAULT_BROKER_ERROR
        }

    private const val DEFAULT_BROKER_ERROR = "The credential broker rejected the token request."
    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401

    init {
        logger.trace(LogCategory.SYSTEM, "Credential brokers available", mapOf("count" to CredentialBrokers.all().size))
    }
}
