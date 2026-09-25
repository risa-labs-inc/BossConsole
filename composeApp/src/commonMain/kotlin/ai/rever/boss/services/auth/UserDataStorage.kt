package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
    /** Redirected by [resetForTesting] for hermetic unit tests; production code never reassigns it. */
    internal var storageFile: File = BossDirectories.resolve("user_data.json")

    /** Same redirect as [storageFile]; the two files always live in the same directory. */
    internal var pendingWizardCompletedFile: File = BossDirectories.resolve("pending_wizard_completed")
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val logger = BossLogger.forComponent("UserDataStorage")

    /**
     * Fences logout against in-flight saves (BossConsole#762, review follow-up on the merged
     * #795): a `saveUserData` call that *entered* before logout but acquires [fileLock] only
     * after `clearUserData()` ran would recreate `user_data.json` with the logged-out user's
     * identity - the mutex cannot order a caller that already passed it. The save captures the
     * generation before acquiring the lock and re-checks it inside; a clear that happened while
     * it waited invalidates the save, so the resurrection path is closed.
     *
     * This deliberately fails closed: a save for a new identity that entered before a lagging
     * clear is skipped too. That window is small, and preserving logged-out identity is the more
     * serious failure; the skip is therefore logged as a warning for diagnosis.
     */
    private val clearGeneration = AtomicLong()

    /** Test seam invoked after the production entry point captures the generation. */
    internal var afterGenerationCaptureForTest: (suspend () -> Unit)? = null

    /**
     * The save body with an explicit entry generation, so the fence's regression test can
     * hand a save the generation it *would have* captured before a logout interleaved,
     * driving the capture-clear-acquire order deterministically without coroutine scheduling.
     */
    private suspend fun doSaveUserData(
        user: UserInfo,
        authenticatedVia: String?,
        generationAtEntry: Long,
    ) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                if (generationAtEntry != clearGeneration.get()) {
                    logger.warn(
                        LogCategory.AUTH,
                        "Skipping user data save: logout occurred while the save waited for the lock",
                    )
                    return@withLock
                }
                try {
                    val wizardCompleted = readPendingWizardFlag() || readStoredWizardFlag()
                    val data =
                        StoredUserData(
                            id = user.id,
                            email = user.email,
                            createdAt = user.createdAt,
                            authenticatedVia = authenticatedVia,
                            pluginWizardCompleted = wizardCompleted,
                        )
                    val content = json.encodeToString(data)
                    // Atomic: `writeText` truncates first, so a crash or a concurrent writer leaves a
                    // half-written user_data.json. That parses as corrupt on the next launch, the user
                    // is treated as logged out, and the plugin wizard runs again.
                    storageFile.atomicWriteText(content)
                    logger.debug(
                        LogCategory.AUTH,
                        "Saved user data",
                        mapOf("email" to LogSanitizer.maskEmail(user.email)),
                    )
                    if (pendingWizardCompletedFile.exists()) {
                        pendingWizardCompletedFile.delete()
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error saving user data", error = e)
                }
            }
        }
    }

    /**
     * Serialises every read-modify-write of [storageFile].
     *
     * `saveUserData` and `setPluginWizardCompleted` both read the file, edit one field, and write
     * the whole record back. Nothing coordinated them, and both are reachable at once: the plugin
     * wizard can finish while a session restore is saving user data, and the loser's field is
     * silently reverted - the wizard re-runs on the next launch because `pluginWizardCompleted`
     * went back to false. `clearUserData` takes the lock too, so logout cannot delete the file
     * between another writer's read and its write and have that writer recreate it.
     */
    private val fileLock = Mutex()

    @Serializable
    data class StoredUserData(
        val id: String,
        val email: String,
        val createdAt: String,
        val authenticatedVia: String? = null, // "passkey", "magic_link", "password", etc.
        val pluginWizardCompleted: Boolean = false, // Whether the plugin install wizard has been completed
    )

    init {
        // Ensure directory exists
        storageFile.parentFile?.mkdirs()
    }

    /**
     * Point both files at [testDir] for hermetic unit testing. Tests must call this again with
     * the real directory (e.g. [BossDirectories.rootDir]) before finishing, so the singleton is
     * left where the app and other tests expect it.
     */
    internal fun resetForTesting(testDir: File) {
        storageFile = testDir.resolve("user_data.json")
        pendingWizardCompletedFile = testDir.resolve("pending_wizard_completed")
    }

    /**
     * The wizard-completed marker written before the user logged in, or false if it is absent or
     * unreadable. Call while holding [fileLock].
     */
    private fun readPendingWizardFlag(): Boolean {
        if (!pendingWizardCompletedFile.exists()) return false
        return try {
            pendingWizardCompletedFile.readText().trim().toBoolean()
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read pending wizard-completed marker - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * The wizard-completed flag already in [storageFile], or false if it is absent or unreadable.
     * Read so that saving user data preserves it rather than resetting it. Call while holding
     * [fileLock].
     */
    private fun readStoredWizardFlag(): Boolean {
        if (!storageFile.exists()) return false
        return try {
            json.decodeFromString<StoredUserData>(storageFile.readText()).pluginWizardCompleted
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read stored wizard status - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * Flush pending user-data writes before the process exits.
     *
     * This object has no debounce - every save is a synchronous read-modify-write under
     * [fileLock] - so unlike [ai.rever.boss.dashboard.RecentFilesManager.flushPendingSaves]
     * there is no timer window a quit can fall inside and nothing buffered to force out.
     * The seam still earns its place on the exit path: acquiring [fileLock] waits for a
     * write that is already mid-flight to finish before returning, so the quit cannot cut
     * down a half-written record. A write that has not started yet is not awaited - its
     * caller awaits it in the normal flow - and keeping this beside the recent-files flush
     * means a future debounced write here gets exit-safety without the quit path gaining a
     * new step.
     */
    suspend fun flushPendingSaves() {
        fileLock.withLock {
            // Nothing is buffered: every write here is synchronous under this lock, so the
            // wait itself is the contract - it lets an in-flight write finish first.
            logger.debug(
                LogCategory.AUTH,
                "Flushed pending user-data saves on exit",
                mapOf("present" to storageFile.exists()),
            )
        }
    }

    /**
     * Save user data to persistent storage
     *
     * Preserves the pluginWizardCompleted flag if it was previously set,
     * and also merges any pending wizard completion status.
     */
    suspend fun saveUserData(
        user: UserInfo,
        authenticatedVia: String? = null,
    ) {
        // The fence's generation capture happens inside the delegate, before its lock
        // acquisition (BossConsole#762): a clear that runs while this save waits
        // invalidates it, so a save entered before logout cannot recreate the record.
        val generationAtEntry = clearGeneration.get()
        afterGenerationCaptureForTest?.invoke()
        doSaveUserData(user, authenticatedVia, generationAtEntry)
    }

    /**
     * Load user data from persistent storage
     */
    suspend fun loadUserData(): UserInfo? =
        withContext(Dispatchers.IO) {
            try {
                if (storageFile.exists()) {
                    val content = storageFile.readText()
                    val data = json.decodeFromString<StoredUserData>(content)
                    logger.debug(
                        LogCategory.AUTH,
                        "Loaded user data",
                        mapOf(
                            "email" to LogSanitizer.maskEmail(data.email),
                            "authenticatedVia" to (data.authenticatedVia ?: "unknown"),
                        ),
                    )
                    UserInfo(
                        id = data.id,
                        email = data.email,
                        createdAt = data.createdAt,
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

    /**
     * Clear stored user data (on logout).
     *
     * Deletes [storageFile] only: the pre-login [pendingWizardCompletedFile] marker is
     * deliberately left in place. It exists precisely for the "wizard finished before the
     * next login" state, and the next login's save merges it into the fresh record -
     * deleting it here would make a pre-login wizard completion vanish on logout and
     * re-run the wizard (pinned by `UserDataStorageMergeTest`).
     */
    suspend fun clearUserData() {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    // Bump the fence first so any save that captured an older
                    // generation while waiting on this lock is invalidated.
                    clearGeneration.incrementAndGet()
                    if (storageFile.exists()) {
                        storageFile.delete()
                        logger.debug(LogCategory.AUTH, "Cleared user data")
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error clearing user data", error = e)
                }
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
                            error = e,
                        )
                        // Don't delete here - let next save handle it
                        // Fall through to check pending file
                    } catch (e: Exception) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Error reading user data file",
                            error = e,
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
                            error = e,
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
     * in a separate file that will be merged when the user logs in. If the file exists but is
     * undecodable - the torn state a pre-atomic-write `writeText` left on existing installs -
     * the flag goes to that same pending marker: there is no record to copy it into, and none
     * can be fabricated without the user's identity; [saveUserData] merges the marker into a
     * fresh, whole record on the next login. Without the fallback the wizard would re-run on
     * every launch for exactly the installs issue #762 is about.
     */
    suspend fun setPluginWizardCompleted(completed: Boolean) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        val content = storageFile.readText()
                        try {
                            val data = json.decodeFromString<StoredUserData>(content)
                            val updatedData = data.copy(pluginWizardCompleted = completed)
                            // Atomic, and under the lock: read and write are one step, so a
                            // saveUserData landing in between cannot have its record
                            // overwritten by this copy of the older one.
                            storageFile.atomicWriteText(json.encodeToString(updatedData))
                            logger.debug(
                                LogCategory.AUTH,
                                "Updated plugin wizard completion status",
                                mapOf(
                                    "completed" to completed,
                                ),
                            )
                        } catch (e: kotlinx.serialization.SerializationException) {
                            // An existing but torn record: the flag has nowhere to go inside it,
                            // so persist it via the pending marker (the pre-login path). The
                            // next saveUserData merges the marker into a fresh, whole record.
                            logger.warn(
                                LogCategory.AUTH,
                                "user_data.json undecodable; persisting wizard status via pending marker",
                                error = e,
                            )
                            pendingWizardCompletedFile.atomicWriteText(completed.toString())
                        }
                    } else {
                        // File doesn't exist yet - store in a temporary pending file
                        // This will be merged when saveUserData is called
                        pendingWizardCompletedFile.atomicWriteText(completed.toString())
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error setting plugin wizard status", error = e)
                }
            }
        }
    }
}
