package ai.rever.boss.components.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExclusivePluginUpdatesTest {
    @Test
    fun `rejected operation cannot delete the owners downloaded jar`(
        @TempDir dir: File,
    ) {
        runBlocking {
            val updates = ExclusivePluginUpdates()
            val jar = File(dir, "plugin-2.0.0.jar")
            val downloaded = CompletableDeferred<Unit>()
            val install = CompletableDeferred<Unit>()
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    updates.run("plugin") {
                        jar.writeText("complete artifact")
                        downloaded.complete(Unit)
                        install.await()
                        Result.success(jar.readText())
                    }
                }

            downloaded.await()
            var secondEntered = false
            try {
                val second =
                    updates.run<String>("plugin") {
                        secondEntered = true
                        // A second download fails before swapping. Its caller discards the shared path.
                        jar.delete()
                        Result.failure(IOException("download failed"))
                    }

                assertTrue(second.isFailure)
                val busy =
                    assertIs<PluginUpdateAlreadyInProgressException>(
                        second.exceptionOrNull(),
                    )
                assertEquals("plugin", busy.pluginId)
                assertTrue(jar.isFile, "the failed second update deleted the first update's artifact")
                assertFalse(secondEntered, "a rejected update must not reach download or cleanup")
            } finally {
                install.complete(Unit)
            }

            assertEquals("complete artifact", first.await().getOrThrow())
        }
    }

    @Test
    fun `another plugin can update while one plugin is busy`() =
        runBlocking {
            val updates = ExclusivePluginUpdates()
            val finish = CompletableDeferred<Unit>()
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    updates.run("first") {
                        finish.await()
                        Result.success(Unit)
                    }
                }

            try {
                assertTrue(updates.run("second") { Result.success(Unit) }.isSuccess)
            } finally {
                finish.complete(Unit)
            }

            first.await().getOrThrow()
        }

    @Test
    fun `cancellation releases admission only after cleanup finishes`() =
        runBlocking {
            val updates = ExclusivePluginUpdates()
            val cleanupRejection = CompletableDeferred<Throwable?>()
            var enteredDuringCleanup = false

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    updates.run<Unit>("plugin") {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                val duplicate =
                                    updates.run<Unit>("plugin") {
                                        enteredDuringCleanup = true
                                        Result.success(Unit)
                                    }
                                cleanupRejection.complete(duplicate.exceptionOrNull())
                            }
                        }
                    }
                }

            first.cancelAndJoin()

            assertFalse(enteredDuringCleanup)
            val busy =
                assertIs<PluginUpdateAlreadyInProgressException>(
                    cleanupRejection.await(),
                )
            assertEquals("plugin", busy.pluginId)
            assertTrue(updates.run("plugin") { Result.success(Unit) }.isSuccess)
        }

    @Test
    fun `failure retains admission until cleanup finishes`() =
        runBlocking {
            val updates = ExclusivePluginUpdates()
            val cleanupStarted = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    updates.run<Unit>("plugin") {
                        try {
                            Result.failure(IOException("download failed"))
                        } finally {
                            cleanupStarted.complete(Unit)
                            finishCleanup.await()
                        }
                    }
                }

            cleanupStarted.await()

            var duplicateEntered = false
            val duplicate =
                updates.run<Unit>("plugin") {
                    duplicateEntered = true
                    Result.success(Unit)
                }

            assertFalse(duplicateEntered)
            val busy =
                assertIs<PluginUpdateAlreadyInProgressException>(
                    duplicate.exceptionOrNull(),
                )
            assertEquals("plugin", busy.pluginId)

            finishCleanup.complete(Unit)
            assertTrue(first.await().isFailure)
            assertTrue(updates.run("plugin") { Result.success(Unit) }.isSuccess)
        }

    @Test
    fun `success failure and exception each permit a later retry`() =
        runBlocking {
            val updates = ExclusivePluginUpdates()

            assertTrue(updates.run("plugin") { Result.success(Unit) }.isSuccess)
            assertTrue(
                updates
                    .run<Unit>("plugin") {
                        Result.failure(IOException("download failed"))
                    }.isFailure,
            )

            val thrown =
                runCatching {
                    updates.run<Unit>("plugin") {
                        throw IOException("callback failed")
                    }
                }

            assertTrue(thrown.exceptionOrNull() is IOException)
            assertTrue(updates.run("plugin") { Result.success(Unit) }.isSuccess)
        }
}
