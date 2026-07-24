package ai.rever.boss.services.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer

/**
 * Persistent storage for user data to survive app restarts
 *
 * WHY THIS EXISTS (Important - Not a Workaround!):
 * ================================================
 *
 * This is the CORRECT solution for custom authentication providers with Supabase.
 * It is NOT a hack or temporary workaround - it's the recommended pattern.
 *
 * Background:
 * -----------
 * Supabase Auth was designed for built-in authentication providers (OAuth, email/password, magic links).
 * When you use a built-in provider, Supabase generates JWT tokens that include full user information,
 * and the Supabase-KT client automatically populates session.user with this data.
 *
 * Custom Authentication Providers (like Passkeys):
 * ------------------------------------------------
 * For custom authentication providers (WebAuthn/passkeys), we implement the authentication
 * logic ourselves:
 *
 * 1. Client verifies passkey signature (Touch ID, Windows Hello, etc.)
 * 2. Edge Function generates Supabase-compatible JWT tokens
 * 3. Client imports session using auth.importSession()
 * 4. **Problem**: Supabase-KT intentionally does NOT populate session.user from custom JWTs
 *    - This is by design, not a bug
 *    - Custom JWTs don't include the user metadata that built-in providers include
 *    - The session.user property remains null
 *
 * 5. **Solution**: UserDataStorage persists user information separately
 *    - We store user data (id, email, createdAt) in local storage
 *    - This data persists across app restarts
 *    - SessionManager coordinates between Supabase auth (JWT tokens) and UserDataStorage (user info)
 *
 * Why Not Use Magic Links Instead?
 * --------------------------------
 * Magic links would populate session.user, but they:
 * - Break the passwordless/biometric UX flow
 * - Add unnecessary friction (email verification step)
 * - Defeat the purpose of passkey authentication
 * - Are less secure (email interception risk)
 *
 * The Correct Pattern:
 * -------------------
 * For custom authentication providers with Supabase:
 * 1. Implement authentication logic yourself (verify passkey, etc.)
 * 2. Generate Supabase-compatible JWT tokens on the backend
 * 3. Use importSession() to establish the Supabase session (for API access)
 * 4. Persist user data separately (UserDataStorage) for app state
 * 5. Use SessionManager to coordinate both
 *
 * This pattern is used by many Supabase applications that implement custom auth providers.
 *
 * Related Documentation:
 * ---------------------
 * - See SessionManager.kt for session orchestration logic
 * - See PasskeyAuthService.kt for passkey authentication implementation
 * - See CoreAuthService.kt for session initialization and restoration
 *
 * Storage Location:
 * ----------------
 * User data is stored in: ~/.boss/user_data.json
 * This file is automatically created and managed by this service.
 */
object UserDataStorage {
    private val storageFile = BossDirectories.resolve("user_data.json")
    private val pendingWizardCompletedFile = BossDirectories.resolve("pending_wizard_completed")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val logger = BossLogger.forComponent("UserDataStorage")

    @Serializable
    data class StoredUserData(
        val id: String,
        val email: String,
        val createdAt: String,
        val authenticatedVia: String? = null,  // "passkey", "magic_link", "password", etc.
        val pluginWizardCompleted: Boolean = false  // Whether the plugin install wizard has been completed
    )

    init {
        // Ensure directory exists
        storageFile.parentFile?.mkdirs()
    }

    /**
     * Save user data to persistent storage
     *
     * Preserves the pluginWizardCompleted flag if it was previously set,
     * and also merges any pending wizard completion status.
     */
    suspend fun saveUserData(user: UserInfo, authenticatedVia: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                // Check for pending wizard completed status (set before login)
                val pendingWizardCompleted = if (pendingWizardCompletedFile.exists()) {
                    try {
                        pendingWizardCompletedFile.readText().trim().toBoolean()
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.AUTH,
                            "Could not read pending wizard-completed marker - assuming false",
                            mapOf("error" to e.toString()),
                        )
                        false
                    }
                } else {
                    false
                }

                // Preserve existing pluginWizardCompleted status if file exists
                val existingWizardCompleted = if (storageFile.exists()) {
                    try {
                        val existingContent = storageFile.readText()
                        val existingData = json.decodeFromString<StoredUserData>(existingContent)
                        existingData.pluginWizardCompleted
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.AUTH,
                            "Could not read stored wizard status - assuming false",
                            mapOf("error" to e.toString()),
                        )
                        false
                    }
                } else {
                    false
                }

                // Use pending status OR existing status (either one being true means completed)
                val wizardCompleted = pendingWizardCompleted || existingWizardCompleted

                val data = StoredUserData(
                    id = user.id,
                    email = user.email,
                    createdAt = user.createdAt,
                    authenticatedVia = authenticatedVia,
                    pluginWizardCompleted = wizardCompleted
                )
                val content = json.encodeToString(data)
                storageFile.writeText(content)
                logger.debug(LogCategory.AUTH, "Saved user data", mapOf("email" to LogSanitizer.maskEmail(user.email)))

                // Clean up pending file if it exists
                if (pendingWizardCompletedFile.exists()) {
                    pendingWizardCompletedFile.delete()
                }
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error saving user data", error = e)
            }
        }
    }

    /**
     * Load user data from persistent storage
     */
    suspend fun loadUserData(): UserInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (storageFile.exists()) {
                    val content = storageFile.readText()
                    val data = json.decodeFromString<StoredUserData>(content)
                    logger.debug(LogCategory.AUTH, "Loaded user data", mapOf(
                        "email" to LogSanitizer.maskEmail(data.email),
                        "authenticatedVia" to (data.authenticatedVia ?: "unknown")
                    ))
                    UserInfo(
                        id = data.id,
                        email = data.email,
                        createdAt = data.createdAt
                    )
                } else {
                    logger.debug(LogCategory.AUTH, "No stored user data found")
                    null
                }
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error loading user data", error = e)
                null
            }
        }
    }

    /**
     * Clear stored user data (on logout)
     */
    suspend fun clearUserData() {
        withContext(Dispatchers.IO) {
            try {
                if (storageFile.exists()) {
                    storageFile.delete()
                    logger.debug(LogCategory.AUTH, "Cleared user data")
                }
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error clearing user data", error = e)
            }
        }
    }

    /**
     * Check if the plugin installation wizard has been completed for this user.
     *
     * Checks both the main user_data.json and the pending file (for cases where
     * the wizard was completed before user logged in).
     */
    suspend fun isPluginWizardCompleted(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // First check the main user data file
                if (storageFile.exists()) {
                    try {
                        val content = storageFile.readText()
                        val data = json.decodeFromString<StoredUserData>(content)
                        if (data.pluginWizardCompleted) {
                            return@withContext true
                        }
                    } catch (e: kotlinx.serialization.SerializationException) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "User data file corrupted, will reset on next save",
                            error = e
                        )
                        // Don't delete here - let next save handle it
                        // Fall through to check pending file
                    } catch (e: Exception) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Error reading user data file",
                            error = e
                        )
                        // Fall through to check pending file
                    }
                }

                // Also check the pending file (wizard completed before login)
                if (pendingWizardCompletedFile.exists()) {
                    try {
                        val pendingValue = pendingWizardCompletedFile.readText().trim().toBoolean()
                        if (pendingValue) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Error reading pending wizard file",
                            error = e
                        )
                    }
                }

                false
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error checking plugin wizard status", error = e)
                false
            }
        }
    }

    /**
     * Mark the plugin installation wizard as completed for this user.
     *
     * If user_data.json doesn't exist yet (user not logged in), stores the setting
     * in a separate file that will be merged when the user logs in.
     */
    suspend fun setPluginWizardCompleted(completed: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                if (storageFile.exists()) {
                    val content = storageFile.readText()
                    val data = json.decodeFromString<StoredUserData>(content)
                    val updatedData = data.copy(pluginWizardCompleted = completed)
                    storageFile.writeText(json.encodeToString(updatedData))
                    logger.debug(LogCategory.AUTH, "Updated plugin wizard completion status", mapOf(
                        "completed" to completed
                    ))
                } else {
                    // File doesn't exist yet - store in a temporary pending file
                    // This will be merged when saveUserData is called
                    pendingWizardCompletedFile.parentFile?.mkdirs()
                    pendingWizardCompletedFile.writeText(completed.toString())
                }
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error setting plugin wizard status", error = e)
            }
        }
    }
}

