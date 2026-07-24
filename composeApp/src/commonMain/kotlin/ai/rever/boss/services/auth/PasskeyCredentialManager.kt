package ai.rever.boss.services.auth

import ai.rever.boss.services.passkey.PasskeyInfo
import ai.rever.boss.services.passkey.SupabasePasskeyService
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlin.time.ExperimentalTime

/**
 * PasskeyCredentialManager - Manages passkey credentials (server-only operations)
 *
 * This service handles credential management operations:
 * - Listing user's registered passkeys from Supabase backend
 * - Deleting passkeys from Supabase backend
 *
 * Separated from PasskeyAuthService to follow Single Responsibility Principle.
 * Authentication logic (register/authenticate) remains in PasskeyAuthService.
 *
 * Note: This service no longer requires PasskeyService as all operations
 * are now performed through Supabase backend exclusively.
 */
@OptIn(ExperimentalTime::class)
object PasskeyCredentialManager {
    private val logger = BossLogger.forComponent("PasskeyCredentialManager")

    /**
     * Get user's registered passkeys from Supabase backend
     *
     * This method fetches passkeys from Supabase backend (source of truth).
     * Local credential management has been removed as authentication is now
     * handled entirely through browser-based WebAuthn.
     *
     * @return Result containing list of PasskeyInfo or error
     */
    suspend fun getUserPasskeys(): Result<List<PasskeyInfo>> {
        return try {
            val currentUser =
                AuthStateManager.currentUser.value
                    ?: return Result.failure(Exception("No user logged in"))

            // Get passkeys from Supabase backend (source of truth)
            val credentialsResult = SupabasePasskeyService.getUserPasskeys(currentUser.id)
            if (credentialsResult.isSuccess) {
                val credentials = credentialsResult.getOrThrow()
                val passkeyInfos =
                    credentials.map { credential ->
                        PasskeyInfo(
                            id = credential.id, // Database ID for deletion
                            credentialId = credential.credential_id,
                            displayName = credential.display_name,
                            createdAt = credential.created_at,
                            lastUsed = credential.last_used_at,
                            rpId = "api.risaboss.com",
                            transports = credential.transports,
                        )
                    }
                Result.success(passkeyInfos)
            } else {
                Result.failure(credentialsResult.exceptionOrNull() ?: Exception("Failed to fetch passkeys"))
            }
        } catch (e: Exception) {
            // Ignore cancellation exceptions (when composable is disposed)
            if (e is java.util.concurrent.CancellationException) {
                return Result.failure(e)
            }
            logger.error(LogCategory.PASSKEY, "Failed to load passkeys", error = e)
            Result.failure(e)
        }
    }

    /**
     * Delete a passkey from Supabase backend
     *
     * This method only deletes the passkey from Supabase backend.
     * Local credentials are no longer managed by the application.
     *
     * @param credentialId The credential ID to delete
     * @return Result indicating success or failure
     */
    suspend fun deletePasskey(credentialId: String): Result<Unit> {
        return try {
            val currentUser =
                AuthStateManager.currentUser.value
                    ?: return Result.failure(Exception("No user logged in"))

            // Delete from Supabase backend only
            val deleteResult = SupabasePasskeyService.deletePasskey(currentUser.id, credentialId)
            if (deleteResult.isFailure) {
                return Result.failure(deleteResult.exceptionOrNull() ?: Exception("Failed to delete passkey"))
            }

            logger.info(LogCategory.PASSKEY, "Successfully deleted passkey from server")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to delete passkey", error = e)
            Result.failure(e)
        }
    }
}
