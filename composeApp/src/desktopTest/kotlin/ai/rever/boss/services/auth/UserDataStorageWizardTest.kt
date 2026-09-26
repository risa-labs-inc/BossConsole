package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogLevel
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hermetic coverage for [UserDataStorage.setPluginWizardCompleted] around a torn
 * user_data.json - the state a pre-atomic-write `writeText` left on existing installs, and the
 * one #762 kept reproducing: the wizard completes, the decode throws, nothing is persisted,
 * and the wizard runs again on the next launch.
 */
class UserDataStorageWizardTest {
    private lateinit var workDir: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("user-data-wizard-test-").toFile()
        UserDataStorage.resetForTesting(workDir)
    }

    @AfterTest
    fun tearDown() {
        UserDataStorage.afterGenerationCaptureForTest = null
        // Point the singleton back at the user's directory before anything else uses it.
        UserDataStorage.resetForTesting(BossDirectories.rootDir)
        workDir.deleteRecursively()
    }

    // Review on #1703: every decode of user_data.json logged the decoder's exception (or its
    // toString), whose message quotes the record - the user's email and id - into the log people
    // attach to bug reports. loadUserData is the startup path, at ERROR, so it fired every launch.
    @Test
    fun `every decode of a torn user record is logged without its email or id`() =
        runBlocking {
            val email = "leak-${System.nanoTime()}@example.com"
            val userId = "user-id-${System.nanoTime()}"
            val torn = """{"id":"$userId","email":"$email","createdAt":"""
            val entries = mutableListOf<LogEntry>()
            val listener = LogListener { entry -> synchronized(entries) { entries += entry } }
            val previousLevel = BossLogger.globalLevel
            // The stored-flag read logs at DEBUG, the level a user is asked to raise for a report.
            BossLogger.setGlobalLevel(LogLevel.DEBUG)
            BossLogger.addListener(listener)
            try {
                UserDataStorage.storageFile.writeText(torn)
                UserDataStorage.loadUserData()
                // Before any pending marker exists: a marker short-circuits the stored-flag read.
                UserDataStorage.saveUserData(
                    UserInfo(id = "saved-id", email = "saved@example.com", createdAt = "2026-01-01T00:00:00Z"),
                )
                // The save wrote a whole record; tear it again for the two wizard paths.
                UserDataStorage.storageFile.writeText(torn)
                UserDataStorage.setPluginWizardCompleted(true)
                UserDataStorage.isPluginWizardCompleted()
            } finally {
                BossLogger.removeListener(listener)
                BossLogger.setGlobalLevel(previousLevel)
            }

            val logged = synchronized(entries) { entries.toList() }
            for (arm in listOf(
                "Error loading user data",
                "Could not read stored wizard status",
                "user_data.json undecodable",
                "User data file corrupted",
            )) {
                val entry =
                    logged.singleOrNull { it.message.startsWith(arm) } ?: fail("no single '$arm' entry: $logged")
                assertNull(entry.error, "$arm: the decoder's exception quotes the record, so it must not be attached")
            }
            for (entry in logged) {
                val text = "${entry.message} ${entry.data} ${entry.error}"
                assertFalse(email in text || userId in text, "leaked in: $entry")
            }
        }

    @Test
    fun `completion is persisted via the pending marker when the stored record is corrupt`() =
        runBlocking {
            UserDataStorage.storageFile.writeText("{ this is not a record")

            UserDataStorage.setPluginWizardCompleted(true)

            assertTrue(
                UserDataStorage.pendingWizardCompletedFile.exists(),
                "the flag must survive the torn record via the pending marker",
            )
            assertEquals("true", UserDataStorage.pendingWizardCompletedFile.readText().trim())
            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the completed wizard must not re-run on the next launch",
            )
        }

    @Test
    fun `completion updates an intact record and leaves no pending marker`() =
        runBlocking {
            val user = UserInfo(id = "u-1", email = "a@example.com", createdAt = "2026-01-01T00:00:00Z")
            UserDataStorage.saveUserData(user)

            UserDataStorage.setPluginWizardCompleted(true)

            val stored =
                json.decodeFromString(
                    UserDataStorage.StoredUserData.serializer(),
                    UserDataStorage.storageFile.readText(),
                )
            assertTrue(stored.pluginWizardCompleted)
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    @Test
    fun `completion without a record goes to the pending marker`() =
        runBlocking {
            UserDataStorage.setPluginWizardCompleted(true)

            assertTrue(UserDataStorage.pendingWizardCompletedFile.exists())
            assertFalse(UserDataStorage.storageFile.exists())
        }

    /**
     * The logout generation fence (BossConsole#762, review follow-up on the merged #795):
     * a save that entered before logout but acquires the lock only after clearUserData ran
     * must NOT recreate user_data.json. A hook after the production entry point captures its
     * generation drives the interleaving deterministically without bypassing that capture.
     */
    @Test
    fun `a save that acquires the lock only after logout cannot resurrect the cleared record`() =
        runBlocking {
            val user = UserInfo(id = "u1", email = "fenced@example.com", createdAt = "2026-09-17T00:00:00Z")
            UserDataStorage.saveUserData(user)
            assertTrue(UserDataStorage.storageFile.exists())

            var hookRan = false
            UserDataStorage.afterGenerationCaptureForTest = {
                UserDataStorage.afterGenerationCaptureForTest = null
                UserDataStorage.clearUserData()
                hookRan = true
            }

            // saveUserData captures its generation, the hook completes logout, then the save
            // reaches the lock with its pre-logout generation and must be skipped.
            UserDataStorage.saveUserData(user)
            assertTrue(hookRan, "the test must drive logout after the production capture point")
            assertFalse(
                UserDataStorage.storageFile.exists(),
                "a save entered before logout but executed after clearUserData must not recreate the record",
            )

            // A save entered AFTER logout (fresh generation) still works.
            UserDataStorage.saveUserData(user)
            assertEquals("fenced@example.com", UserDataStorage.loadUserData()?.email)
        }
}
