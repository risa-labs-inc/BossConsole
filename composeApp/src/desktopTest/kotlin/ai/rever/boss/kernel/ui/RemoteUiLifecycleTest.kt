package ai.rever.boss.kernel.ui

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.UIRegistration
import ai.rever.boss.ipc.proto.UIUnregistration
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.kernel.services.PluginUIServiceBridge
import ai.rever.boss.ui.sdk.LifecycleStates
import ai.rever.boss.ui.sdk.WidgetNode
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProto
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.ui.sdk.WidgetType
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The surface lifecycle family, which #34 deferred entirely rather than ship half of.
 *
 * Its objection was precise: `created` is easy, `destroyed` "cannot be flushed" because the outgoing
 * flow dies with the surface scope, and a create-without-destroy lifecycle is a worse footgun than no
 * lifecycle at all. The first half of that is answered by ordering rather than by machinery — enqueue
 * `destroyed` *before* closing the channel and Kotlin's graceful close delivers it — and the second
 * half is answered by a latch, so the symmetry is a property of the code rather than a convention.
 *
 * These tests are therefore mostly about pairing: every ordering of attach, detach, stream and
 * unregister, checked for exactly one `created` and at most one matching `destroyed`. The last test is
 * the one #34 asked for by name — a plugin, over real gRPC, receiving `destroyed`.
 */
class RemoteUiLifecycleTest {
    private val registry = RemoteUiSurfaceRegistry()

    @Test
    fun `a component attaching to a streaming surface announces created`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        registry.attach(SURFACE, RecordingHost())

        assertEquals(listOf(LifecycleStates.CREATED), surface.lifecycleStates())
    }

    @Test
    fun `a stream binding under an attached component announces created`() {
        // The other order, which is just as normal: the user opens the panel, and the plugin process
        // comes up afterwards. Both halves have to be able to complete the rendezvous or the event is
        // only delivered when the timing happens to suit.
        registry.attach(SURFACE, RecordingHost())
        val surface = registry.register(SURFACE, PROCESS).accepted()

        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        assertEquals(listOf(LifecycleStates.CREATED), surface.lifecycleStates())
    }

    @Test
    fun `half a surface announces nothing`() {
        // A registered plugin with nowhere to draw, and a component with no plugin. Neither is "created":
        // the event means the widgets are on screen, and in both cases they are not.
        val unattached = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.attach(OTHER, RecordingHost())

        assertEquals(emptyList(), unattached.lifecycleStates(), "a streaming surface nobody renders is not created")
    }

    @Test
    fun `a registered surface with no stream is not created when a component attaches`() {
        // RegisterUI has returned but StreamUI has not bound yet — there is no transport to deliver over,
        // and the surface is not being rendered from either. Announcing here would emit into a queue that
        // may never be drained.
        val surface = registry.register(SURFACE, PROCESS).accepted()

        registry.attach(SURFACE, RecordingHost())

        assertEquals(emptyList(), surface.lifecycleStates())
    }

    @Test
    fun `created is announced exactly once however many times the halves are re-established`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        val host = RecordingHost()

        registry.attach(SURFACE, host)
        registry.attach(SURFACE, host)
        registry.attach(SURFACE, RecordingHost())

        assertEquals(listOf(LifecycleStates.CREATED), surface.lifecycleStates())
    }

    @Test
    fun `a component detaching announces destroyed to a plugin that is still there`() {
        // The case worth having: the user closed the panel, the plugin is alive and reading, and it
        // should stop doing work for a surface nobody is looking at.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        val host = RecordingHost()
        registry.attach(SURFACE, host)

        registry.detach(SURFACE, host)

        assertEquals(listOf(LifecycleStates.CREATED, LifecycleStates.DESTROYED), surface.lifecycleStates())
    }

    @Test
    fun `a component that was already displaced cannot announce destroyed over its successor`() {
        // detach is scoped to its owner. A component disposed late would otherwise tell a live plugin that
        // the surface its *replacement* is rendering has gone away.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        val first = RecordingHost()
        registry.attach(SURFACE, first)
        registry.attach(SURFACE, RecordingHost())

        registry.detach(SURFACE, first)

        assertEquals(listOf(LifecycleStates.CREATED), surface.lifecycleStates())
    }

    @Test
    fun `destroyed is never announced without a created`() {
        // The asymmetry #34 called a footgun, made unreachable rather than merely avoided: the latch is a
        // compare-and-set from true, so no ordering produces an unmatched teardown.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        val host = RecordingHost()
        registry.attach(SURFACE, host)

        registry.detach(SURFACE, host)
        registry.unregister(SURFACE)

        assertEquals(emptyList(), surface.lifecycleStates(), "the surface was never rendered, so nothing was destroyed")
    }

    @Test
    fun `a plugin respawning under a live component is told created again`() {
        // The new process never heard the first one. A latch that stayed set would leave a respawned
        // plugin waiting for an event that had already been spent on its predecessor.
        val first = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.attach(SURFACE, RecordingHost())
        registry.closeStream(first)

        val respawned = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        assertEquals(listOf(LifecycleStates.CREATED), respawned.lifecycleStates())
    }

    @Test
    fun `a dying stream announces nothing — destruction is inferred from the stream ending`() {
        // The one path that genuinely cannot deliver, and the reason this family is documented as
        // "a plugin that can be told, is told" rather than as a guarantee. There is no transport left.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.attach(SURFACE, RecordingHost())

        registry.closeStream(surface)

        assertEquals(listOf(LifecycleStates.CREATED), surface.lifecycleStates())
    }

    @Test
    fun `unregister announces destroyed directly, not only over the transport`() {
        // The gRPC test proves it reaches a plugin; this localises a regression to the registry instead
        // of leaving the transport test as the only witness.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.attach(SURFACE, RecordingHost())

        assertTrue(registry.unregister(SURFACE))

        assertEquals(listOf(LifecycleStates.CREATED, LifecycleStates.DESTROYED), surface.lifecycleStates())
    }

    @Test
    fun `attach and openStream racing still announce created exactly once`() {
        // The claim the whole rendezvous rests on, and the one every other test here drives on a single
        // thread: `attach` writes into `hosts` then reads `streaming`, `openStream` sets `streaming` then
        // reads `hosts`, so whichever runs second sees the other's write. That is a Dekker-shaped
        // argument about visibility, correct only because ConcurrentHashMap mutations and AtomicBoolean
        // accesses are synchronization actions — i.e. exactly the kind of reasoning that deserves to be
        // executed rather than believed. Released together, repeatedly, asserting exactly one `created`.
        //
        // Honest about its reach: this catches a DOUBLE announce reliably, and it exercises the real
        // concurrent path. It does not reliably catch the reverse — inverting attach() to read
        // `streaming` before publishing its host leaves it green, because the losing interleaving needs
        // both reads to precede both writes and a barrier cannot make that window wide enough. The
        // ordering still has to be read to be trusted; this only stops it regressing loudly.
        repeat(RACE_ROUNDS) { round ->
            val id = "$SURFACE-$round"
            val isolated = RemoteUiSurfaceRegistry()
            val surface = isolated.register(id, PROCESS).accepted()
            val barrier = CyclicBarrier(2)
            val threads =
                listOf(
                    thread {
                        barrier.await()
                        isolated.attach(id, RecordingHost())
                    },
                    thread {
                        barrier.await()
                        isolated.openStream(id)
                    },
                )
            threads.forEach { it.join() }

            assertEquals(
                listOf(LifecycleStates.CREATED),
                surface.lifecycleStates(),
                "round $round announced the wrong number of created events",
            )
        }
    }

    @Test
    fun `a created that could not be queued does not leave the surface owing a destroyed`() {
        // The latch is what makes the pair symmetric, so a failed emit must roll it back rather than
        // leave the surface owing a `destroyed` for a `created` no plugin ever saw. Unreachable through
        // the registry today — `emit` fails only on a closed surface, which would fail the `destroyed`
        // too — so the invariant currently rests on the overflow policy of a channel in another file.
        // Asserted directly instead.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.close()

        assertFalse(RemoteUiLifecycle.announceCreated(surface), "a closed surface cannot be announced")
        assertFalse(surface.createdAnnounced.get(), "the latch must not stay set for an event nobody got")
    }

    @Test
    fun `kernel shutdown announces destroyed to every rendered surface`() {
        // clear() runs on kernel shutdown while plugin streams may still be up, and it closes surfaces
        // by the same before-close ordering as unregister. Claimed in three separate comments and, until
        // this test, asserted nowhere — which for a file whose value is that the pairing is *checked*
        // rather than intended was the obvious hole.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.attach(SURFACE, RecordingHost())

        registry.clear()

        assertEquals(listOf(LifecycleStates.CREATED, LifecycleStates.DESTROYED), surface.lifecycleStates())
    }

    @Test
    fun `a plugin over real gRPC receives created and then destroyed as its last event`() {
        // What #34 asked to see before this family shipped. Nothing here is faked: a real server, the
        // generated plugin-side stub, and a response flow whose completion is what ends the collection —
        // so `destroyed` arriving *last* proves it was delivered before the channel closed rather than
        // dropped by the teardown that closes it.
        val tokenRegistry = ProcessTokenRegistry()
        val server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(PluginUIServiceBridge(registry))
                .build()
                .start()
        // Authenticated as PROCESS, matching registration()'s process id - the bridge now verifies
        // identity before RegisterUI/StreamUI/UnregisterUI (BossConsole#53).
        val channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(PROCESS)))
                .build()
        val plugin = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)
        try {
            val received =
                runBlocking {
                    coroutineScope {
                        assertTrue(plugin.registerUI(registration()).success)
                        val updates = Channel<WidgetUpdate>(Channel.UNLIMITED)
                        val events = async { plugin.streamUI(updates.consumeAsFlow()).toList() }
                        updates.send(fullTree())

                        // Bind first, then attach: the component arriving second is the ordering a user
                        // produces by opening a panel a running plugin already registered.
                        awaitTrue { registry.surfaceOf(SURFACE)?.streaming == true }
                        registry.attach(SURFACE, RecordingHost())

                        plugin.unregisterUI(UIUnregistration.newBuilder().setSurfaceId(SURFACE).build())
                        withTimeout(AWAIT_TIMEOUT_MS) { events.await() }
                    }
                }

            assertEquals(
                listOf(LifecycleStates.CREATED, LifecycleStates.DESTROYED),
                received.filter { it.hasLifecycle() }.map { it.lifecycle.lifecycleState },
            )
            assertTrue(received.last().hasLifecycle(), "destroyed must be the final event on the stream")
            assertEquals("", received.last().targetNodeId, "a lifecycle event belongs to the surface, not a node")
        } finally {
            channel.shutdownNow()
            channel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            server.shutdownNow()
            server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ---- Helpers ----

    /**
     * Every lifecycle state queued for the plugin, in order.
     *
     * Closes the surface first, which seals the queue so the flow completes and the collection returns at
     * once instead of waiting on a timeout. `close()` itself announces nothing — only the registry does,
     * and always *before* closing — so sealing cannot manufacture the event under test.
     */
    private fun RemoteUiSurface.lifecycleStates(): List<String> =
        runBlocking {
            close()
            withTimeout(AWAIT_TIMEOUT_MS) { events().toList() }
                .filter { it.hasLifecycle() }
                .map { it.lifecycle.lifecycleState }
        }

    private suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun SurfaceRegistration.accepted(): RemoteUiSurface = assertIs<SurfaceRegistration.Accepted>(this).surface

    private fun registration(): UIRegistration =
        UIRegistration
            .newBuilder()
            .setSurfaceId(SURFACE)
            .setSurfaceType("panel")
            .setProcessId(PROCESS)
            .build()

    private fun fullTree(): WidgetUpdate =
        WidgetUpdate
            .newBuilder()
            .setSurfaceId(SURFACE)
            .setFullTree(
                WidgetTree(
                    rootId = NODE,
                    nodes = mapOf(NODE to WidgetNode(NODE, WidgetType.TEXT, mapOf("value" to "hello"))),
                ).toProto(),
            ).build()

    private class RecordingHost : RemoteUiSurfaceHost {
        val trees = CopyOnWriteArrayList<WidgetTree>()
        val connections = CopyOnWriteArrayList<Boolean>()

        override fun onTreeUpdated(tree: WidgetTree) {
            trees += tree
        }

        override fun onConnectionChanged(connected: Boolean) {
            connections += connected
        }
    }

    private companion object {
        const val SURFACE = "panel-1"
        const val OTHER = "panel-2"
        const val PROCESS = "plugin-a"
        const val NODE = "node-1"
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 5L
        const val SHUTDOWN_TIMEOUT_MS = 5_000L

        /** Enough rounds to make a visibility gap show up, cheap enough to keep in the suite. */
        const val RACE_ROUNDS = 2_000
    }
}
