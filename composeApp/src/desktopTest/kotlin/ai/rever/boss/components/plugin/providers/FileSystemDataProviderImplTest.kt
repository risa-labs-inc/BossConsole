package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemDataProviderImplTest {
    private val tempDir =
        File(
            System.getProperty("user.home"),
            "boss-fs-provider-test-${System.currentTimeMillis()}",
        )

    @BeforeTest
    fun setUp() {
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testProviderLifecycleAndPathConfinement() =
        runTest {
            val provider = FileSystemDataProviderImpl()
            assertTrue(provider.supportsHiddenEntries)
            assertEquals(System.getProperty("user.home"), provider.getHomeDirectory())

            val subDir = File(tempDir, "subdir")
            subDir.mkdirs()

            // Create file
            val createResult = provider.createFile(subDir.absolutePath, "test.txt")
            assertTrue(createResult.isSuccess, "Should create file inside directory")
            val filePath = createResult.getOrThrow()

            // Write & Read
            val writeResult = provider.writeFile(filePath, "hello boss")
            assertTrue(writeResult.isSuccess)
            val readResult = provider.readFile(filePath)
            assertTrue(readResult.isSuccess)
            assertEquals("hello boss", readResult.getOrThrow())

            // Rename
            val renameResult = provider.rename(filePath, "renamed.txt")
            assertTrue(renameResult.isSuccess)

            // Dispose provider
            provider.dispose()
        }
}
