package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStorageConcurrencyTest {
    private val directory = createTempDirectory("plugin-storage-concurrency").toFile()
    private val file = File(directory, "storage.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `an older save cannot overwrite a successful later put`() =
        checkOverlappingMutation(mapOf("seed" to "value", "first" to "value", "second" to "value")) {
            it.putString("second", "value")
        }

    @Test
    fun `an older save cannot resurrect a removed key`() =
        checkOverlappingMutation(mapOf("first" to "value")) {
            it.remove("seed")
        }

    @Test
    fun `an older save cannot resurrect cleared storage`() =
        checkOverlappingMutation(emptyMap()) {
            it.clear()
        }

    private fun checkOverlappingMutation(
        expected: Map<String, String>,
        mutate: suspend (PluginStorageProviderImpl) -> Unit,
    ) = runBlocking {
        PluginStorageProviderImpl("test", file).putString("seed", "value")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val writes = AtomicInteger()
        val storage =
            PluginStorageProviderImpl("test", file) { target, properties ->
                if (writes.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                }
                writePluginProperties(target, properties)
            }
        val first = async(Dispatchers.Default) { storage.putString("first", "value") }
        try {
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
            // Matching the provider's IO context and starting undispatched enters the mutation
            // immediately, up to its first suspension: the mutex on the fixed implementation.
            val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { mutate(storage) }
            try {
                assertFalse(second.isCompleted, "the later mutation must wait for the first commit")
                assertEquals(1, writes.get(), "the second writer must not enter concurrently")
            } finally {
                releaseFirst.countDown()
                second.await()
            }
        } finally {
            releaseFirst.countDown()
            first.await()
        }

        val reloaded = PluginStorageProviderImpl("test", file)
        assertEquals(expected.keys, reloaded.getAllKeys())
        expected.forEach { (key, value) ->
            assertEquals(value, reloaded.getString(key))
            assertEquals(value, storage.getString(key))
        }
        assertEquals(expected.keys, storage.getAllKeys())
    }
}
