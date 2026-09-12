package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStorageProviderImplTest {
    private lateinit var testDir: File

    @BeforeEach
    fun setup() {
        testDir = Files.createTempDirectory("plugin_storage_test").toFile()
    }

    @AfterEach
    fun teardown() {
        testDir.setWritable(true)
        testDir.deleteRecursively()
    }

    @Test
    fun `Test 4 and 5 - cancellation integrity`() =
        runBlocking {
            val provider = PluginStorageProviderImpl("test-plugin", testDir)
            provider.putString("key1", "val1")

            val job =
                launch(Dispatchers.Default) {
                    provider.putString("key2", "val2")
                }

            // Let it start, but hopefully cancel before IO or during IO
            delay(1)
            job.cancelAndJoin()

            val inCache = provider.contains("key2")
            val props = Properties()
            if (File(testDir, "storage.properties").exists()) {
                File(testDir, "storage.properties").inputStream().use { props.load(it) }
            }
            val inDisk = props.containsKey("key2")

            println("inCache: $inCache, inDisk: $inDisk")
            assertEquals(inCache, inDisk, "Cache and disk must remain consistent after cancellation")
        }
}
