package ai.rever.boss.kernel.services

import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.ClickEvent
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.TextChangeEvent
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetNode
import ai.rever.boss.ipc.proto.WidgetType
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.kernel.ui.RemoteUiPlacement
import ai.rever.boss.kernel.ui.RemoteUiSurfaceHost
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.kernel.ui.SurfaceRegistration
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.ui.sdk.DiffOperation
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProtoDiff
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.rever.boss.ipc.proto.WidgetTree as ProtoWidgetTree
import ai.rever.boss.ui.sdk.WidgetTree as SdkWidgetTree

/**
 * The remote-UI transport, exercised over a real gRPC server with a real plugin-side client.
 *
 * This is the path that did not exist. `ui_protocol.proto` makes the plugin the client and the kernel
 * the server, and the host had it inverted: it dialled *out* with a `PluginUIServiceCoroutineStub` and,
 * because `StreamUI`'s request stream is typed `WidgetUpdate`, repacked every outgoing `UIEvent` as a
 * `WidgetUpdate` carrying only `surface_id`. Whatever the user did — the click id, the typed value, the
 * dropdown index — was discarded at that repack, and inbound `UIEvent`s were logged and dropped.
 *
 * So the assertion that matters throughout is not "an event arrived" but "an event arrived **with its
 * payload**". Everything here goes over localhost TCP through the generated stubs; nothing is faked at
 * the transport.
 *
 * The server runs behind a real [ProcessIdentityInterceptor] backed by [tokenRegistry], and [plugin] -
 * the default stub every pre-existing test in this file uses, sharing [channel] exactly as it always
 * has - is authenticated as [DEFAULT_PROCESS], the same identity [registration] defaults its
 * `process_id` field to. That is what lets the bulk of this file stay unchanged: one real caller, its
 * declared identity and its authenticated one agreeing, is exactly the ordinary case. The
 * BossConsole#53 tests further down are the ones that build a *second*, differently-authenticated stub
 * with [pluginAs] to exercise the disagreement.
 */
class PluginUIServiceBridgeTest {
    private lateinit var registry: RemoteUiSurfaceRegistry
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var plugin: PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub
    private val extraChannels = mutableListOf<ManagedChannel>()

    @BeforeTest
    fun setUp() {
        // Port 0: the OS picks a free one, which cannot race a concurrently starting test the way
        // "find a free port, then bind it" can.
        registry = RemoteUiSurfaceRegistry()
        tokenRegistry = ProcessTokenRegistry()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(PluginUIServiceBridge(registry))
                .build()
                .start()
        // channel and plugin share one connection, as they always have - several tests (e.g. "a plugin
        // that disappears...") kill `channel` directly to simulate the plugin process dying, and that
        // only tears down `plugin`'s actual stream if this is the same connection it uses.
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(DEFAULT_PROCESS)))
                .build()
        plugin = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)
    }

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        channel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        extraChannels.forEach { it.shutdownNow() }
        extraChannels.forEach { it.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * A stub authenticated as [processId] against this test's server - a fresh channel carrying its own
     * [ProcessTokenClientInterceptor], since one channel can only ever speak as the identity attached to
     * it at construction. Tracked in [extraChannels] so [tearDown] closes it too.
     */
    private fun pluginAs(processId: String): PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub {
        val token = tokenRegistry.issue(processId)
        val authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(token))
                .build()
        extraChannels += authenticatedChannel
        return PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(authenticatedChannel)
    }

    /** An unauthenticated stub against this test's server - no credential attached at all. */
    private fun anonymousPlugin(): PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub {
        val unauthenticatedChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        extraChannels += unauthenticatedChannel
        return PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(unauthenticatedChannel)
    }

    @Test
    fun `a widget tree streamed by a plugin reaches the attached host component`() =
        runBlocking {
            val host = RecordingHost()
            registry.attach(PANEL, host)
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = holdStream(updates)
                updates.send(fullTree(PANEL, label = "Save"))

                awaitTrue { host.trees.isNotEmpty() }
                assertEquals(
                    "Save",
                    host.trees
                        .last()
                        .nodes
                        .getValue(BUTTON_NODE)
                        .properties["label"],
                )
                assertTrue(host.connections.last(), "a streaming surface must report itself connected")
                stream.cancel()
            }
        }

    @Test
    fun `a click the host emits reaches the plugin with its event id intact`() =
        runBlocking {
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = async { plugin.streamUI(updates.consumeAsFlow()).take(1).toList() }
                updates.send(fullTree(PANEL))
                awaitTrue { registry.surfaceOf(PANEL)?.streaming == true }

                assertTrue(registry.emit(PANEL, click(PANEL, "save_settings")))

                val event = stream.await().single()
                // The old transport could carry exactly the first of these five.
                assertEquals(PANEL, event.surfaceId)
                assertEquals(BUTTON_NODE, event.targetNodeId)
                assertEquals(UIEvent.EventCase.CLICK, event.eventCase)
                assertEquals("save_settings", event.click.eventId)
                assertTrue(event.timestamp > 0, "timestamp must survive the wire")
            }
        }

    @Test
    fun `a burst of text changes arrives in the order the user typed it`() =
        runBlocking {
            // TextChange carries the whole field value with last-write-wins semantics, so a reordered
            // pair silently reverts a character. Ordering is a correctness property, not a nicety.
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val typed = (1..BURST).map { "value-$it" }
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = async { plugin.streamUI(updates.consumeAsFlow()).take(typed.size).toList() }
                updates.send(fullTree(PANEL))
                awaitTrue { registry.surfaceOf(PANEL)?.streaming == true }

                typed.forEach { value -> assertTrue(registry.emit(PANEL, textChange(PANEL, value))) }

                assertEquals(typed, stream.await().map { it.textChange.newValue })
            }
        }

    @Test
    fun `two surfaces never see each other's events`() =
        runBlocking {
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            assertTrue(plugin.registerUI(registration(TAB)).success)
            val panelUpdates = Channel<WidgetUpdate>(Channel.UNLIMITED)
            val tabUpdates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val panelStream = async { plugin.streamUI(panelUpdates.consumeAsFlow()).take(1).toList() }
                val tabStream = async { plugin.streamUI(tabUpdates.consumeAsFlow()).take(1).toList() }
                panelUpdates.send(fullTree(PANEL))
                tabUpdates.send(fullTree(TAB))
                awaitTrue {
                    registry.surfaceOf(PANEL)?.streaming == true && registry.surfaceOf(TAB)?.streaming == true
                }

                assertTrue(registry.emit(PANEL, textChange(PANEL, "panel-only")))
                assertTrue(registry.emit(TAB, textChange(TAB, "tab-only")))

                assertEquals(listOf("panel-only"), panelStream.await().map { it.textChange.newValue })
                assertEquals(listOf("tab-only"), tabStream.await().map { it.textChange.newValue })
            }
        }

    @Test
    fun `unregistering completes the plugin's event stream and drops later events`() =
        runBlocking {
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                // No take(): the flow must end because the surface closed, not because we stopped reading.
                val stream = async { plugin.streamUI(updates.consumeAsFlow()).toList() }
                updates.send(fullTree(PANEL))
                awaitTrue { registry.surfaceOf(PANEL)?.streaming == true }
                assertTrue(registry.emit(PANEL, textChange(PANEL, "before-teardown")))

                plugin.unregisterUI(UIUnregistration.newBuilder().setSurfaceId(PANEL).build())

                assertEquals(listOf("before-teardown"), stream.await().map { it.textChange.newValue })
                assertNull(registry.surfaceOf(PANEL), "an unregistered surface must not stay in the registry")
                // The interesting half: a click that lands during teardown is dropped, not thrown.
                assertFalse(registry.emit(PANEL, click(PANEL, "too_late")))
            }
        }

    @Test
    fun `a plugin that disappears leaves its surface visibly disconnected, not frozen`() =
        runBlocking {
            val host = RecordingHost()
            registry.attach(PANEL, host)
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = holdStream(updates)
                updates.send(fullTree(PANEL))
                awaitTrue { host.connections.lastOrNull() == true }

                // A crash, not a graceful UnregisterUI: the transport dying is the only notice the host
                // gets that a plugin process is gone.
                channel.shutdownNow()
                stream.join()
            }

            awaitTrue { host.connections.lastOrNull() == false }
            assertNull(registry.surfaceOf(PANEL), "a dead stream must release the surface id for a respawn")
            assertTrue(host.trees.isNotEmpty(), "the last tree stays on screen - disconnected, not blank")
        }

    @Test
    fun `a plugin that dies right after binding leaves no stuck claim behind`() =
        runBlocking {
            // The claim happens inside the request pump, so a `finally` guarding only the event collection
            // missed it: an RPC cancelled between `openStream()` returning Bound and the response side
            // resuming from `await()` left the surface streaming forever — the component reading
            // *connected* for a dead plugin, and every respawn refused for the rest of the session, since
            // reclaiming only takes over surfaces that are not streaming.
            //
            // Which side of the `await()` the cancellation lands on is not forceable from out here, so the
            // assertion is the invariant rather than the interleaving: after the plugin is gone, nothing
            // is left claiming a stream and the id is usable again.
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = holdStream(updates)
                updates.send(fullTree(PANEL))
                awaitTrue { registry.surfaceOf(PANEL)?.streaming == true }
                channel.shutdownNow()
                stream.join()
            }

            awaitTrue { registry.surfaceOf(PANEL)?.streaming != true }
            assertIs<SurfaceRegistration.Accepted>(registry.register(PANEL, "plugin-a"))
        }

    @Test
    fun `a diff sent over the wire is applied to the surface's tree`() =
        runBlocking {
            // Diffs are the intended steady state after the first full tree, and until this PR the host
            // could not decode one at all. Exercised over the wire, not just through applyUpdate.
            val host = RecordingHost()
            registry.attach(PANEL, host)
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = holdStream(updates)
                updates.send(fullTree(PANEL, label = "Save"))
                awaitTrue { host.trees.isNotEmpty() }

                updates.send(
                    WidgetUpdate
                        .newBuilder()
                        .setSurfaceId(PANEL)
                        .setDiff(
                            listOf<DiffOperation>(
                                DiffOperation.NodeUpdated(BUTTON_NODE, mapOf("label" to "Saving…"), null),
                            ).toProtoDiff(baseVersion = 1, newVersion = 2),
                        ).build(),
                )

                awaitTrue { host.trees.size > 1 }
                assertEquals(
                    "Saving…",
                    host.trees
                        .last()
                        .nodes
                        .getValue(BUTTON_NODE)
                        .properties["label"],
                )
                assertEquals(2L, registry.surfaceOf(PANEL)?.tree?.version)
                stream.cancel()
            }
        }

    @Test
    fun `an update naming a surface this stream is not bound to is ignored`() =
        runBlocking {
            // One call, one surface. A second surface_id on the same stream must not reach the other
            // surface — that would be the cross-delivery the per-surface routing exists to prevent.
            val panelHost = RecordingHost()
            val tabHost = RecordingHost()
            registry.attach(PANEL, panelHost)
            registry.attach(TAB, tabHost)
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            assertTrue(plugin.registerUI(registration(TAB)).success)
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val stream = holdStream(updates)
                updates.send(fullTree(PANEL, label = "Mine"))
                awaitTrue { panelHost.trees.isNotEmpty() }

                updates.send(fullTree(TAB, label = "Not mine"))
                // The stream stays healthy — the stray update is dropped, not fatal.
                updates.send(fullTree(PANEL, label = "Still mine"))
                awaitTrue { panelHost.trees.size > 1 }

                assertEquals(listOf("Mine", "Still mine"), panelHost.trees.map { it.label() })
                assertTrue(tabHost.trees.isEmpty(), "the other surface must not have been written to")
                assertNull(registry.surfaceOf(TAB)?.tree)
                stream.cancel()
            }
        }

    @Test
    fun `a duplicate surface id is rejected and says why`() =
        runBlocking {
            // Two actually-different processes, each authenticated as itself: the scenario this test
            // means to cover (two plugins racing for one surface_id) is inherently a two-identity one,
            // and a single stub can no longer stand in for both now that a declared process_id has to
            // match who is really calling.
            assertTrue(plugin.registerUI(registration(PANEL, process = "plugin-a")).success)

            val second = pluginAs("plugin-b").registerUI(registration(PANEL, process = "plugin-b"))

            assertFalse(second.success)
            assertContains(second.errorMessage, PANEL)
            assertContains(second.errorMessage, "plugin-a")
        }

    @Test
    fun `a blank surface id is rejected`() =
        runBlocking {
            val response = plugin.registerUI(registration(""))

            assertFalse(response.success)
            assertContains(response.errorMessage, "surface_id")
        }

    @Test
    fun `streaming a surface that was never registered fails with a usable reason`() =
        runBlocking {
            val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)
            val stream = plugin.streamUI(updates.consumeAsFlow())
            updates.send(fullTree("never-registered"))

            val failure = assertFailsWith<StatusException> { stream.toList() }

            // NOT_FOUND, not FAILED_PRECONDITION: this one is recoverable by registering the surface and
            // opening a new stream, and a plugin should not have to parse the description to know that.
            assertEquals(Status.Code.NOT_FOUND, failure.status.code)
            assertContains(failure.status.description.orEmpty(), "RegisterUI")
        }

    @Test
    fun `a stream that never names a surface is refused rather than left hanging`() =
        runBlocking {
            assertTrue(plugin.registerUI(registration(PANEL)).success)

            // StreamUI has no surface_id of its own; the request stream closing without one leaves the
            // call unbindable, and the plugin deserves an error instead of an RPC that never returns.
            val failure = assertFailsWith<StatusException> { plugin.streamUI(emptyFlow()).toList() }

            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
            assertContains(failure.status.description.orEmpty(), "first WidgetUpdate")
        }

    @Test
    fun `a stream that sends nothing and never closes is reaped rather than held open`() =
        runBlocking {
            // The INVALID_ARGUMENT case above needs the request stream to END. A plugin that opens the call
            // and then neither sends nor half-closes gives no such completion, and gRPC applies no server
            // deadline of its own — so without the bind timeout the RPC and its pump would sit there for the
            // life of the process. Runs against its own server so the deadline can be milliseconds.
            val brief =
                ServerBuilder
                    .forPort(0)
                    .intercept(ProcessIdentityInterceptor(tokenRegistry))
                    .addService(quickBridge())
                    .build()
                    .start()
            val briefChannel =
                ManagedChannelBuilder
                    .forAddress("localhost", brief.port)
                    .usePlaintext()
                    .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(DEFAULT_PROCESS)))
                    .build()
            try {
                val silent = Channel<WidgetUpdate>(Channel.UNLIMITED)
                val stub = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(briefChannel)
                assertTrue(stub.registerUI(registration(PANEL)).success)

                val failure = assertFailsWith<StatusException> { stub.streamUI(silent.consumeAsFlow()).toList() }

                assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.status.code)
                assertContains(failure.status.description.orEmpty(), "first WidgetUpdate")
            } finally {
                briefChannel.shutdownNow()
                brief.shutdownNow()
            }
        }

    @Test
    fun `a second stream for the same surface is refused so ordering stays intact`() =
        runBlocking {
            assertTrue(plugin.registerUI(registration(PANEL)).success)
            val first = Channel<WidgetUpdate>(Channel.UNLIMITED)

            coroutineScope {
                val held = async { plugin.streamUI(first.consumeAsFlow()).toList() }
                first.send(fullTree(PANEL))
                awaitTrue { registry.surfaceOf(PANEL)?.streaming == true }

                val second = Channel<WidgetUpdate>(Channel.UNLIMITED)
                val rival = plugin.streamUI(second.consumeAsFlow())
                second.send(fullTree(PANEL))
                val failure = assertFailsWith<StatusException> { rival.toList() }

                // FAILED_PRECONDITION: unlike an unregistered surface, this one never becomes available.
                assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)
                assertContains(failure.status.description.orEmpty(), "StreamUI")

                // The original stream is untouched by the rejection.
                assertTrue(registry.emit(PANEL, textChange(PANEL, "still-mine")))
                assertTrue(registry.unregister(PANEL))
                assertEquals(listOf("still-mine"), held.await().map { it.textChange.newValue })
            }
        }

    @Test
    fun `a registration's initial tree renders before any stream exists`() =
        runBlocking {
            val host = RecordingHost()
            registry.attach(PANEL, host)

            val response =
                plugin.registerUI(
                    registration(PANEL)
                        .toBuilder()
                        .setInitialTree(protoTree(label = "Ready"))
                        .build(),
                )

            assertTrue(response.success)
            assertEquals(
                "Ready",
                host.trees
                    .single()
                    .nodes
                    .getValue(BUTTON_NODE)
                    .properties["label"],
            )
            assertFalse(host.connections.last(), "no stream yet, so the surface is not connected")
        }

    // ---- BossConsole#53: process identity ----

    @Test
    fun `RegisterUI with no credential at all is refused`() =
        runBlocking {
            val failure = assertFailsWith<StatusException> { anonymousPlugin().registerUI(registration(PANEL)) }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertNull(registry.surfaceOf(PANEL), "a refused registration must not reach the registry")
        }

    @Test
    fun `process A cannot impersonate process B through RegisterUI`() =
        runBlocking {
            // plugin-a's own channel, claiming to be plugin-b in the request body.
            val failure =
                assertFailsWith<StatusException> {
                    plugin.registerUI(registration(PANEL, process = "plugin-b"))
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertNull(registry.surfaceOf(PANEL), "an impersonation attempt must not reach the registry")

            // The rejection cost plugin-a nothing: it can still register under its own name.
            assertTrue(plugin.registerUI(registration(PANEL, process = DEFAULT_PROCESS)).success)
        }

    @Test
    fun `process A cannot impersonate process B through StreamUI`() =
        runBlocking {
            val host = RecordingHost()
            registry.attach(PANEL, host)
            assertTrue(pluginAs("plugin-b").registerUI(registration(PANEL, process = "plugin-b")).success)

            // plugin-a (this test's default `plugin`) tries to stream plugin-b's surface.
            val attack = Channel<WidgetUpdate>(Channel.UNLIMITED)
            val attackStream = plugin.streamUI(attack.consumeAsFlow())
            attack.send(fullTree(PANEL, label = "stolen"))

            val failure = assertFailsWith<StatusException> { attackStream.toList() }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)

            // plugin-b's surface was never claimed, closed or overwritten by the attempt: it streams
            // normally afterwards, exactly as if the attack had never happened.
            assertFalse(registry.surfaceOf(PANEL)!!.streaming, "the attempt must not have claimed the surface")
            val legitimate = Channel<WidgetUpdate>(Channel.UNLIMITED)
            coroutineScope {
                val stream = launch { runCatching { pluginAs("plugin-b").streamUI(legitimate.consumeAsFlow()).toList() } }
                legitimate.send(fullTree(PANEL, label = "mine"))
                awaitTrue { host.trees.isNotEmpty() }
                assertEquals("mine", host.trees.last().label())
                stream.cancel()
            }
        }

    @Test
    fun `process A cannot impersonate process B through UnregisterUI`() =
        runBlocking {
            assertTrue(pluginAs("plugin-b").registerUI(registration(PANEL, process = "plugin-b")).success)

            val failure =
                assertFailsWith<StatusException> {
                    plugin.unregisterUI(UIUnregistration.newBuilder().setSurfaceId(PANEL).build())
                }

            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertNotNull(registry.surfaceOf(PANEL), "plugin-b's surface must survive plugin-a's attempt")

            // The true owner can still unregister its own surface.
            pluginAs("plugin-b").unregisterUI(UIUnregistration.newBuilder().setSurfaceId(PANEL).build())
            assertNull(registry.surfaceOf(PANEL))
        }

    @Test
    fun `unregistering an unknown surface is unaffected by having no credential`() =
        runBlocking {
            // No owner exists to protect, so this stays the pre-existing idempotent no-op regardless of
            // who (or what) calls it - unlike RegisterUI and StreamUI, which always require identity.
            anonymousPlugin().unregisterUI(UIUnregistration.newBuilder().setSurfaceId("never-registered").build())
        }

    // ---- BossConsole#54: identity gates placement, end to end ----
    //
    // #53's rejections are proven above at the RPC layer (a thrown PERMISSION_DENIED). These two
    // additionally wire a real RemoteUiPlacement pointed at a real (if throwaway) window, so "never
    // reaches placement" is an observed fact about a PanelRegistry - not just an inference from the
    // RPC failing.

    @Test
    fun `an unauthenticated RegisterUI never reaches placement`() =
        runBlocking {
            withWiredPlacement { panelRegistry, _, _, anonymousStub ->
                assertFailsWith<StatusException> { anonymousStub().registerUI(registration(PANEL)) }

                assertNull(registry.surfaceOf(PANEL))
                assertTrue(panelRegistry.getAllPanels().none { it.id.panelId == PANEL })
            }
        }

    @Test
    fun `process A claiming process B's identity never places a surface under either name`() =
        runBlocking {
            withWiredPlacement { panelRegistry, tabRegistry, stubAs, _ ->
                val failure =
                    assertFailsWith<StatusException> {
                        stubAs("plugin-a").registerUI(registration(PANEL, process = "plugin-b"))
                    }

                assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
                assertNull(registry.surfaceOf(PANEL), "the impersonation attempt must not have registered anything")
                assertTrue(panelRegistry.getAllPanels().none { it.id.panelId == PANEL })
                assertTrue(tabRegistry.getAllTabTypes().none { it.typeId.typeId == PANEL })
            }
        }

    /**
     * A second bridge, wired with a real [RemoteUiPlacement] pointed at a throwaway window
     * (registered into the same process-wide [SplitViewStateRegistry] / [PanelComponentStoreRegistry]
     * production placement itself resolves through), so a test can observe whether placement was
     * actually reached rather than inferring it from the RPC's own outcome. Torn down on return.
     */
    private suspend fun <T> withWiredPlacement(
        use: suspend (
            panelRegistry: PanelRegistry,
            tabRegistry: TabRegistry,
            stubAs: (String) -> PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub,
            anonymousStub: () -> PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub,
        ) -> T,
    ): T {
        val windowId = "bridge-placement-test-${placementWindowCounter.getAndIncrement()}"
        val panelRegistry = PanelRegistry()
        val tabRegistry = TabRegistry()
        val splitViewState = SplitViewState(tabRegistry, windowId)
        val store = PanelComponentStore(DefaultComponentContext(LifecycleRegistry()), panelRegistry)
        SplitViewStateRegistry.register(windowId, splitViewState)
        PanelComponentStoreRegistry.register(windowId, store)
        val wiredPlacement = RemoteUiPlacement(registry = registry, resolveWindowId = { windowId })
        val wiredServer =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(PluginUIServiceBridge(registry, placement = wiredPlacement))
                .build()
                .start()
        val channels = mutableListOf<ManagedChannel>()

        fun stubAs(processId: String): PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub {
            val ch =
                ManagedChannelBuilder
                    .forAddress("localhost", wiredServer.port)
                    .usePlaintext()
                    .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(processId)))
                    .build()
            channels += ch
            return PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(ch)
        }

        fun anonymousStub(): PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub {
            val ch = ManagedChannelBuilder.forAddress("localhost", wiredServer.port).usePlaintext().build()
            channels += ch
            return PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(ch)
        }
        try {
            return use(panelRegistry, tabRegistry, ::stubAs, ::anonymousStub)
        } finally {
            channels.forEach { it.shutdownNow() }
            wiredServer.shutdownNow()
            SplitViewStateRegistry.unregister(windowId)
            PanelComponentStoreRegistry.unregister(windowId)
            splitViewState.dispose()
        }
    }

    // ---- Helpers ----

    /**
     * Thread-safe on purpose: the transport publishes from a gRPC thread while the test thread polls and
     * reads these, so a plain ArrayList would be a data race — the kind that survives a laptop and flakes
     * on a loaded CI runner.
     */
    private class RecordingHost : RemoteUiSurfaceHost {
        val trees = CopyOnWriteArrayList<SdkWidgetTree>()
        val connections = CopyOnWriteArrayList<Boolean>()

        override fun onTreeUpdated(tree: SdkWidgetTree) {
            trees += tree
        }

        override fun onConnectionChanged(connected: Boolean) {
            connections += connected
        }
    }

    /**
     * Open a `StreamUI` call and keep it open, discarding events.
     *
     * The returned RPC only runs while something collects the response flow, so tests that assert on the
     * *inbound* direction still have to hold the call open.
     */
    private fun kotlinx.coroutines.CoroutineScope.holdStream(updates: Channel<WidgetUpdate>): Job =
        launch {
            runCatching { plugin.streamUI(updates.consumeAsFlow()).toList() }
        }

    private fun SdkWidgetTree.label(): String? = nodes[BUTTON_NODE]?.properties?.get("label")

    /** Poll until [condition] holds. The host applies inbound updates on a gRPC thread. */
    private suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** A bridge over the same registry whose bind deadline fires in milliseconds. */
    private fun quickBridge() = PluginUIServiceBridge(registry, bindTimeoutMs = BRIEF_BIND_TIMEOUT_MS)

    private fun registration(
        surfaceId: String,
        process: String = DEFAULT_PROCESS,
    ): UIRegistration =
        UIRegistration
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setSurfaceType("panel")
            .setDisplayName("Test Surface")
            .setProcessId(process)
            .build()

    private fun protoTree(label: String): ProtoWidgetTree =
        ProtoWidgetTree
            .newBuilder()
            .setRootId(BUTTON_NODE)
            .setVersion(1)
            .addNodes(
                WidgetNode
                    .newBuilder()
                    .setId(BUTTON_NODE)
                    .setType(WidgetType.WIDGET_TYPE_BUTTON)
                    .putProperties("label", label)
                    .putProperties("clickEventId", "save_settings"),
            ).build()

    private fun fullTree(
        surfaceId: String,
        label: String = "Save",
    ): WidgetUpdate =
        WidgetUpdate
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setFullTree(protoTree(label))
            .build()

    private fun click(
        surfaceId: String,
        eventId: String,
    ): UIEvent =
        UIEvent
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setTargetNodeId(BUTTON_NODE)
            .setTimestamp(System.currentTimeMillis())
            .setClick(ClickEvent.newBuilder().setEventId(eventId))
            .build()

    private fun textChange(
        surfaceId: String,
        value: String,
    ): UIEvent =
        UIEvent
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setTargetNodeId(FIELD_NODE)
            .setTimestamp(System.currentTimeMillis())
            .setTextChange(TextChangeEvent.newBuilder().setNewValue(value))
            .build()

    private companion object {
        const val PANEL = "panel-1"
        const val TAB = "tab-1"
        const val BUTTON_NODE = "button-7"
        const val FIELD_NODE = "field-3"
        const val BURST = 50
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 5L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
        const val DEFAULT_PROCESS = "plugin-a"
        val placementWindowCounter = AtomicInteger(0)

        /** Long enough not to be flaky, short enough that the reaping test costs nothing. */
        const val BRIEF_BIND_TIMEOUT_MS = 250L
    }
}
