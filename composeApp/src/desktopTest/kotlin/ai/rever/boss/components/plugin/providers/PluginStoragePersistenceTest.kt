package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginStoragePersistenceTest {
    private val directory = createTempDirectory("plugin-storage-test").toFile()
    private val file = File(directory, "storage.properties")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `failed writes leave the committed value unchanged and report failure`() =
        runBlocking {
            PluginStorageProviderImpl("test", file).putString("key", "committed")
            val before = file.readBytes()
            val storage = PluginStorageProviderImpl("test", file) { _, _ -> throw IOException("disk unavailable") }

            assertFailsWith<IOException> { storage.putString("key", "uncommitted") }

            assertEquals("committed", storage.getString("key"))
            assertTrue(before.contentEquals(file.readBytes()))
        }

    @Test
    fun `readers do not see a mutation before its disk commit`() =
        runBlocking {
            PluginStorageProviderImpl("test", file).putString("key", "committed")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val storage =
                PluginStorageProviderImpl("test", file) { target, properties ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    writePluginProperties(target, properties)
                }
            val write = async(Dispatchers.Default) { storage.putString("key", "next") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals("committed", storage.getString("key"))
                assertEquals("committed", PluginStorageProviderImpl("test", file).getString("key"))
            } finally {
                release.countDown()
                write.await()
            }
            assertEquals("next", storage.getString("key"))
            assertEquals("next", PluginStorageProviderImpl("test", file).getString("key"))
        }

    @Test
    fun `serialization failure preserves the existing file and removes temporary files`() {
        val original = "existing=value\n".toByteArray()
        file.writeBytes(original)
        val invalid = Properties().apply { put("not-a-string", 42) }

        assertFailsWith<ClassCastException> { writePluginProperties(file, invalid) }

        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(listOf(file.name), directory.listFiles()!!.map { it.name })
    }

    @Test
    fun `overlapping file writes use distinct temporary files and clean up both outcomes`() =
        runBlocking {
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)

            fun properties(fail: Boolean) =
                object : Properties() {
                    override fun store(
                        out: OutputStream,
                        comments: String?,
                    ) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        super.store(out, comments)
                        if (fail) throw IOException("interrupted serialization")
                    }
                }.apply { setProperty("key", "committed") }

            val failing =
                async(Dispatchers.IO) {
                    assertFailsWith<IOException> { writePluginProperties(file, properties(true)) }
                }
            val successful = async(Dispatchers.IO) { writePluginProperties(file, properties(false)) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val temporaryFiles = directory.listFiles()!!.toList()
                assertEquals(2, temporaryFiles.size)
                assertEquals(2, temporaryFiles.map { it.name }.toSet().size)
                assertTrue(temporaryFiles.all { it.name.endsWith(".tmp") })
            } finally {
                release.countDown()
                failing.await()
                successful.await()
            }
            assertEquals(listOf(file.name), directory.listFiles()!!.map { it.name })
            assertEquals("committed", PluginStorageProviderImpl("test", file).getString("key"))
        }

    @Test
    fun `typed and escaped values survive reload in the existing properties format`() =
        runBlocking {
            val storage = PluginStorageProviderImpl("test", file)
            val text = "line one\nUnicode: \u00e9\u6f22\ud83d\ude00\\=:\t"
            storage.putString("key with = and :", text)
            storage.putInt("int", 42)
            storage.putLong("long", Long.MAX_VALUE)
            storage.putBoolean("boolean", true)
            storage.putFloat("float", 1.25f)
            storage.putJson("json", "{\"value\":\"\u6f22\"}")

            val reloaded = PluginStorageProviderImpl("test", file)
            assertEquals(text, reloaded.getString("key with = and :"))
            assertEquals(42, reloaded.getInt("int"))
            assertEquals(Long.MAX_VALUE, reloaded.getLong("long"))
            assertTrue(reloaded.getBoolean("boolean"))
            assertEquals(1.25f, reloaded.getFloat("float"))
            assertEquals("{\"value\":\"\u6f22\"}", reloaded.getJson("json"))
        }

    @Test
    fun `failed mutations publish no changes and can be retried`() =
        runBlocking {
            PluginStorageProviderImpl("test", file).putString("key", "committed")
            val fail = AtomicBoolean(true)
            val storage =
                PluginStorageProviderImpl("test", file) { target, properties ->
                    if (fail.get()) throw IOException("disk unavailable")
                    writePluginProperties(target, properties)
                }
            val changes = mutableListOf<String>()
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    storage.observeChanges().collect { changes.add(it) }
                }
            try {
                assertFailsWith<IOException> { storage.putString("key", "next") }
                assertFailsWith<IOException> { storage.remove("key") }
                assertFailsWith<IOException> { storage.clear() }
                yield()
                assertTrue(changes.isEmpty())
                assertEquals("committed", storage.getString("key"))
                assertEquals("committed", PluginStorageProviderImpl("test", file).getString("key"))

                fail.set(false)
                storage.putString("key", "next")
                yield()
                assertEquals(listOf("key"), changes)
                assertEquals("next", PluginStorageProviderImpl("test", file).getString("key"))
            } finally {
                observer.cancelAndJoin()
            }
        }
}
