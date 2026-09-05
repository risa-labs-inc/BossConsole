package ai.rever.boss.components.plugin.remote

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.kernel.services.PluginUIServiceBridge
import ai.rever.boss.kernel.ui.RemoteUiSurface
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.kernel.ui.SurfaceRegistration
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetNode
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProto
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.ui.sdk.WidgetType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renderer behaviour asserted against a real composition.
 *
 * These three rules were fixed with care and documented in prose, but nothing executed them: focus was
 * only reported on a *transition*, a widget's local state was made pushable by the plugin without
 * clobbering in-flight typing, and `LIST` inside `SCROLL` fell back to a plain column because a
 * `LazyColumn` measured with an unbounded max height throws. Each is a claim about what happens across
 * two compositions, which is exactly what cannot be checked by reading the tree or the wire.
 *
 * The last test closes the loop the rest of this PR opens: a real click on a rendered widget, landing in
 * the surface's outgoing queue as a `UIEvent` with its payload.
 */
class RemoteWidgetRendererComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a text field reports no focus event on its first composition`() {
        // onFocusChanged also fires when a node's focus state is first resolved on attach. Wired straight
        // through, every field announced focus *loss* the moment it rendered, and a plugin could not tell
        // that from a real blur.
        val events = mutableListOf<Pair<String, WidgetEvent>>()
        compose.setContent {
            RemoteWidgetRenderer(textFieldTree("seed"), onEvent = { id, event -> events += id to event })
        }
        compose.waitForIdle()

        assertTrue(
            events.none { it.second is WidgetEvent.Focus },
            "first composition must not report a focus transition, got $events",
        )
    }

    @Test
    fun `focusing a text field reports the transition`() {
        val events = mutableListOf<Pair<String, WidgetEvent>>()
        compose.setContent {
            RemoteWidgetRenderer(textFieldTree("seed"), onEvent = { id, event -> events += id to event })
        }

        compose.onNode(hasSetTextAction()).performTextReplacement("typed")
        compose.waitForIdle()

        assertEquals(
            listOf(WidgetEvent.Focus(hasFocus = true)),
            events.map { it.second }.filterIsInstance<WidgetEvent.Focus>(),
            "gaining focus is a real transition and must be reported exactly once",
        )
        assertEquals(FIELD, events.first().first, "events must be tagged with the node that raised them")
    }

    @Test
    fun `a plugin can push a new value into a field without clobbering what the user is typing`() {
        var tree by mutableStateOf(textFieldTree("seed"))
        compose.setContent { RemoteWidgetRenderer(tree) }
        compose.onNodeWithText("seed").assertExists()

        compose.onNode(hasSetTextAction()).performTextReplacement("typed")
        compose.waitForIdle()
        compose.onNodeWithText("typed").assertExists()

        // A tree update that repeats the value the plugin already sent is the common case — it happens on
        // every unrelated change — and must leave the buffer alone.
        tree = textFieldTree("seed").copy(version = 2)
        compose.waitForIdle()
        compose.onNodeWithText("typed").assertExists()

        // A genuinely new value is the plugin driving its own widget: clearing a box after submit,
        // echoing back a normalized value, rejecting an edit. The plugin wins.
        tree = textFieldTree("pushed-back")
        compose.waitForIdle()
        compose.onNodeWithText("pushed-back").assertExists()
    }

    @Test
    fun `a list nested inside a scroll renders its rows instead of throwing`() {
        // A LazyColumn measured with an unbounded max height throws, so this tree shape — which a plugin
        // can send at any time — used to take the whole surface down.
        val tree =
            WidgetTree(
                rootId = "scroll",
                nodes =
                    mapOf(
                        "scroll" to WidgetNode("scroll", WidgetType.SCROLL, childIds = listOf("list")),
                        "list" to
                            WidgetNode(
                                id = "list",
                                type = WidgetType.LIST,
                                properties = mapOf("items" to "alpha,beta"),
                            ),
                    ),
            )

        compose.setContent { RemoteWidgetRenderer(tree) }
        compose.waitForIdle()

        compose.onNodeWithText("alpha").assertExists()
        compose.onNodeWithText("beta").assertExists()
    }

    @Test
    fun `a click on a rendered button reaches the panel's surface with its event id`() {
        val registry = RemoteUiSurfaceRegistry()
        val surface = (registry.register(PANEL, "plugin-a") as SurfaceRegistration.Accepted).surface
        val panel = RemotePanelComponent(PANEL, "Test Panel", "plugin-a", registry)
        surface.pushTree(buttonTree())
        // Attached after the tree arrived: the surface retains it, so the panel renders immediately.
        panel.attach()

        compose.setContent { panel.Content() }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        val queued = surface.firstEvent()
        // The panel's id is used twice — to attach and emit, and to stamp the event — and the two uses
        // must agree or the plugin receives events for a surface it does not own.
        assertEquals(PANEL, queued.surfaceId)
        assertEquals(BUTTON, queued.targetNodeId)
        assertEquals("save_settings", queued.click.eventId)
        panel.dispose()
    }

    @Test
    fun `a click on a rendered button reaches the tab's surface with its event id`() {
        // Same wiring, separately implemented in RemoteTabComponent — so separately asserted.
        val registry = RemoteUiSurfaceRegistry()
        val surface = (registry.register(TAB, "plugin-a") as SurfaceRegistration.Accepted).surface
        val tab = RemoteTabComponent(TAB, "Test Tab", "plugin-a", registry)
        tab.attach()
        surface.pushTree(buttonTree())

        compose.setContent { tab.Content() }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        val queued = surface.firstEvent()
        assertEquals(TAB, queued.surfaceId)
        assertEquals(BUTTON, queued.targetNodeId)
        assertEquals("save_settings", queued.click.eventId)
        tab.dispose()
    }

    @Test
    fun `a plugin over real gRPC renders into a real composition and gets the click back`() {
        // The one combination nothing else covers: the round-trip tests use a recording host, and the other
        // Compose tests drive the registry directly, so gRPC threads writing Compose snapshot state through
        // RemoteUiSurfaceHost — the actual production threading — was never exercised. Everything here is
        // real: a gRPC server, the generated plugin-side stub, a composition, and a click.
        //
        // The plugin side runs on its own scope rather than inside runBlocking around the Compose calls:
        // runBlocking owns this thread's event loop, and the Compose test rule needs it to advance frames,
        // so nesting the two deadlocks (`waitUntil` times out with the tree sitting undelivered).
        val registry = RemoteUiSurfaceRegistry()
        val tokenRegistry = ProcessTokenRegistry()
        val server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(PluginUIServiceBridge(registry))
                .build()
                .start()
        // Authenticated as "plugin-a", matching this test's registration/panel process id - the bridge
        // now verifies identity before RegisterUI/StreamUI/UnregisterUI (BossConsole#53).
        val channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue("plugin-a")))
                .build()
        val plugin = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)
        val pluginScope = CoroutineScope(Dispatchers.Default)
        val panel = RemotePanelComponent(PANEL, "Test Panel", "plugin-a", registry)
        panel.attach()
        compose.setContent { panel.Content() }

        try {
            val registration =
                UIRegistration
                    .newBuilder()
                    .setSurfaceId(PANEL)
                    .setSurfaceType("panel")
                    .setProcessId("plugin-a")
                    .build()
            assertTrue(runBlocking { plugin.registerUI(registration).success })

            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)
            // Filtered to clicks, not `take(1)`. The rendezvous now announces `created` the moment this
            // stream binds under the already-attached panel, so the first event on the wire is a
            // lifecycle one — and `take(1)` cancels the RPC, which would tear the surface down before
            // the click was made. Any consumer that reads "the first event" has to allow for that.
            val events =
                pluginScope.async {
                    plugin
                        .streamUI(updates.consumeAsFlow())
                        .filter { it.hasClick() }
                        .take(1)
                        .toList()
                }
            val fullTree =
                WidgetUpdate
                    .newBuilder()
                    .setSurfaceId(PANEL)
                    .setFullTree(buttonTree().toProto())
                    .build()
            assertTrue(updates.trySend(fullTree).isSuccess)

            // The tree crosses the wire on a gRPC thread and has to land in the composition.
            compose.waitUntil(WAIT_TIMEOUT_MS) {
                runCatching { compose.onNodeWithText("Save").assertExists() }.isSuccess
            }
            assertTrue(panel.connected.value, "a streaming plugin must read as connected")

            compose.onNodeWithText("Save").performClick()
            compose.waitForIdle()

            val received = runBlocking { withTimeout(WAIT_TIMEOUT_MS) { events.await() } }.single()
            assertEquals(PANEL, received.surfaceId)
            assertEquals(BUTTON, received.targetNodeId)
            assertEquals("save_settings", received.click.eventId)
        } finally {
            panel.dispose()
            pluginScope.cancel()
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    /**
     * The first event queued for a surface.
     *
     * Bounded: an unbounded `runBlocking` here would make a regression *hang the build* instead of failing
     * a test, which is a far worse failure mode on CI than a red assertion.
     */
    private fun RemoteUiSurface.firstEvent(): UIEvent =
        runBlocking {
            withTimeout(WAIT_TIMEOUT_MS) { events().take(1).toList() }.single()
        }

    private fun textFieldTree(value: String): WidgetTree =
        WidgetTree(
            rootId = FIELD,
            nodes =
                mapOf(
                    FIELD to
                        WidgetNode(
                            id = FIELD,
                            type = WidgetType.TEXT_FIELD,
                            properties = mapOf("value" to value, "onChangeEvent" to "changed"),
                        ),
                ),
        )

    private fun buttonTree(): WidgetTree =
        WidgetTree(
            rootId = BUTTON,
            nodes =
                mapOf(
                    BUTTON to
                        WidgetNode(
                            id = BUTTON,
                            type = WidgetType.BUTTON,
                            properties = mapOf("label" to "Save", "clickEventId" to "save_settings"),
                        ),
                ),
        )

    private companion object {
        const val FIELD = "field-1"
        const val BUTTON = "button-1"
        const val PANEL = "panel-1"
        const val TAB = "tab-1"
        const val WAIT_TIMEOUT_MS = 10_000L
    }
}
