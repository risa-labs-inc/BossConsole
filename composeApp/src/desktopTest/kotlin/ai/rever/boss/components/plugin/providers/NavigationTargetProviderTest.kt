package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.NavigationTargetBus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavigationTargetProviderTest {
    @BeforeTest
    fun clearNavigationState() {
        NavigationTargetProviderImpl.clearCache()
        NavigationTargetBus.ipcBridge = null
    }

    @AfterTest
    fun resetNavigationState() {
        NavigationTargetProviderImpl.clearCache()
        NavigationTargetBus.ipcBridge = null
    }

    @Test
    fun `clearing a consumed target prevents replay to a later editor`() =
        runBlocking {
            val targetPath = "/workspace/src/Consumed.kt"

            NavigationTargetBus.navigateTo(
                filePath = targetPath,
                line = 41,
                column = 7,
                sourceWindowId = "window-a",
            )

            val consumed =
                withTimeout(TIMEOUT_MS) {
                    NavigationTargetProviderImpl.targets.first { it.filePath == targetPath }
                }
            assertEquals(41, consumed.line)

            NavigationTargetProviderImpl.clearCache()

            assertNull(
                withTimeoutOrNull(NO_EVENT_TIMEOUT_MS) {
                    NavigationTargetProviderImpl.targets.first()
                },
                "clearCache must remove the target exposed to plugins, not only the host copy",
            )
        }

    @Test
    fun `provider and bus expose one replay owner`() {
        assertSame(
            NavigationTargetBus.targets,
            NavigationTargetProviderImpl.targets,
            "a relay flow can retain a stale target after the bus cache is cleared",
        )
    }

    @Test
    fun `unconsumed target replays to an editor that subscribes after navigation`() =
        runBlocking {
            NavigationTargetBus.navigateTo(
                filePath = "/workspace/src/LateEditor.kt",
                line = 12,
                column = 3,
                sourceWindowId = "window-late",
            )

            val replayed = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }

            assertEquals("/workspace/src/LateEditor.kt", replayed.filePath)
            assertEquals(12, replayed.line)
            assertEquals(3, replayed.column)
            assertEquals("window-late", replayed.sourceWindowId)
        }

    @Test
    fun `new target remains deliverable after an earlier target is cleared`() =
        runBlocking {
            NavigationTargetBus.navigateTo("/workspace/Old.kt", 2, 1, "window-a")
            assertEquals("/workspace/Old.kt", NavigationTargetProviderImpl.targets.first().filePath)
            NavigationTargetProviderImpl.clearCache()

            NavigationTargetBus.navigateTo("/workspace/New.kt", 9, 4, "window-b")

            val next = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }
            assertEquals("/workspace/New.kt", next.filePath)
            assertEquals("window-b", next.sourceWindowId)
        }

    @Test
    fun `active editor receives navigation bursts in emission order`() =
        runBlocking {
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    NavigationTargetProviderImpl.targets
                        .take(3)
                        .toList()
                }

            NavigationTargetBus.navigateTo("/workspace/First.kt", 1, 1, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Second.kt", 2, 1, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Third.kt", 3, 1, "window-a")

            assertContentEquals(
                listOf("/workspace/First.kt", "/workspace/Second.kt", "/workspace/Third.kt"),
                withTimeout(TIMEOUT_MS) { received.await() }.map { it.filePath },
            )
        }

    @Test
    fun `invalid target lines are not replayed`() =
        runBlocking {
            NavigationTargetBus.navigateTo("/workspace/Zero.kt", 0, 8, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Negative.kt", -4, 8, "window-a")

            assertNull(
                withTimeoutOrNull(NO_EVENT_TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() },
                "non-positive lines must not leave a navigation target for a future editor",
            )
        }

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val NO_EVENT_TIMEOUT_MS = 200L
    }
}
