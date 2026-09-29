package ai.rever.boss.mcp.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCommandsTest {
    private class Backend : AppUpdateBackend {
        var state = AppUpdateSnapshot("test-app", "1.0", "idle")
        var action: suspend (String, String?) -> Unit = { _, _ -> }
        var calls = 0

        override fun snapshot() = state

        override suspend fun perform(
            action: String,
            version: String?,
        ) {
            calls++
            this.action(action, version)
        }
    }

    @Test
    fun `status has no side effects and never exposes installer paths`() =
        runTest {
            val backend = Backend()
            val commands = AppUpdateCommands(backend, backgroundScope)
            val status = commands.status()
            assertEquals("1.0", status.payload["current_version"]?.jsonPrimitive?.content)
            assertFalse(status.payload.containsKey("download_path"))
            assertEquals(0, backend.calls)
        }

    @Test
    fun `download requires an available update`() =
        runTest {
            val backend = Backend()
            val commands = AppUpdateCommands(backend, backgroundScope)
            assertTrue(commands.start("download").isError)
            runCurrent()
            assertEquals(0, backend.calls)
        }

    @Test
    fun `install binds to exactly the staged version`() =
        runTest {
            val backend = Backend()
            backend.state = backend.state.copy(state = "ready_to_install", installVersion = "2.0")
            val commands = AppUpdateCommands(backend, backgroundScope)
            assertTrue(commands.start("install").isError)
            assertTrue(commands.start("install", "1.0").isError)
            assertFalse(commands.start("install", "2.0").isError)
            runCurrent()
            assertEquals(1, backend.calls)
        }

    @Test
    fun `background work is accepted once and status remains available`() =
        runTest {
            val backend = Backend()
            val finish = CompletableDeferred<Unit>()
            backend.action = { _, _ -> finish.await() }
            val commands = AppUpdateCommands(backend, backgroundScope)
            assertFalse(commands.start("check").isError)
            assertTrue(commands.start("check").isError)
            runCurrent()
            assertEquals(
                "check",
                commands
                    .status()
                    .payload["operation"]
                    ?.jsonPrimitive
                    ?.content,
            )
            finish.complete(Unit)
            runCurrent()
            assertFalse(commands.status().payload.containsKey("operation"))
            assertEquals(1, backend.calls)
        }

    @Test
    fun `background failures are visible and release the operation lock`() =
        runTest {
            val backend = Backend()
            backend.action = { _, _ -> error("network unavailable") }
            val commands = AppUpdateCommands(backend, backgroundScope)
            commands.start("check")
            runCurrent()
            assertEquals(
                "network unavailable",
                commands
                    .status()
                    .payload["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertFalse(commands.start("check").isError)
        }

    @Test
    fun `checks never replace a download or staged update`() =
        runTest {
            val backend = Backend()
            val commands = AppUpdateCommands(backend, backgroundScope)
            for (state in listOf("checking", "downloading", "ready_to_install", "installing", "restart_required")) {
                backend.state = backend.state.copy(state = state)
                assertTrue(commands.start("check").isError)
            }
            assertEquals(0, backend.calls)
        }
}
