package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for #506: overlapping saves must not lose committed state,
 * failed commits must propagate without mutating state, cancelled callers must
 * not leave cache and disk inconsistent, and reloaded providers must read back
 * what earlier providers committed.
 */
class PluginStorageProviderImplTest {
    private lateinit var testDir: File

    @BeforeEach
    fun setup() {
        testDir = Files.createTempDirectory("plugin_storage_test").toFile()
    }

    @AfterEach
    fun teardown() {
        testDir.deleteRecursively()
    }

    private fun readDiskProperties(): Properties {
        val props = Properties()
        val storageFile = File(testDir, "storage.properties")
        if (storageFile.exists()) {
            storageFile.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun tempFilesLeftBehind(): List<File> =
        testDir.listFiles { file -> file.name.startsWith("storage.properties.tmp.") }?.toList().orEmpty()

    /**
     * Launches a collector that records change events, then yields once so
     * the subscriber is registered inside collect() before any commit can
     * emit. The yield is sufficient only because SharedFlow.collect
     * allocates its subscriber slot synchronously before its first
     * suspension, which holds while observeChanges() stays an unadorned
     * asSharedFlow(); an operator that suspends before subscribing (for
     * example flowOn, buffer, a flow { } re-wrap, or an onStart that
     * awaits) would silently void the barrier. Operators that do not
     * suspend before slot allocation (for example a plain map) are safe.
     */
    private suspend fun CoroutineScope.launchChangeCollector(
        provider: PluginStorageProviderImpl,
        events: MutableList<String>,
    ): Job {
        val collector =
            launch {
                provider.observeChanges().collect { events += it }
            }
        yield()
        return collector
    }

    @Test
    fun `concurrent puts do not lose updates`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            val distinctKeys = (1..16).map { "key$it" }
            val distinctJobs =
                distinctKeys.map { key ->
                    async(Dispatchers.Default) {
                        provider.putString(key, "value-$key")
                    }
                }
            val sharedJobs =
                (1..8).map { attempt ->
                    async(Dispatchers.Default) {
                        provider.putString("shared", "value-$attempt")
                    }
                }
            distinctJobs.awaitAll()
            sharedJobs.awaitAll()

            distinctKeys.forEach { key ->
                assertEquals(
                    "value-$key",
                    provider.getString(key, null),
                    "concurrent put for $key must be visible in the cache",
                )
                assertEquals(
                    "value-$key",
                    readDiskProperties().getProperty(key),
                    "concurrent put for $key must be committed to disk",
                )
            }
            val sharedValue = provider.getString("shared", null)
            assertTrue(sharedValue in (1..8).map { "value-$it" }, "shared key must hold one of the written values")
            assertEquals(
                sharedValue,
                readDiskProperties().getProperty("shared"),
                "shared key cache and disk must agree after concurrent writes",
            )
            assertTrue(tempFilesLeftBehind().isEmpty(), "no temporary files may be left behind")
        }

    @Test
    fun `remove and clear commit in order and tolerate no-ops`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            provider.putString("a", "1")
            provider.putString("b", "2")

            provider.remove("a")
            assertFalse(provider.contains("a"), "removed key must leave the cache")
            assertTrue(provider.contains("b"))
            val afterRemove = readDiskProperties()
            assertFalse(afterRemove.containsKey("a"), "removed key must not remain on disk")
            assertEquals("2", afterRemove.getProperty("b"))

            provider.remove("missing")
            provider.clear()
            assertTrue(provider.getAllKeys().isEmpty(), "cleared storage must have no cache entries")
            assertTrue(readDiskProperties().isEmpty, "cleared storage must have no keys on disk")

            provider.clear()
            provider.remove("missing")
        }

    @Test
    fun `typed and json setters share the serialized path`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("typed-plugin", testDir)
            provider.putInt("count", 42)
            provider.putLong("big", 1000000000L)
            provider.putBoolean("flag", true)
            provider.putFloat("ratio", 1.5f)
            provider.putJson("config", """{"x": 1}""")

            assertEquals(42, provider.getInt("count", 0))
            assertEquals(1000000000L, provider.getLong("big", 0L))
            assertEquals(true, provider.getBoolean("flag", false))
            assertEquals(1.5f, provider.getFloat("ratio", 0f))
            assertEquals("""{"x": 1}""", provider.getJson("config"))

            val disk = readDiskProperties()
            assertEquals("42", disk.getProperty("count"))
            assertEquals("1000000000", disk.getProperty("big"))
            assertEquals("true", disk.getProperty("flag"))
            assertEquals("1.5", disk.getProperty("ratio"))
            assertEquals("""{"x": 1}""", disk.getProperty("json:config"))
        }

    @Test
    fun `failed commit propagates and leaves state untouched`() =
        runBlocking {
            val blockedParent = File(testDir, "blocked")
            assertTrue(blockedParent.createNewFile(), "must be able to create the blocker file")
            val storageDir = File(blockedParent, "plugin-data")
            val provider = PluginStorageProviderImpl("blocked-plugin", storageDir)

            assertFailsWith<IOException> {
                provider.putString("k", "v")
            }
            assertFalse(provider.contains("k"), "cache must not gain the value of a failed commit")
            assertFalse(
                File(storageDir, "storage.properties").exists(),
                "no storage file may appear under the blocked path",
            )
        }

    @Test
    fun `cancellation before the call starts does not touch storage`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            val job =
                launch {
                    provider.putString("k", "v")
                }
            job.cancel()
            job.join()

            assertFalse(provider.contains("k"), "cancelled call must not mutate the cache")
            assertFalse(readDiskProperties().containsKey("k"), "cancelled call must not write to disk")
            assertTrue(tempFilesLeftBehind().isEmpty(), "cancelled call must not leave temp files")
        }

    @Test
    fun `an in-flight commit survives caller cancellation and stays consistent`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    provider.putString("k", "v")
                }
            // Premise: an uncontended Mutex.lock() acquires without
            // suspending, and withContext(Dispatchers.IO) dispatches to
            // another thread, so the body reaches the NonCancellable commit
            // before cancel() below. If either premise changes, this test
            // dies on the deadline check, which points at that premise.
            job.cancel()

            val storageFile = File(testDir, "storage.properties")
            val deadline = System.currentTimeMillis() + 15000
            while (provider.getString("k", null) != "v" || !storageFile.exists()) {
                check(System.currentTimeMillis() < deadline) { "commit must complete after caller cancellation" }
                delay(25)
            }
            assertEquals(
                "v",
                readDiskProperties().getProperty("k"),
                "cache and disk must agree after a cancelled caller",
            )
            assertTrue(tempFilesLeftBehind().isEmpty(), "commit must not leave temp files behind")
        }

    @Test
    fun `reload loads encoded values and json keys remain readable`() =
        runBlocking {
            val seeded = Properties()
            seeded["unicode"] = "héllo wörld ✓"
            seeded["spaced key"] = "a=b: c"
            seeded["json:state"] = """{"count": 2}"""
            File(testDir, "storage.properties").outputStream().use { seeded.store(it, "seed") }

            val provider = PluginStorageProviderImpl("reload-plugin", testDir)
            assertEquals("héllo wörld ✓", provider.getString("unicode", null))
            assertEquals("a=b: c", provider.getString("spaced key", null))
            assertEquals("""{"count": 2}""", provider.getJson("state"))
            assertTrue(
                provider.getAllKeys().containsAll(setOf("unicode", "spaced key", "json:state")),
                "reloaded provider must expose every committed key",
            )
        }

    @Test
    fun `a second provider instance reloads committed values`() =
        runBlocking {
            val first = PluginStorageProviderImpl("reload-plugin", testDir)
            first.putString("persisted", "yes")
            first.putJson("config", """{"a":1}""")

            val second = PluginStorageProviderImpl("reload-plugin", testDir)
            assertEquals("yes", second.getString("persisted", null))
            assertEquals("""{"a":1}""", second.getJson("config"))
        }

    @Test
    fun `concurrent createStorage calls share one provider instance`() =
        runBlocking {
            val factory = createPluginStorageFactory()
            val jobs =
                (1..16).map {
                    async(Dispatchers.Default) {
                        factory.createStorage("shared-instance-plugin")
                    }
                }
            val instances = jobs.awaitAll()
            assertEquals(1, instances.distinct().size, "one plugin id must map to one provider instance")
        }

    @Test
    fun `a successful put publishes its key on the changes flow`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val collector = launchChangeCollector(provider, events)
            try {
                provider.putString("k", "v")
                val deadline = System.currentTimeMillis() + 10000
                while (events.size < 1) {
                    check(System.currentTimeMillis() < deadline) { "change event must be published after commit" }
                    delay(10)
                }
                assertEquals(listOf("k"), events, "exactly one event for the committed key")
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `a failed commit publishes no change event`() =
        runBlocking {
            val blockedParent = File(testDir, "blocked")
            assertTrue(blockedParent.createNewFile(), "must be able to create the blocker file")
            val provider = PluginStorageProviderImpl("blocked-plugin", File(blockedParent, "plugin-data"))
            val events = Collections.synchronizedList(mutableListOf<String>())
            val collector = launchChangeCollector(provider, events)
            try {
                assertFailsWith<IOException> {
                    provider.putString("k", "v")
                }
                delay(200)
                assertTrue(events.isEmpty(), "a failed commit must not publish a change event")
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `cancellation while queued behind the commit lock does not touch storage`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val collector = launchChangeCollector(provider, events)
            try {
                // The 4 MB value is load-bearing, not belt-and-braces: it
                // keeps A holding transactionMutex past B's lock() attempt.
                // If A ever won that race, B would commit inline and this
                // test would fail on the contains("b") assertion, which
                // reads as a production bug - the failure would be
                // misattributed to the code under test.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    provider.putString("a", "A".repeat(4 * 1024 * 1024))
                }
                val jobB =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        provider.putString("b", "B")
                    }
                // B is suspended in transactionMutex.lock() behind A.
                jobB.cancel()
                jobB.join()
                assertFalse(provider.contains("b"), "queued-then-cancelled caller must not mutate the cache")

                val deadline = System.currentTimeMillis() + 30000
                while (provider.getString("a", null) == null) {
                    check(System.currentTimeMillis() < deadline) { "A's commit must complete" }
                    delay(25)
                }
                val disk = readDiskProperties()
                assertEquals(
                    "A".repeat(4 * 1024 * 1024),
                    disk.getProperty("a"),
                    "A's commit must be on disk",
                )
                assertFalse(disk.containsKey("b"), "B's cancelled write must not reach disk")
                assertTrue(events.none { it == "b" }, "no event may be published for a cancelled queued caller")
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `a new provider sweeps orphaned temp files`() {
        File(testDir, "storage.properties.tmp.orphan").apply {
            createNewFile()
            writeText("stale")
        }
        val provider = PluginStorageProviderImpl("sweep-plugin", testDir)
        assertEquals("sweep-plugin", provider.getPluginId())
        val orphans = testDir.listFiles { file -> file.name.startsWith("storage.properties.tmp.") }
        assertTrue(orphans.isNullOrEmpty(), "orphaned temp files must be swept on provider construction")
    }
}
