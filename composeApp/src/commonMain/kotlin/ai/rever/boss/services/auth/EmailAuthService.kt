package ai.rever.boss.services.auth

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.OtpType
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer

/**
 * Handles email-based authentication operations
 */
internal object EmailAuthService {

    private val logger = BossLogger.forComponent("EmailAuthService")

    /**
     * Mark email as verified - called when deep link indicates successful verification
     */
    suspend fun verifyEmail(token: String, type: String = "magiclink"): Result<Unit> {
        return try {
            logger.info(LogCategory.AUTH, "Email verification confirmed via deep link", mapOf("type" to type))
            
            // Use our magic link verification method with the correct type
            verifyMagicLinkToken(token, type = type).fold(
                onSuccess = {
                    logger.info(LogCategory.AUTH, "Magic link verification successful")
                    Result.success(Unit)
                },
                onFailure = { error ->
                    logger.warn(LogCategory.AUTH, "Magic link verification failed", error = error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Email verification processing failed", error = e)
            Result.failure(Exception("Failed to process email verification: ${e.message}"))
        }
    }
    

    /**
     * Send magic link to user's email for passwordless authentication
     * This works for both new signups and existing users (including unconfirmed ones)
     */
    suspend fun sendMagicLink(email: String): Result<Unit> {
        return try {
            logger.info(LogCategory.AUTH, "Sending magic link", mapOf("email" to LogSanitizer.maskEmail(email)))
            logger.debug(LogCategory.AUTH, "Using Supabase endpoint", mapOf("url" to SupabaseConfig.client.supabaseUrl))

            // signInWith(OTP) handles multiple cases:
            // 1. New user - creates unconfirmed user and sends signup link
            // 2. Existing confirmed user - sends login link
            // 3. Existing unconfirmed user - resends signup/confirmation link
            SupabaseConfig.client.auth.signInWith(OTP) {
                this.email = email
                // The createUser flag is true by default, which means:
                // - If user doesn't exist, create them (signup)
                // - If user exists (confirmed or not), just send the link
            }

            logger.info(LogCategory.AUTH, "Magic link sent successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Magic link sending failed", mapOf("exceptionType" to e.javaClass.simpleName), error = e)
            
            val errorMessage = when {
                e.message?.contains("User not found") == true -> 
                    "No account found with this email address"
                e.message?.contains("cancelled") == true ->
                    "Network request cancelled. Please check your internet connection."
                e.message?.contains("Email rate limit exceeded") == true ->
                    "Too many attempts. Please wait a few minutes before trying again."
                else -> e.message ?: "Failed to send magic link"
            }
            Result.failure(Exception(errorMessage))
        }
    }

    /**
     * Verify magic link token using SDK's verifyEmailOtp method
     * For magic links, we need to use token_hash verification
     */
    suspend fun verifyMagicLinkToken(token: String, email: String? = null, type: String = "magiclink"): Result<Boolean> {
        return try {
            logger.debug(LogCategory.AUTH, "Starting magic link verification", mapOf(
                "type" to type,
                "hasEmail" to (email != null),
                "tokenLength" to token.length
            ))

            // Try using the SDK's verifyEmailOtp with tokenHash
            // Magic links use token_hash verification
            val otpType = when(type) {
                "signup" -> OtpType.Email.SIGNUP
                "magiclink" -> OtpType.Email.MAGIC_LINK
                "recovery" -> OtpType.Email.RECOVERY
                "invite" -> OtpType.Email.INVITE
                else -> OtpType.Email.EMAIL
            }

            logger.debug(LogCategory.AUTH, "Mapped OTP type", mapOf("otpType" to otpType.toString()))

            // The SDK should handle the session properly
            // For magic links, we need the email address
            if (email != null) {
                logger.debug(LogCategory.AUTH, "Verifying with email", mapOf("email" to LogSanitizer.maskEmail(email)))
                // Use the version with email and token
                SupabaseConfig.client.auth.verifyEmailOtp(
                    type = otpType,
                    email = email,
                    token = token
                )
            } else {
                logger.debug(LogCategory.AUTH, "Verifying with tokenHash (no email)")
                // Fallback to tokenHash version if no email provided
                SupabaseConfig.client.auth.verifyEmailOtp(
                    type = otpType,
                    tokenHash = token
                )
            }

            logger.info(LogCategory.AUTH, "SDK verifyEmailOtp completed successfully")

            // Check if we have a session now
            val currentSession = SupabaseConfig.client.auth.currentSessionOrNull()
            val hasSession = currentSession != null
            logger.debug(LogCategory.AUTH, "Session state after verification", mapOf(
                "hasSession" to hasSession,
                "userEmail" to (currentSession?.user?.email?.let { LogSanitizer.maskEmail(it) } ?: "none")
            ))

            // Mark that user authenticated via magic link
            AuthStateManager.setAuthenticatedViaMagicLink(true)
            logger.info(LogCategory.AUTH, "Magic link verification complete")
            
            Result.success(true)
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "Magic link verification failed", mapOf(
                "exceptionType" to (e::class.simpleName ?: "unknown")
            ), error = e)
            
            val errorMessage = when {
                e.message?.contains("Invalid token") == true -> 
                    "This magic link has expired. Magic links are valid for 15 minutes. Please request a new one."
                e.message?.contains("already_used") == true -> 
                    "This magic link has already been used. Please request a new one if you need to sign in again."
                e.message?.contains("expired") == true ->
                    "This magic link has expired. Magic links are valid for 15 minutes. Please request a new one."
                e.message?.contains("JsonLiteral") == true ||
                e.message?.contains("JsonObject") == true -> {
                    "Server response format issue - please try again"
                }
                e.message?.contains("404") == true -> {
                    "Magic link verification endpoint not found"
                }
                e.message?.contains("cancelled") == true ->
                    "Network request cancelled. Please check your internet connection."
                else -> e.message ?: "Magic link verification failed"
            }
            
            Result.failure(Exception(errorMessage))
        }
    }
}
