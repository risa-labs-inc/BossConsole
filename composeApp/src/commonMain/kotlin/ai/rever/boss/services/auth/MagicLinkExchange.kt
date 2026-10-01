package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.getSupabaseAnonKey
import ai.rever.boss.services.supabase.getSupabaseUrl
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exchanges a magic link for a session WITHOUT letting it near the live Supabase client until
 * the account it signs in is the one [AuthFlowMarker.Flow] says the link was sent to.
 *
 * A magic link is an opaque token hash: nothing in it names the account, and nothing can be read
 * from it without spending it. `Auth.verifyEmailOtp` spends it AND imports the result into the
 * client, whose session-status collector then publishes the user and the signed-in state - so a
 * check after it is an undo, and an undo can fail. This calls GoTrue's `/verify` itself instead:
 * the minted session lives only in this function until [Minted.email] matches the flow, and is
 * then imported; a session for any other account (or one whose account cannot be read) is revoked
 * and dropped, never imported, never persisted, never published.
 */
internal object MagicLinkExchange {
    private val logger = BossLogger.forComponent("MagicLinkExchange")

    private const val WRONG_ACCOUNT = "This sign-in link is for a different account than this BOSS window asked for."
    private const val NO_ACCOUNT = "This sign-in has no account to check the link against."

    /** A session GoTrue minted for a link, held outside the client until it is accepted. */
    data class Minted(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val userId: String,
        val email: String?,
        val userCreatedAt: String,
    )

    /** The two network calls, separated so the acceptance rules are testable without a server. */
    interface Transport {
        suspend fun verify(
            tokenHash: String,
            type: String,
        ): Minted

        /** Revokes [accessToken]'s session server-side; false when that could not be confirmed. */
        suspend fun revoke(accessToken: String): Boolean
    }

    class WrongAccountException : Exception(WRONG_ACCOUNT)

    internal var transport: Transport = HttpTransport

    /** The one step that touches the live client; runs only for an accepted session. */
    internal var importer: suspend (Minted) -> Result<Unit> = ::importIntoClient

    /**
     * Spends [tokenHash] for [flow]. Success means the session was for [flow]'s account and is now
     * this process's session; any failure means the client was never touched.
     */
    @Suppress("ReturnCount") // each refusal returns before anything reaches the client
    suspend fun exchange(
        tokenHash: String,
        type: String,
        flow: AuthFlowMarker.Flow,
    ): Result<Unit> {
        val expected =
            flow.emailHash
                ?: return Result.failure(IllegalStateException(NO_ACCOUNT))
        val minted =
            runCatching { transport.verify(tokenHash, type) }
                .getOrElse { return Result.failure(it) }
        if (minted.email?.let(AuthFlowMarker::hashEmail) != expected) {
            val revoked = runCatching { transport.revoke(minted.accessToken) }.getOrDefault(false)
            logger.warn(
                LogCategory.AUTH,
                "Refused a sign-in link minted for a different account; its session was never imported",
                mapOf("revoked" to revoked),
            )
            return Result.failure(WrongAccountException())
        }
        return importer(minted)
    }

    private suspend fun importIntoClient(minted: Minted): Result<Unit> =
        SessionManager
            .establishSession(
                accessToken = minted.accessToken,
                refreshToken = minted.refreshToken,
                userInfo = UserInfo(minted.userId, minted.email.orEmpty(), minted.userCreatedAt),
                authMethod = SessionManager.AuthMethod.MAGIC_LINK,
                expiresIn = minted.expiresIn,
            )

    /** GoTrue over HTTP: `POST /auth/v1/verify` and `POST /auth/v1/logout?scope=local`. */
    private object HttpTransport : Transport {
        private const val TIMEOUT_MS = 20_000L

        private val json = Json { ignoreUnknownKeys = true }

        private val client by lazy { HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = TIMEOUT_MS } } }

        @Serializable
        private data class VerifyResponse(
            @SerialName("access_token") val accessToken: String,
            @SerialName("refresh_token") val refreshToken: String,
            @SerialName("expires_in") val expiresIn: Long,
            val user: User,
        )

        @Serializable
        private data class User(
            val id: String,
            val email: String? = null,
            @SerialName("created_at") val createdAt: String = "",
        )

        override suspend fun verify(
            tokenHash: String,
            type: String,
        ): Minted {
            val response =
                client.post("${getSupabaseUrl().trimEnd('/')}/auth/v1/verify") {
                    header("apikey", getSupabaseAnonKey())
                    header(HttpHeaders.Authorization, "Bearer ${getSupabaseAnonKey()}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("type", type)
                            put("token_hash", tokenHash)
                        }.toString(),
                    )
                }
            // The body is GoTrue's own error text on failure; on success it holds tokens, so it is
            // never logged.
            check(response.status.isSuccess()) { "The sign-in link was not accepted (${response.status.value})" }
            val body = json.decodeFromString(VerifyResponse.serializer(), response.bodyAsText())
            return Minted(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken,
                expiresIn = body.expiresIn,
                userId = body.user.id,
                email = body.user.email,
                userCreatedAt = body.user.createdAt,
            )
        }

        override suspend fun revoke(accessToken: String): Boolean =
            client
                .post("${getSupabaseUrl().trimEnd('/')}/auth/v1/logout?scope=local") {
                    header("apikey", getSupabaseAnonKey())
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }.status
                .isSuccess()
    }
}
