package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [FileSystemDataProviderImpl.openFile] launches into a scope the window owns: it delivers while
 * the provider is alive and delivers nothing once [FileSystemDataProviderImpl.dispose] has run.
 */
class FileSystemDataProviderDisposeTest {
    private val tempDir = createTempDirectory("fs-provider-dispose").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private suspend fun observe(
        provider: FileSystemDataProviderImpl,
        path: String,
    ): String? =
        coroutineScope {
            val seen =
                async(Dispatchers.Default) {
                    withTimeoutOrNull(2_000) {
                        FileEventBus.fileOpenEvents.first { it.filePath == path }.filePath
                    }
                }
            delay(200) // let the subscription start before the emit (replay = 0)
            provider.openFile(path, windowId = "dispose-test")
            seen.await()
        }

    @Test
    fun `openFile delivers while alive and nothing after dispose`() =
        runBlocking {
            val alive = File(tempDir, "alive.txt").apply { writeText("x") }.absolutePath
            val gone = File(tempDir, "gone.txt").apply { writeText("x") }.absolutePath
            val provider = FileSystemDataProviderImpl(dispatcher = Dispatchers.Unconfined)

            assertEquals(alive, observe(provider, alive))

            provider.dispose()

            assertNull(observe(provider, gone), "a disposed provider must not launch anything")
        }
}
