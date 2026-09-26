package ai.rever.boss.components.plugin.providers

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
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
                        // Expected.
                    }
                }

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
            val unicodeValue = "caf\u00E9 \u2014 \u0928\u092E\u0938\u094D\u0924\u0947"

            val properties = java.util.Properties().apply {
                setProperty("ascii", "hello")
                setProperty("unicode", unicodeValue)
                setProperty(
                    "json:data",
                    """{"message":"hello"}""",
                )
            }

            file.outputStream().use { output ->
                properties.store(
                    output,
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
                unicodeValue,
                provider.getString("unicode"),
            )
            assertEquals(
                """{"message":"hello"}""",
                provider.getJson("data"),
            )

            provider.putString(
                "unicode-after-write",
                unicodeValue,
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
                unicodeValue,
                reloaded.getString("unicode"),
            )
            assertEquals(
                """{"message":"hello"}""",
                reloaded.getJson("data"),
            )
            assertEquals(
                unicodeValue,
                reloaded.getString("unicode-after-write"),
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
    fun readerCannotObservePartiallyPublishedCache() = runBlocking {
        val tempDir = Files.createTempDirectory("plugin-storage-test").toFile()

        try {
            val existingKeyCount = 256
            val storageFile = File(
                tempDir,
                "storage.properties",
            )

            val properties = java.util.Properties()

            for (index in 0 until existingKeyCount) {
                properties.setProperty(
                    "existing-$index",
                    "value-$index",
                )
            }

            storageFile.outputStream().use { output ->
                properties.store(
                    output,
                    "Plugin storage test",
                )
            }

            val provider = provider(tempDir)

            val publishReached = CountDownLatch(1)
            val releasePublish = CountDownLatch(1)

            provider.beforeCachePublishHookForTest = {
                publishReached.countDown()

                check(
                    releasePublish.await(
                        30,
                        TimeUnit.SECONDS,
                    )
                ) {
                    "Timed out waiting to release cache publication"
                }
            }

            try {
                val commitJob = launch(Dispatchers.Default) {
                    provider.putString(
                        "new-key",
                        "new-value",
                    )
                }

                assertTrue(
                    publishReached.await(
                        30,
                        TimeUnit.SECONDS,
                    ),
                    "Commit did not reach the pre-publication point",
                )

                val visibleKeys = provider.getAllKeys()

                assertEquals(
                    existingKeyCount,
                    visibleKeys.size,
                )

                assertFalse(
                    visibleKeys.contains("new-key"),
                )

                for (index in 0 until existingKeyCount) {
                    assertTrue(
                        visibleKeys.contains("existing-$index"),
                        "Reader observed an incomplete cache snapshot",
                    )
                }

                releasePublish.countDown()
                commitJob.join()
            } finally {
                releasePublish.countDown()
                provider.beforeCachePublishHookForTest = null
            }

            assertEquals(
                "new-value",
                provider.getString("new-key"),
            )

            assertEquals(
                existingKeyCount + 1,
                provider.getAllKeys().size,
            )

            val reloaded = provider(tempDir)

            assertEquals(
                "new-value",
                reloaded.getString("new-key"),
            )

            assertEquals(
                existingKeyCount + 1,
                reloaded.getAllKeys().size,
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
