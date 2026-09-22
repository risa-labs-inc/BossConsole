package ai.rever.boss.kernel

import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.NavigationTargetIpcPayload
import ai.rever.boss.components.plugin.providers.NavigationTargetProviderImpl
import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NavigationTargetIpcTest {
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
    fun `local and IPC consumers receive equivalent structured targets`() =
        runBlocking {
            val bridge = RecordingBridge()
            NavigationTargetBus.ipcBridge = bridge

            NavigationTargetBus.navigateTo(
                filePath = "/workspace/src/Shared.kt",
                line = 73,
                column = 19,
                sourceWindowId = "window-shared",
            )

            val local = withTimeout(TIMEOUT_MS) { NavigationTargetProviderImpl.targets.first() }
            val forwarded = assertNotNull(bridge.payload) as NavigationTargetIpcPayload
            assertEquals(local.filePath, forwarded.filePath)
            assertEquals(local.line, forwarded.line)
            assertEquals(local.column, forwarded.column)
            assertEquals(local.sourceWindowId, forwarded.sourceWindowId)
            assertEquals("NavigationTargetEvent", bridge.eventType)
            assertEquals("window-shared", bridge.sourceWindowId)

            val json = Json.encodeToJsonElement(NavigationTargetIpcPayload.serializer(), forwarded).jsonObject
            val serializedPath = json.getValue("filePath").jsonPrimitive.content
            val serializedLine = json.getValue("line").jsonPrimitive.content
            val serializedColumn = json.getValue("column").jsonPrimitive.content
            val serializedWindow = json.getValue("sourceWindowId").jsonPrimitive.content
            assertEquals("/workspace/src/Shared.kt", serializedPath)
            assertEquals("73", serializedLine)
            assertEquals("19", serializedColumn)
            assertEquals("window-shared", serializedWindow)
        }

    @Test
    fun `invalid target lines are not forwarded`() =
        runBlocking {
            val bridge = RecordingBridge()
            NavigationTargetBus.ipcBridge = bridge

            NavigationTargetBus.navigateTo("/workspace/Zero.kt", 0, 8, "window-a")
            NavigationTargetBus.navigateTo("/workspace/Negative.kt", -4, 8, "window-a")

            assertNull(bridge.payload, "a rejected local target must not cross the IPC boundary")
        }

    private class RecordingBridge : IpcEventBridge {
        var eventType: String? = null
        var payload: Any? = null
        var sourceWindowId: String? = null

        override suspend fun forward(
            eventType: String,
            payload: Any,
            sourceWindowId: String,
        ) {
            this.eventType = eventType
            this.payload = payload
            this.sourceWindowId = sourceWindowId
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
