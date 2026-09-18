package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [UserDataStorage]'s coordinated save path, complementing the single-flow
 * scenarios in [UserDataStorageWizardTest]:
 *
 * 1. The merge contract of `saveUserData`: the fresh record folds in the wizard flag from the
 *    pending marker OR the previously stored record, and a successful save consumes the marker.
 *    Issue #762's visible symptom was exactly this flag going missing across a save.
 * 2. `clearUserData` under the storage mutex: logout cannot delete the record between another
 *    writer's read and its write, and a save that entered before logout cannot recreate the
 *    record - or consume the pending marker - once the clear ran (the generation fence from
 *    #762's review follow-up on #795).
 * 3. The lost-update regression the mutex exists for: `saveUserData` and
 *    `setPluginWizardCompleted` both rewrite the whole record; uncoordinated, the loser
 *    silently reverts the winner's field and the wizard re-runs on the next launch.
 * 4. Torn-write protection on the save path: the atomic temp+move must never expose a
 *    half-written record to a reader and must never leak its temp file.
 *
 * The race tests assert invariants that hold for EVERY serialization the mutex permits, so they
 * cannot flake on coroutine scheduling: they fail only if the mutual exclusion itself breaks.
 *
 * Hermetic exactly like [UserDataStorageWizardTest]: both files are redirected into a temp dir
 * by [UserDataStorage.resetForTesting] and pointed back at [BossDirectories.rootDir] in
 * tearDown, so the singleton is left where the app and other tests expect it.
 */
class UserDataStorageMergeTest {
    private lateinit var workDir: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private val user1 = UserInfo(id = "u-1", email = "one@example.com", createdAt = "2026-01-01T00:00:00Z")
    private val user2 = UserInfo(id = "u-2", email = "two@example.com", createdAt = "2026-02-02T00:00:00Z")

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("user-data-merge-test-").toFile()
        UserDataStorage.resetForTesting(workDir)
    }

    @AfterTest
    fun tearDown() {
        UserDataStorage.afterGenerationCaptureForTest = null
        // Point the singleton back at the user's directory before anything else uses it.
        UserDataStorage.resetForTesting(BossDirectories.rootDir)
        workDir.deleteRecursively()
    }

    private fun storedRecord(): UserDataStorage.StoredUserData =
        json.decodeFromString(
            UserDataStorage.StoredUserData.serializer(),
            UserDataStorage.storageFile.readText(),
        )

    private fun workDirFileNames(): List<String> =
        workDir
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    // --- (1) saveUserData merge contract -------------------------------------------

    @Test
    fun `a pending wizard marker is merged into the fresh record and consumed by the save`() =
        runBlocking {
            // Wizard completed before login: no record exists, so the flag lives in the marker.
            UserDataStorage.setPluginWizardCompleted(true)
            assertTrue(UserDataStorage.pendingWizardCompletedFile.exists(), "the marker must exist before the save")

            UserDataStorage.saveUserData(user1)

            val stored = storedRecord()
            assertEquals(user1.email, stored.email, "the fresh record must carry the saved identity")
            assertTrue(
                stored.pluginWizardCompleted,
                "the pending marker must be folded into the fresh record, not dropped by it",
            )
            assertFalse(
                UserDataStorage.pendingWizardCompletedFile.exists(),
                "a successful save must consume the pending marker",
            )
            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the merged flag must be visible to the wizard check",
            )
        }

    @Test
    fun `the stored wizard flag survives a later save that replaces the identity`() =
        runBlocking {
            UserDataStorage.saveUserData(user1)
            UserDataStorage.setPluginWizardCompleted(true) // in-record update; no marker involved

            // A session-restore style save with a brand-new identity and provider.
            UserDataStorage.saveUserData(user2, authenticatedVia = "passkey")

            val stored = storedRecord()
            assertEquals(user2.email, stored.email, "the later save must win for the identity")
            assertEquals("passkey", stored.authenticatedVia)
            assertTrue(
                stored.pluginWizardCompleted,
                "the flag stored in the record must be merged into the fresh one, not reset by it",
            )
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    @Test
    fun `a marker landing next to a stored false flag wins the merge and is consumed`() =
        runBlocking {
            UserDataStorage.saveUserData(user1) // a record exists, its flag still false

            // The corrupt-record fallback writes the marker even while a record exists, so this
            // state can land between two saves.
            UserDataStorage.pendingWizardCompletedFile.writeText("true")

            UserDataStorage.saveUserData(user1, authenticatedVia = "passkey")

            assertTrue(
                storedRecord().pluginWizardCompleted,
                "pending OR stored must fold the marker's true into the fresh record",
            )
            assertFalse(
                UserDataStorage.pendingWizardCompletedFile.exists(),
                "the save must consume the marker it folded in",
            )
        }

    @Test
    fun `a false marker does not erase a stored true flag and is still consumed`() =
        runBlocking {
            UserDataStorage.saveUserData(user1)
            UserDataStorage.setPluginWizardCompleted(true) // the record says completed
            UserDataStorage.pendingWizardCompletedFile.writeText("false") // a pre-login "not done" marker

            UserDataStorage.saveUserData(user1)

            assertTrue(
                storedRecord().pluginWizardCompleted,
                "the merge is an OR: a false marker must not revert a stored true flag",
            )
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    @Test
    fun `a corrupt stored record does not block the marker merge`() =
        runBlocking {
            UserDataStorage.pendingWizardCompletedFile.writeText("true")
            UserDataStorage.storageFile.writeText("{ this is not a record") // pre-atomic-write legacy state

            UserDataStorage.saveUserData(user1)

            val stored = storedRecord()
            assertEquals(user1.email, stored.email)
            assertTrue(
                stored.pluginWizardCompleted,
                "the marker must merge even when the record it lands beside is undecodable",
            )
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    // --- (2) clearUserData under the lock -------------------------------------------

    @Test
    fun `a fenced save neither recreates the record nor consumes the pending marker`() =
        runBlocking {
            UserDataStorage.saveUserData(user1)
            UserDataStorage.pendingWizardCompletedFile.writeText("true")

            var hookRan = false
            UserDataStorage.afterGenerationCaptureForTest = {
                UserDataStorage.afterGenerationCaptureForTest = null
                // Logout completes while the save is between its generation capture and its lock
                // acquisition: the save is entered, but has read and written nothing yet.
                UserDataStorage.clearUserData()
                hookRan = true
            }

            UserDataStorage.saveUserData(user2)

            assertTrue(hookRan, "the test must drive logout after the production capture point")
            assertFalse(
                UserDataStorage.storageFile.exists(),
                "a save entered before logout must not recreate the cleared record",
            )
            assertTrue(
                UserDataStorage.pendingWizardCompletedFile.exists(),
                "the fenced save performs no side effects: it must not consume the pending marker",
            )
            assertEquals("true", UserDataStorage.pendingWizardCompletedFile.readText().trim())

            // The next login saves with a fresh generation and still merges the marker.
            UserDataStorage.saveUserData(user2)
            val stored = storedRecord()
            assertEquals(user2.email, stored.email)
            assertTrue(
                stored.pluginWizardCompleted,
                "the marker that survived the fenced save must merge into the next save",
            )
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    @Test
    fun `logout racing the wizard update never leaves a resurrected record`() =
        runBlocking(Dispatchers.Default) {
            repeat(25) { round ->
                UserDataStorage.pendingWizardCompletedFile.delete()
                UserDataStorage.saveUserData(user1) // a record the wizard can update in place

                val start = CompletableDeferred<Unit>()
                val wizard =
                    async {
                        start.await()
                        UserDataStorage.setPluginWizardCompleted(true)
                    }
                val clear =
                    async {
                        start.await()
                        UserDataStorage.clearUserData()
                    }
                start.complete(Unit)
                awaitAll(wizard, clear)

                // Both legal serializations end with the record gone: wizard-then-clear writes
                // the flag and the clear deletes it; clear-then-wizard sends the flag to the
                // pending marker instead. A surviving record means the clear deleted the file
                // between the wizard's read and its write and the wizard's write recreated it.
                assertFalse(
                    UserDataStorage.storageFile.exists(),
                    "round $round: the record must be gone whichever writer got the lock first",
                )
            }
        }

    // --- (3) lost-update regression -------------------------------------------------

    @Test
    fun `saves racing the wizard completion never lose the completed flag`() =
        runBlocking(Dispatchers.Default) {
            repeat(20) { round ->
                // Blank slate each round so the marker path (record absent) and the in-record
                // path (record present) both get exercised across the rounds' interleavings.
                UserDataStorage.storageFile.delete()
                UserDataStorage.pendingWizardCompletedFile.delete()

                val start = CompletableDeferred<Unit>()
                val saveWriters =
                    listOf(
                        async {
                            start.await()
                            UserDataStorage.saveUserData(user1)
                        },
                        async {
                            start.await()
                            UserDataStorage.saveUserData(user2, authenticatedVia = "passkey")
                        },
                    )
                val wizardWriters =
                    (1..12).map {
                        async {
                            start.await()
                            UserDataStorage.setPluginWizardCompleted(true)
                        }
                    }
                start.complete(Unit)
                (saveWriters + wizardWriters).awaitAll()

                assertTrue(
                    UserDataStorage.storageFile.exists(),
                    "round $round: both saves completed and nothing deletes here, so a record must exist",
                )
                val stored = storedRecord()
                assertTrue(
                    stored.email == user1.email || stored.email == user2.email,
                    "round $round: the record must hold one of the saved identities, got ${stored.email}",
                )
                assertTrue(
                    stored.pluginWizardCompleted,
                    "round $round: the mutex must serialize the read-modify-writes; a racing save must " +
                        "never revert the wizard flag the way it did before the lock existed",
                )
                assertTrue(
                    UserDataStorage.isPluginWizardCompleted(),
                    "round $round: the wizard check must agree with the stored record",
                )
                assertFalse(
                    UserDataStorage.pendingWizardCompletedFile.exists(),
                    "round $round: any marker written before the first save must be merged and consumed",
                )
            }
        }

    // --- (4) torn-write protection on the save path ---------------------------------

    @Test
    fun `readers never observe a torn record while writers race`() =
        runBlocking(Dispatchers.Default) {
            // Seed the record so it exists for the whole test: nothing here deletes it, so
            // every wizard toggle takes the in-record path and no marker file appears.
            UserDataStorage.saveUserData(user1)

            val stop = AtomicBoolean(false)
            val torn = CopyOnWriteArrayList<String>()
            val reader =
                thread(isDaemon = true, name = "user-data-torn-read-detector") {
                    while (!stop.get()) {
                        val snapshot = UserDataStorage.storageFile
                        if (snapshot.exists()) {
                            try {
                                json.decodeFromString(
                                    UserDataStorage.StoredUserData.serializer(),
                                    snapshot.readText(),
                                )
                            } catch (e: SerializationException) {
                                torn.add("a reader saw a record it could not decode: " + e.message)
                            } catch (_: IOException) {
                                // A transient refusal to open the file while the atomic replace
                                // lands (e.g. a Windows sharing violation): no content was
                                // observed, so it is not a torn record - just read again.
                                continue
                            }
                        }
                    }
                }

            try {
                val start = CompletableDeferred<Unit>()
                val writers =
                    (0 until 24).map { i ->
                        async {
                            start.await()
                            if (i % 2 == 0) {
                                UserDataStorage.saveUserData(if (i % 4 == 0) user1 else user2)
                            } else {
                                UserDataStorage.setPluginWizardCompleted(i % 3 == 0)
                            }
                        }
                    }
                start.complete(Unit)
                writers.awaitAll()
            } finally {
                stop.set(true)
                reader.join(5_000L)
            }

            assertTrue(
                torn.isEmpty(),
                "the atomic temp+move must expose only whole records to readers: $torn",
            )
            val stored = storedRecord()
            assertTrue(
                stored.email == user1.email || stored.email == user2.email,
                "the settled record must be one of the complete written forms",
            )
            assertEquals(
                listOf("user_data.json"),
                workDirFileNames(),
                "no atomic-write temp file may outlive the race",
            )
        }

    @Test
    fun `a completed save leaves no atomic-write temp file behind`() =
        runBlocking {
            UserDataStorage.setPluginWizardCompleted(true)
            assertEquals(
                listOf("pending_wizard_completed"),
                workDirFileNames(),
                "the marker write must move its temp file into place, leaving only the marker",
            )

            UserDataStorage.saveUserData(user1)

            // The marker was merged and consumed, and the save's own temp file was moved into
            // place: the record is the only thing left in the directory.
            assertEquals(
                listOf("user_data.json"),
                workDirFileNames(),
                "the save must not leak its temp file or leave the consumed marker behind",
            )
            assertTrue(storedRecord().pluginWizardCompleted)
        }
}
