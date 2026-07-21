package ai.rever.boss.service.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.createSupabaseClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Wraps the Supabase Kotlin client to provide auth operations for AuthServiceGrpcImpl.
 *
 * Reads SUPABASE_URL and SUPABASE_ANON_KEY from environment variables at construction time.
 * Uses CIO as the Ktor HTTP engine (added to boss-service-auth dependencies).
 */
class SupabaseAuthClient(
    supabaseUrl: String = System.getenv("SUPABASE_URL") ?: "https://api.risaboss.com",
    supabaseAnonKey: String = System.getenv("SUPABASE_ANON_KEY") ?: "",
) {
    private val logger = LoggerFactory.getLogger(SupabaseAuthClient::class.java)

    private val client = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseAnonKey,
    ) {
        install(Auth)
    }

    /** Signs in with email + password. Returns [AuthResult.Success] on success. */
    suspend fun signInWithEmailPassword(email: String, password: String): AuthResult {
        return try {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            buildSuccessResult(fallbackEmail = email)
        } catch (e: Exception) {
            logger.error("Email/password sign-in failed for {}", maskEmail(email), e)
            AuthResult.Failure(e.message ?: "Sign-in failed")
        }
    }

    /**
     * Sends a magic link to the given email.
     * Returns [AuthResult.MagicLinkSent] immediately — the actual session arrives
     * when the user clicks the link and the deep-link callback updates state.
     */
    suspend fun sendMagicLink(email: String): AuthResult {
        return try {
            client.auth.signInWith(OTP) {
                this.email = email
            }
            AuthResult.MagicLinkSent(email)
        } catch (e: Exception) {
            logger.error("Magic link send failed for {}", maskEmail(email), e)
            AuthResult.Failure(e.message ?: "Magic link failed")
        }
    }

    /** Signs out and clears the local session. Errors are logged but not re-thrown. */
    suspend fun signOut() {
        try {
            client.auth.signOut()
        } catch (e: Exception) {
            logger.warn("Sign-out error (clearing local state anyway): {}", e.message)
        }
    }

    /**
     * Attempts to restore a previously stored session (e.g. from disk or env).
     * Call this at service startup before accepting requests.
     */
    suspend fun restoreSession(): AuthResult {
        return try {
            client.auth.awaitInitialization()
            buildSuccessResult(fallbackEmail = null)
        } catch (e: Exception) {
            logger.debug("No session to restore: {}", e.message)
            AuthResult.Failure("No session")
        }
    }

    private fun buildSuccessResult(fallbackEmail: String?): AuthResult {
        val session = client.auth.currentSessionOrNull()
            ?: return AuthResult.Failure("No session available after auth operation")
        val user = session.user
        return AuthResult.Success(
            userId = user?.id ?: "",
            email = user?.email ?: fallbackEmail ?: "",
            displayName = user?.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull ?: "",
            isAdmin = user?.userMetadata?.get("is_admin")?.jsonPrimitive?.contentOrNull == "true",
            sessionToken = session.accessToken,
            sessionCreatedAt = System.currentTimeMillis() / 1000,
        )
    }

    private fun maskEmail(email: String): String =
        if (email.length > 3) "${email.take(3)}***" else "***"
}

sealed class AuthResult {
    data class Success(
        val userId: String,
        val email: String,
        val displayName: String,
        val isAdmin: Boolean,
        val sessionToken: String,
        val sessionCreatedAt: Long,
    ) : AuthResult()

    data class MagicLinkSent(val email: String) : AuthResult()

    data class Failure(val message: String) : AuthResult()
}
