package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.FileEventBus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSystemDataProviderLifecycleTest {
    @Test
    fun `dispose stops later file-open events`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val provider = FileSystemDataProviderImpl(dispatcher)
            val beforeWindow = "filesystem-provider-before-dispose"
            val before =
                async {
                    FileEventBus.fileOpenEvents.first { it.sourceWindowId == beforeWindow }
                }
            yield()

            provider.openFile("/tmp/before.txt", beforeWindow)
            advanceUntilIdle()

            assertEquals("/tmp/before.txt", before.await().filePath)

            provider.dispose()
            val afterWindow = "filesystem-provider-after-dispose"
            val after =
                async {
                    withTimeoutOrNull(1) {
                        FileEventBus.fileOpenEvents.first { it.sourceWindowId == afterWindow }
                    }
                }
            yield()

            provider.openFile("/tmp/after.txt", afterWindow)
            advanceUntilIdle()

            assertNull(after.await())
        }
}
