package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStorageCancellationTest {
    private val directory = createTempDirectory("plugin-storage-cancellation").toFile()
    private val file = File(directory, "storage.properties")
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private fun blockedStorage() =
        PluginStorageProviderImpl("test", file) { target, properties ->
            writePluginProperties(target, properties)
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `cancellation during an admitted write still publishes the committed value`() =
        runBlocking {
            val storage = blockedStorage()
            val write = launch(Dispatchers.Default) { storage.putString("key", "committed") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals("committed", PluginStorageProviderImpl("test", file).getString("key"))
                assertFalse(storage.contains("key"))
                write.cancel()
            } finally {
                release.countDown()
                write.join()
            }

            assertTrue(write.isCancelled)
            assertEquals("committed", storage.getString("key"))
            assertEquals("committed", PluginStorageProviderImpl("test", file).getString("key"))
        }

    @Test
    fun `cancellation before admission cannot modify cache or disk`() =
        runBlocking {
            val storage = blockedStorage()
            val first = launch(Dispatchers.Default) { storage.putString("first", "committed") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val waiting =
                    launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        storage.putString("cancelled", "must not persist")
                    }
                waiting.cancelAndJoin()
            } finally {
                release.countDown()
                first.join()
            }

            assertFalse(storage.contains("cancelled"))
            assertEquals(setOf("first"), PluginStorageProviderImpl("test", file).getAllKeys())
        }
}
