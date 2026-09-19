package ai.rever.boss.components.plugin.providers

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginStorageFactoryProviderTest {

    @Test
    fun concurrentWritesPreserveAllCommittedKeys() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val provider = provider(tempDir)

            coroutineScope {
                (0 until 20)
                    .map { index ->
                        async {
                            provider.putString(
                                "key-$index",
                                "value-$index",
                            )
                        }
                    }
                    .awaitAll()
            }

            for (index in 0 until 20) {
                assertEquals(
                    "value-$index",
                    provider.getString("key-$index"),
                )
            }

            val reloaded = provider(tempDir)

            for (index in 0 until 20) {
                assertEquals(
                    "value-$index",
                    reloaded.getString("key-$index"),
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun removeDoesNotLoseConcurrentCommittedUpdates() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val provider = provider(tempDir)

            provider.putString("keep", "initial")
            provider.putString("remove", "delete-me")

            coroutineScope {
                val removeJob = launch {
                    provider.remove("remove")
                }

                val updateJob = launch {
                    provider.putString("keep", "updated")
                }

                removeJob.join()
                updateJob.join()
            }

            assertEquals(
                "updated",
                provider.getString("keep"),
            )
            assertFalse(provider.contains("remove"))

            val reloaded = provider(tempDir)

            assertEquals(
                "updated",
                reloaded.getString("keep"),
            )
            assertFalse(reloaded.contains("remove"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun clearIsCommittedBeforeLaterPut() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val provider = provider(tempDir)

            provider.putString("old-1", "one")
            provider.putString("old-2", "two")

            provider.clear()
            provider.putString("new", "value")

            assertFalse(provider.contains("old-1"))
            assertFalse(provider.contains("old-2"))
            assertEquals(
                "value",
                provider.getString("new"),
            )

            val reloaded = provider(tempDir)

            assertFalse(reloaded.contains("old-1"))
            assertFalse(reloaded.contains("old-2"))
            assertEquals(
                "value",
                reloaded.getString("new"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failedPersistenceDoesNotPublishCacheChange() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val validFile = File(tempDir, "storage.properties")

            val provider = PluginStorageProviderImpl(
                pluginId = "failed-write-test",
                storageFileOverride = validFile,
            )

            provider.putString("existing", "committed")

            // Make the target path a directory so the next atomic replacement fails.
            assertTrue(validFile.delete())
            assertTrue(validFile.mkdir())

            var failed = false

            try {
                provider.putString(
                    "new",
                    "must-not-commit",
                )
            } catch (_: Exception) {
                failed = true
            }

            assertTrue(failed)

            // Failed persistence must not publish the new value.
            assertEquals(
                "committed",
                provider.getString("existing"),
            )
            assertFalse(provider.contains("new"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cancellationWhileWaitingForCommitDoesNotMutateStorage() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val provider = provider(tempDir)

            provider.putString(
                "existing",
                "committed",
            )

            // Hold the provider transaction lock so the next mutation
            // is guaranteed to wait before it can mutate storage.
            val lock = provider.commitMutexForTest()

            lock.lock()

            try {
                val cancelled = launch {
                    try {
                        provider.putString(
                            "cancelled",
                            "must-not-commit",
                        )
                    } catch (_: CancellationException) {
                        // Expected: cancellation while waiting for the mutex.
                    }
                }

                // Give the child coroutine a chance to reach the mutex.
                yield()

                cancelled.cancel()
                cancelled.join()
            } finally {
                lock.unlock()
            }

            assertEquals(
                "committed",
                provider.getString("existing"),
            )
            assertFalse(provider.contains("cancelled"))

            val reloaded = provider(tempDir)

            assertEquals(
                "committed",
                reloaded.getString("existing"),
            )
            assertFalse(reloaded.contains("cancelled"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun propertiesEncodingRemainsCompatible() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()
        val file = File(tempDir, "storage.properties")

        try {
            val properties = java.util.Properties().apply {
                setProperty("ascii", "hello")
                setProperty("unicode", "?????? ???")
                setProperty(
                    "json:data",
                    """{"message":"hello"}""",
                )
            }

            file.outputStream().use {
                properties.store(
                    it,
                    "legacy plugin storage",
                )
            }

            val provider = PluginStorageProviderImpl(
                pluginId = "encoding-test",
                storageFileOverride = file,
            )

            assertEquals(
                "hello",
                provider.getString("ascii"),
            )
            assertEquals(
                "?????? ???",
                provider.getString("unicode"),
            )
            assertEquals(
                """{"message":"hello"}""",
                provider.getJson("data"),
            )

            provider.putString(
                "after-reload",
                "works",
            )

            val reloaded = PluginStorageProviderImpl(
                pluginId = "encoding-test",
                storageFileOverride = file,
            )

            assertEquals(
                "hello",
                reloaded.getString("ascii"),
            )
            assertEquals(
                "?????? ???",
                reloaded.getString("unicode"),
            )
            assertEquals(
                """{"message":"hello"}""",
                reloaded.getJson("data"),
            )
            assertEquals(
                "works",
                reloaded.getString("after-reload"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun atomicCommitLeavesNoTemporaryFiles() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val provider = provider(tempDir)

            provider.putString(
                "key",
                "value",
            )

            val tempFiles = tempDir
                .listFiles()
                ?.filter { it.name.endsWith(".tmp") }
                .orEmpty()

            assertTrue(
                tempFiles.isEmpty(),
                "Atomic commit should clean up sibling temporary files",
            )

            assertEquals(
                "value",
                provider.getString("key"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun provider(tempDir: File): PluginStorageProviderImpl =
        PluginStorageProviderImpl(
            pluginId = "test-plugin",
            storageFileOverride = File(
                tempDir,
                "storage.properties",
            ),
        )
}
