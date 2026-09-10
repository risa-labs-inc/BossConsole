package ai.rever.boss.kernel.ui

import ai.rever.boss.ipc.proto.TextChangeEvent
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ipc.proto.WidgetUpdate
import ai.rever.boss.ui.sdk.DiffOperation
import ai.rever.boss.ui.sdk.WidgetNode
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProto
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProtoDiff
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.ui.sdk.WidgetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import ai.rever.boss.ipc.proto.DiffOperation as ProtoDiffOp

/**
 * The rendezvous rules that let a surface's two halves start, stop and restart independently.
 *
 * A plugin process and the panel or tab it draws into have unrelated lifetimes: the plugin registers
 * whenever it comes up, the component is built when the user opens the surface, and a plugin can crash
 * and respawn under a component that never went away. Every ordering here is therefore normal, and none
 * of them may lose a tree, misroute an event, or throw at a caller.
 */
class RemoteUiSurfaceRegistryTest {
    private val registry = RemoteUiSurfaceRegistry()

    @Test
    fun `capabilities replay before streaming and reset for absent displaced and closed surfaces`() {
        val originalHost = RecordingHost()
        registry.attach(SURFACE, originalHost)
        assertEquals(listOf(false), originalHost.capabilities)

        val surface = registry.register(SURFACE, PROCESS, RemoteUiSurfaceDescriptor(wantsKeys = true)).accepted()
        val replacementHost = RecordingHost()
        registry.attach(SURFACE, replacementHost)
        assertEquals(listOf(false, false), originalHost.capabilities, "displacement resets the old host")
        assertEquals(listOf(true), replacementHost.capabilities, "replay grants before a stream exists")

        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE, PROCESS))
        registry.closeStream(surface)
        assertEquals(listOf(true, true, false), replacementHost.capabilities, "closing revokes the tap")
    }

    @Test
    fun `unregister and kernel reset retain ownership while old host compositions can remain`() {
        registry.register(SURFACE, PROCESS).accepted()
        registry.unregister(SURFACE)
        assertIs<SurfaceRegistration.Rejected>(registry.register(SURFACE, "intruder"))
        registry.clear()
        assertIs<SurfaceRegistration.Rejected>(registry.register(SURFACE, "intruder"))
        assertIs<SurfaceRegistration.Accepted>(registry.register(SURFACE, PROCESS))
    }

    @Test
    fun `a disconnected visible surface cannot be taken over by another process`() {
        val original = registry.register(SURFACE, PROCESS).accepted()
        registry.closeStream(original)
        assertIs<SurfaceRegistration.Rejected>(registry.register(SURFACE, "intruder"))
        assertIs<SurfaceRegistration.Accepted>(registry.register(SURFACE, PROCESS))
    }

    @Test
    fun `authorization of an old registration cannot remove its replacement`() {
        val original = registry.register(SURFACE, PROCESS).accepted()
        val replacement = registry.register(SURFACE, PROCESS).accepted()
        assertFalse(registry.unregister(SURFACE, original))
        assertEquals(replacement, registry.surfaceOf(SURFACE))
    }

    @Test
    fun `stream ownership is checked when the claim is acquired`() {
        registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.NotOwner>(registry.openStream(SURFACE, "intruder"))
        assertIs<SurfaceStream.NotOwner>(registry.openStream(SURFACE, null))
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE, PROCESS))
    }

    @Test
    fun `a component attached before the plugin exists still gets the first tree`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)

        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("first"))

        assertEquals(listOf("first"), host.trees.map { it.label() })
    }

    @Test
    fun `a component attached after the plugin is replayed the current state instead of rendering blank`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("already-here"))
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        val host = RecordingHost()
        registry.attach(SURFACE, host)

        assertEquals(listOf("already-here"), host.trees.map { it.label() })
        assertEquals(listOf(true), host.connections, "the surface was already streaming when we attached")
    }

    @Test
    fun `a duplicate registration is refused and names the process holding the id`() {
        registry.register(SURFACE, "plugin-a").accepted()

        val second = registry.register(SURFACE, "plugin-b")

        val rejected = assertIs<SurfaceRegistration.Rejected>(second)
        assertContains(rejected.reason, SURFACE)
        assertContains(rejected.reason, "plugin-a")
    }

    @Test
    fun `a surface id is reusable once its stream dies, so a respawned plugin can take it back`() {
        val first = registry.register(SURFACE, "plugin-a").accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.closeStream(first)

        val second = registry.register(SURFACE, PROCESS)

        assertIs<SurfaceRegistration.Accepted>(second)
    }

    @Test
    fun `a surface claimed but never streamed is reclaimable by its own process`() {
        // The gap closeStream cannot see: a plugin can die between RegisterUI returning and StreamUI
        // binding, or claim more surfaces than it streams. Refusing the respawn's claim would leave that
        // panel permanently disconnected with no way back.
        val abandoned = registry.register(SURFACE, PROCESS).accepted()

        val respawn = registry.register(SURFACE, PROCESS)

        val surface = assertIs<SurfaceRegistration.Accepted>(respawn).surface
        assertNotSame(abandoned, surface)
        assertSame(surface, registry.surfaceOf(SURFACE))
        assertFalse(abandoned.emit(textChange("to-the-dead-one")), "the reclaimed surface is closed")
    }

    @Test
    fun `a blank process id is refused, because the reclaim rule treats it as an identity`() {
        // proto3 makes the empty string the default, so a runtime that forgets the field would hand every
        // plugin the same identity — and with it the right to reclaim any other plugin's un-streamed
        // surface, and then receive that surface's keystrokes.
        val rejected = assertIs<SurfaceRegistration.Rejected>(registry.register(SURFACE, ""))

        assertContains(rejected.reason, "process_id")
    }

    @Test
    fun `a second component attaching to one surface detaches the first instead of freezing it`() {
        // The registry routes to one host per id and is process-wide while the app is multi-window, so this
        // is reachable. Silence would leave the displaced component rendering its last tree and still
        // reporting connected — frozen from the host side rather than the plugin side.
        val first = RecordingHost()
        val second = RecordingHost()
        registry.attach(SURFACE, first)
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        assertEquals(listOf(false, true), first.connections)

        registry.attach(SURFACE, second)
        surface.pushTree(tree("for-the-second"))

        assertEquals(false, first.connections.last(), "the displaced component must be told it is detached")
        assertTrue(first.trees.isEmpty(), "and must stop receiving trees")
        assertEquals(listOf("for-the-second"), second.trees.map { it.label() })
        assertEquals(true, second.connections.first(), "the new one is replayed the live connection")
    }

    @Test
    fun `a different process cannot take over a claim, streamed or not`() {
        registry.register(SURFACE, "plugin-a").accepted()

        val intruder = registry.register(SURFACE, "plugin-b")

        assertIs<SurfaceRegistration.Rejected>(intruder)
    }

    @Test
    fun `a streaming surface is not reclaimable even by its own process`() {
        // Otherwise a buggy plugin that re-registers mid-session would silently cut its own live stream.
        registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        assertIs<SurfaceRegistration.Rejected>(registry.register(SURFACE, PROCESS))
    }

    @Test
    fun `a closed surface refuses a stream, so a racing claim cannot report a dead plugin as connected`() {
        // openStream reads the map, then unregister/clear can remove and close the surface, and only then
        // does the claim land. Without the closed check that claim succeeded and published connected = true
        // — and nothing took it back, because events() completes at once over the closed channel and the
        // compensating close() has already run. The component would sit reading *connected* forever.
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertTrue(registry.unregister(SURFACE))

        assertFalse(surface.claimStream(), "a closed surface has no stream to give")
        assertFalse(
            host.connections.any { it },
            "and must never announce a connection it cannot honour, got ${host.connections}",
        )
    }

    @Test
    fun `a component that opens after its plugin died gets no stale tree`() {
        // The other side of close()'s "keep the last tree": a component already attached keeps what it
        // rendered, but one opening later has nothing replayed to it. A tree from a process that died some
        // time ago is stale, and showing it as though it were live would be the worse of the two.
        val gone = registry.register(SURFACE, PROCESS).accepted()
        gone.pushTree(tree("from-the-dead-plugin"))
        registry.closeStream(gone)

        val host = RecordingHost()
        registry.attach(SURFACE, host)

        assertTrue(host.trees.isEmpty())
        assertEquals(listOf(false), host.connections)
    }

    @Test
    fun `clear closes every surface and reports the disconnection`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        registry.clear()

        assertNull(registry.surfaceOf(SURFACE))
        assertFalse(surface.emit(textChange("after-clear")))
        assertEquals(listOf(false, true, false), host.connections)
    }

    @Test
    fun `a registration's descriptor is retained on the surface`() {
        val descriptor =
            RemoteUiSurfaceDescriptor(
                surfaceType = "panel",
                displayName = "Inbox",
                iconName = "mail",
                defaultSlot = "left.top.top",
                wantsKeys = true,
            )

        val surface = registry.register(SURFACE, PROCESS, descriptor).accepted()

        assertEquals(descriptor, surface.descriptor)
    }

    @Test
    fun `an attached component follows a plugin across a crash and respawn`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val crashed = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        registry.closeStream(crashed)

        val respawned = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        respawned.pushTree(tree("after-respawn"))

        assertEquals(listOf("after-respawn"), host.trees.map { it.label() })
        assertEquals(listOf(false, true, false, true), host.connections)
    }

    @Test
    fun `an event emitted with no plugin behind the surface is reported undeliverable, not thrown`() {
        assertFalse(registry.emit(SURFACE, textChange("nobody-home")))
    }

    @Test
    fun `an event emitted after the surface closes is dropped`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertTrue(registry.emit(SURFACE, textChange("in-time")))

        assertTrue(registry.unregister(SURFACE))

        assertFalse(registry.emit(SURFACE, textChange("too-late")))
        assertFalse(surface.emit(textChange("too-late-direct")), "a closed surface refuses directly too")
        assertNull(registry.surfaceOf(SURFACE))
    }

    @Test
    fun `a graceful shutdown reports the disconnection once, not once per teardown path`() {
        // UnregisterUI closes the surface, which ends the event queue, which ends the StreamUI call, whose
        // own teardown closes the surface again. The component must not be told twice.
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        assertTrue(registry.unregister(SURFACE))
        registry.closeStream(surface)

        assertEquals(listOf(false, true, false), host.connections)
    }

    @Test
    fun `detaching is scoped to the component that owns the attachment`() {
        val replaced = RecordingHost()
        val current = RecordingHost()
        registry.attach(SURFACE, replaced)
        registry.attach(SURFACE, current)

        // A component disposed after its successor attached must not take the successor's slot with it.
        registry.detach(SURFACE, replaced)
        registry.register(SURFACE, PROCESS).accepted().pushTree(tree("still-delivered"))

        assertEquals(listOf("still-delivered"), current.trees.map { it.label() })
        assertTrue(replaced.trees.isEmpty())
    }

    @Test
    fun `a plugin that stops reading sheds the oldest events and keeps the newest in order`() =
        runBlocking {
            // Bounded, not unlimited: the reader is in another process and can stop reading, and the
            // queue lives in the host. Shedding from the head keeps last-write-wins pointing at the
            // current value — dropping the newest would leave the plugin holding a stale one forever.
            val surface = registry.register(SURFACE, PROCESS).accepted()
            val overflow = 5
            val sent = (1..RemoteUiSurface.OUTGOING_BUFFER + overflow).map { "value-$it" }

            sent.forEach { value -> assertTrue(surface.emit(textChange(value))) }

            assertEquals(overflow.toLong(), surface.shedEventCount)
            val drained = surface.events().take(RemoteUiSurface.OUTGOING_BUFFER).toList()
            assertEquals(sent.drop(overflow), drained.map { it.textChange.newValue })
        }

    @Test
    fun `concurrent registrations for one id produce exactly one winner`() =
        runBlocking {
            // The reclaim path is a compare-and-set precisely because this can happen; the retry loop it
            // needs was otherwise untested. Every caller must either win or be told who holds the id, and
            // the registry must end up agreeing with exactly one of them.
            val attempts = 32
            val outcomes =
                (1..attempts)
                    .map { async(Dispatchers.Default) { registry.register(SURFACE, PROCESS) } }
                    .awaitAll()

            val accepted = outcomes.filterIsInstance<SurfaceRegistration.Accepted>()
            assertTrue(accepted.isNotEmpty(), "someone must win")
            assertEquals(attempts, outcomes.size, "no caller may be left without an outcome")
            val live = registry.surfaceOf(SURFACE)
            assertNotNull(live)
            assertEquals(1, accepted.count { it.surface === live }, "the registry must agree with one winner")
            // Everyone who lost lost to this plugin, and every surface that is not the live one is closed.
            outcomes.filterIsInstance<SurfaceRegistration.Rejected>().forEach {
                assertContains(it.reason, PROCESS)
            }
            accepted.filter { it.surface !== live }.forEach {
                assertFalse(it.surface.emit(textChange("loser")), "a superseded surface must be closed")
            }
        }

    @Test
    fun `draining concurrently with a burst of events leaves the queue accounting sane`() =
        runBlocking {
            // shedEventCount is documented as approximate under a concurrent drain; "approximate" must
            // still mean non-negative and never more than what was sent.
            val surface = registry.register(SURFACE, PROCESS).accepted()
            val sent = RemoteUiSurface.OUTGOING_BUFFER * 4

            val drained =
                coroutineScope {
                    val collector =
                        async(Dispatchers.Default) {
                            surface
                                .events()
                                .take(RemoteUiSurface.OUTGOING_BUFFER)
                                .toList()
                                .size
                        }
                    launch(Dispatchers.Default) {
                        repeat(sent) { surface.emit(textChange("value-$it")) }
                    }
                    collector.await()
                }

            assertEquals(RemoteUiSurface.OUTGOING_BUFFER, drained)
            assertTrue(surface.shedEventCount >= 0, "the counter must never go negative")
            assertTrue(surface.shedEventCount <= sent, "nor exceed what was sent, got ${surface.shedEventCount}")
        }

    @Test
    fun `a full tree replaces the surface's tree`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()

        surface.applyUpdate(
            WidgetUpdate
                .newBuilder()
                .setSurfaceId(SURFACE)
                .setFullTree(tree("from-wire").toProto())
                .build(),
        )

        assertEquals("from-wire", surface.tree?.label())
        assertEquals(listOf("from-wire"), host.trees.map { it.label() })
    }

    @Test
    fun `a diff applies on top of the surface's current tree`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("before").copy(version = 4))

        surface.applyUpdate(
            WidgetUpdate
                .newBuilder()
                .setSurfaceId(SURFACE)
                .setDiff(
                    listOf<DiffOperation>(
                        DiffOperation.NodeUpdated(NODE, mapOf("label" to "after"), null),
                    ).toProtoDiff(baseVersion = 4, newVersion = 5),
                ).build(),
        )

        assertEquals("after", surface.tree?.label())
        assertEquals(5L, surface.tree?.version, "the sender's version must be kept so the next base check works")
        assertEquals(listOf("before", "after"), host.trees.map { it.label() })
    }

    @Test
    fun `a diff whose base version does not match keeps the local numbering so the next one trips too`() {
        // Adopting the sender's newVersion after a divergence would make every SUBSEQUENT base_version
        // check pass, and the surface would be permanently, invisibly wrong. "The next full tree repairs
        // it" is no comfort to a plugin that sends one full tree and then only diffs — the steady state.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("before").copy(version = 4))

        surface.applyUpdate(diffUpdate("after", baseVersion = 9, newVersion = 10))

        assertEquals("after", surface.tree?.label(), "still applied - refusing would freeze the surface")
        assertEquals(5L, surface.tree?.version, "but the sender's numbering is not adopted")
    }

    @Test
    fun `a diff carrying operations this build cannot decode keeps the local numbering`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("before").copy(version = 4))

        // A structural op that cannot be decoded cannot be reconstructed from its neighbours, so the tree
        // below is knowingly not the plugin's — and must not claim to be at the plugin's version.
        //
        // newVersion is deliberately far from base + 1: with newVersion = 5 on a base-4 tree both branches
        // produce 5 (the sender's number and the local increment coincide), so the assertion would pass
        // with the skipped-operations guard removed and prove nothing.
        val undecodable =
            diffUpdate("after", baseVersion = 4, newVersion = 9)
                .toBuilder()
                .apply { diffBuilder.addOperations(ProtoDiffOp.getDefaultInstance()) }
                .build()
        surface.applyUpdate(undecodable)

        assertEquals("after", surface.tree?.label())
        assertEquals(4L + 1, surface.tree?.version, "local increment, not the sender's newVersion of 9")
    }

    @Test
    fun `a reclaimed surface cannot publish over the one that replaced it`() {
        // claim() installs the replacement before closing what it reclaimed, and the publish lock is
        // per-instance — so without an ownership check a predecessor's close() could report "disconnected"
        // after its successor had already reported a live stream, with trees still arriving.
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val abandoned = registry.register(SURFACE, PROCESS).accepted()
        val replacement = registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))
        val connectionsAfterHandover = host.connections.toList()

        abandoned.pushTree(tree("from-the-dead-one"))

        assertTrue(host.trees.isEmpty(), "a replaced surface must not publish")
        assertEquals(connectionsAfterHandover, host.connections, "nor report the connection state")
        replacement.pushTree(tree("from-the-live-one"))
        assertEquals(listOf("from-the-live-one"), host.trees.map { it.label() })
    }

    @Test
    fun `a surface refuses a second event consumer`() {
        // Two consumers would each drain part of the queue, splitting one ordered event sequence.
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.events()

        val failure = assertFailsWith<IllegalStateException> { surface.events() }

        assertContains(failure.message.orEmpty(), SURFACE)
    }

    @Test
    fun `a diff arriving before any full tree is dropped rather than applied to nothing`() {
        val host = RecordingHost()
        registry.attach(SURFACE, host)
        val surface = registry.register(SURFACE, PROCESS).accepted()

        surface.applyUpdate(
            WidgetUpdate
                .newBuilder()
                .setSurfaceId(SURFACE)
                .setDiff(
                    listOf<DiffOperation>(DiffOperation.NodeRemoved(NODE)).toProtoDiff(baseVersion = 1, newVersion = 2),
                ).build(),
        )

        assertNull(surface.tree)
        assertTrue(host.trees.isEmpty(), "nothing to render, so nothing is published")
    }

    @Test
    fun `an update with neither a tree nor a diff leaves the surface alone`() {
        val surface = registry.register(SURFACE, PROCESS).accepted()
        surface.pushTree(tree("kept"))

        surface.applyUpdate(WidgetUpdate.newBuilder().setSurfaceId(SURFACE).build())

        assertEquals("kept", surface.tree?.label())
    }

    @Test
    fun `a stream for an unregistered surface is refused as recoverable`() {
        // Typed apart from AlreadyStreaming because the recoveries differ: this one is fixable by calling
        // RegisterUI, and the bridge turns the distinction into NOT_FOUND vs FAILED_PRECONDITION.
        val refused = assertIs<SurfaceStream.Unregistered>(registry.openStream("never-registered"))

        assertContains(refused.reason, "RegisterUI")
    }

    @Test
    fun `only one stream may hold a surface at a time`() {
        registry.register(SURFACE, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(SURFACE))

        val refused = assertIs<SurfaceStream.AlreadyStreaming>(registry.openStream(SURFACE))

        assertContains(refused.reason, "StreamUI")
    }

    @Test
    fun `unregistering a surface nobody registered is not an error`() {
        assertFalse(registry.unregister("never-registered"))
    }

    // ---- Helpers ----

    /**
     * Thread-safe on purpose: publication happens on whichever thread delivered the update, and the test
     * thread reads these without taking the surface's publish lock.
     */
    private class RecordingHost : RemoteUiSurfaceHost {
        val trees = CopyOnWriteArrayList<WidgetTree>()
        val connections = CopyOnWriteArrayList<Boolean>()
        val capabilities = CopyOnWriteArrayList<Boolean>()

        override fun onKeyCapabilityChanged(wantsKeys: Boolean) {
            capabilities += wantsKeys
        }

        override fun onTreeUpdated(tree: WidgetTree) {
            trees += tree
        }

        override fun onConnectionChanged(connected: Boolean) {
            connections += connected
        }
    }

    private fun SurfaceRegistration.accepted(): RemoteUiSurface = assertIs<SurfaceRegistration.Accepted>(this).surface

    private fun tree(label: String): WidgetTree =
        WidgetTree(
            rootId = NODE,
            nodes = mapOf(NODE to WidgetNode(NODE, WidgetType.TEXT, mapOf("label" to label))),
        )

    private fun WidgetTree.label(): String? = nodes[NODE]?.properties?.get("label")

    private fun diffUpdate(
        label: String,
        baseVersion: Long,
        newVersion: Long,
    ): WidgetUpdate =
        WidgetUpdate
            .newBuilder()
            .setSurfaceId(SURFACE)
            .setDiff(
                listOf<DiffOperation>(
                    DiffOperation.NodeUpdated(NODE, mapOf("label" to label), null),
                ).toProtoDiff(baseVersion, newVersion),
            ).build()

    private fun textChange(value: String): UIEvent =
        UIEvent
            .newBuilder()
            .setSurfaceId(SURFACE)
            .setTargetNodeId(NODE)
            .setTextChange(TextChangeEvent.newBuilder().setNewValue(value))
            .build()

    private companion object {
        const val SURFACE = "panel-1"
        const val PROCESS = "plugin-a"
        const val NODE = "node-1"
    }
}
