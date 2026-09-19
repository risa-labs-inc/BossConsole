package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ordering guard on [ResolvedHostsStore]: writes are serialized by a lock, but their order
 * against snapshot recency is not, so a stale (smaller) snapshot must not overwrite a newer one
 * that already landed. [ResolvedHostsStore.writeGuarded] is exercised directly so the ordering is
 * deterministic instead of racing coroutines.
 */
class ResolvedHostsStoreTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var storeFile: File
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        storeFile = File(tempDir, "browser-resolved-hosts.json")
        ResolvedHostsStore.storeFile = storeFile
        ResolvedHostsStore.clear()
    }

    @AfterTest
    fun tearDown() {
        ResolvedHostsStore.clear()
    }

    private fun onDisk(): List<String> = json.decodeFromString<List<String>>(storeFile.readText())

    @Test
    fun `a stale snapshot does not overwrite a newer one`() =
        runBlocking {
            // The newer (seq 2) snapshot lands first, then the older (seq 1) one is applied late -
            // exactly the out-of-order dispatch the guard exists for.
            ResolvedHostsStore.writeGuarded(storeFile, listOf("a", "b"), seq = 2)
            ResolvedHostsStore.writeGuarded(storeFile, listOf("a"), seq = 1)

            assertEquals(listOf("a", "b"), onDisk(), "the just-recorded host must survive the stale write")
        }

    @Test
    fun `a newer snapshot overwrites an older one`() =
        runBlocking {
            ResolvedHostsStore.writeGuarded(storeFile, listOf("a"), seq = 1)
            ResolvedHostsStore.writeGuarded(storeFile, listOf("a", "b"), seq = 2)

            assertEquals(listOf("a", "b"), onDisk())
        }
}
