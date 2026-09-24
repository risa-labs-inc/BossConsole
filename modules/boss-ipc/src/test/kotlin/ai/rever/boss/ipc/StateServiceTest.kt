package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.StateServiceImpl
import com.google.protobuf.ByteString
import io.grpc.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for StateService — get/set/watch + optimistic concurrency.
 */
class StateServiceTest {
    private lateinit var testServer: IpcTestServer
    private var channel: io.grpc.ManagedChannel? = null
    private lateinit var stateService: StateServiceImpl

    @Before
    fun setUp() {
        stateService = StateServiceImpl()
        testServer = IpcTestServer(stateService)
        channel = testServer.channelFor("test-process")
    }

    @After
    fun tearDown() {
        testServer.close()
    }

    @Test
    fun `getState returns empty for unknown key`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

            val result = stub.getState(StateKey.newBuilder().setKey("nonexistent.key").build())

            assertEquals("nonexistent.key", result.key)
            assertEquals(0L, result.version, "Version should be 0 for unknown key")
        }

    @Test
    fun `setState then getState returns stored value`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

            val payload = ByteString.copyFromUtf8("test-value-123")
            stub.setState(
                StateUpdate
                    .newBuilder()
                    .setKey("test.state.key")
                    .setValue(payload)
                    .setValueType("string")
                    .setSourceProcess("test-process")
                    .build(),
            )

            val result = stub.getState(StateKey.newBuilder().setKey("test.state.key").build())

            assertEquals("test.state.key", result.key)
            assertEquals(payload, result.value)
            assertTrue(result.version > 0, "Version should be incremented")
            assertEquals(1, stateService.stateCount)
        }

    @Test
    fun `watchState emits current value then updates`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

                // Set initial value
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("watch.test.key")
                        .setValue(ByteString.copyFromUtf8("initial"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .build(),
                )

                // Watch should emit initial value first
                val firstValue = stub.watchState(StateKey.newBuilder().setKey("watch.test.key").build()).first()

                assertEquals("initial", firstValue.value.toStringUtf8())
            }
        }

    @Test
    fun `setState increments version on each update`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

            val v1 =
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("version.test.key")
                        .setValue(ByteString.copyFromUtf8("v1"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .build(),
                )

            val v2 =
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("version.test.key")
                        .setValue(ByteString.copyFromUtf8("v2"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .build(),
                )

            assertTrue(v2.version > v1.version, "Version should increase on each update")
        }

    @Test
    fun `setState with wrong expectedVersion returns current without updating`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

            // Set initial
            val initial =
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("conflict.test.key")
                        .setValue(ByteString.copyFromUtf8("original"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .build(),
                )

            // Try to update with wrong expected version
            val conflicted =
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey("conflict.test.key")
                        .setValue(ByteString.copyFromUtf8("conflicted"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .setExpectedVersion(999L) // wrong version
                        .build(),
                )

            // Should return current value without updating
            assertEquals("original", conflicted.value.toStringUtf8())
            assertEquals(initial.version, conflicted.version, "Version should not change on conflict")
        }

    @Test
    fun `listStateKeys returns all stored keys`() =
        runBlocking {
            val stub = StateServiceGrpcKt.StateServiceCoroutineStub(channel!!)

            val keys = listOf("list.key1", "list.key2", "list.key3")
            keys.forEach { key ->
                stub.setState(
                    StateUpdate
                        .newBuilder()
                        .setKey(key)
                        .setValue(ByteString.copyFromUtf8("value"))
                        .setValueType("string")
                        .setSourceProcess("test")
                        .build(),
                )
            }

            val result = stub.listStateKeys(Empty.getDefaultInstance())
            val resultKeys = result.keysList.map { it.key }

            assertTrue(resultKeys.containsAll(keys), "All stored keys should be listed")
        }

    @Test
    fun `watchState delivers an update that lands after the snapshot`() =
        runBlocking {
            withTimeout(10_000) {
                withHostIdentity {
                    stateService.setState(stateUpdate("watch.gap.key", "initial"))
                    val received = mutableListOf<StateValue>()
                    val snapshotDelivered = CompletableDeferred<Unit>()
                    val releaseWatcher = CompletableDeferred<Unit>()
                    val followUpDelivered = CompletableDeferred<Unit>()
                    val watcher =
                        launch {
                            stateService.watchState(stateKey("watch.gap.key")).collect { value ->
                                received.add(value)
                                if (received.size == 1) {
                                    snapshotDelivered.complete(Unit)
                                    // Hold the watcher before it can move past the
                                    // snapshot while the update below is emitted.
                                    releaseWatcher.await()
                                } else if (received.size == 2) {
                                    followUpDelivered.complete(Unit)
                                }
                            }
                        }
                    snapshotDelivered.await()
                    // The update lands after the snapshot was read and delivered, while
                    // the watcher is still held. A snapshot-then-subscribe order left this
                    // emit with no subscriber, and replay=0 drops it for good.
                    stateService.setState(stateUpdate("watch.gap.key", "updated"))
                    releaseWatcher.complete(Unit)
                    followUpDelivered.await()
                    watcher.cancel()
                    val values = received.map { it.value.toStringUtf8() }
                    assertEquals(listOf("initial", "updated"), values)
                    assertEquals(listOf(1L, 2L), received.map { it.version })
                }
            }
        }

    @Test
    fun `watchState emits the current value then every change without duplicates`() =
        runBlocking {
            withTimeout(10_000) {
                withHostIdentity {
                    stateService.setState(stateUpdate("watch.plain.key", "v1"))
                    val received = mutableListOf<StateValue>()
                    val snapshotDelivered = CompletableDeferred<Unit>()
                    val allChangesDelivered = CompletableDeferred<Unit>()
                    val watcher =
                        launch {
                            stateService.watchState(stateKey("watch.plain.key")).collect { value ->
                                received.add(value)
                                if (received.size == 1) snapshotDelivered.complete(Unit)
                                if (received.size == 3) allChangesDelivered.complete(Unit)
                            }
                        }
                    snapshotDelivered.await()
                    stateService.setState(stateUpdate("watch.plain.key", "v2"))
                    stateService.setState(stateUpdate("watch.plain.key", "v3"))
                    allChangesDelivered.await()
                    watcher.cancel()
                    val values = received.map { it.value.toStringUtf8() }
                    val versions = received.map { it.version }
                    assertEquals(listOf("v1", "v2", "v3"), values)
                    assertEquals(listOf(1L, 2L, 3L), versions)
                }
            }
        }

    @Test
    fun `watchState delivers a burst of rapid updates in order`() =
        runBlocking {
            withTimeout(10_000) {
                withHostIdentity {
                    stateService.setState(stateUpdate("watch.burst.key", "seed"))
                    val received = mutableListOf<StateValue>()
                    val snapshotDelivered = CompletableDeferred<Unit>()
                    val burstDelivered = CompletableDeferred<Unit>()
                    val updates = 25
                    val watcher =
                        launch {
                            stateService.watchState(stateKey("watch.burst.key")).collect { value ->
                                received.add(value)
                                if (received.size == 1) snapshotDelivered.complete(Unit)
                                if (received.size == updates + 1) burstDelivered.complete(Unit)
                            }
                        }
                    snapshotDelivered.await()
                    repeat(updates) { i ->
                        stateService.setState(stateUpdate("watch.burst.key", "v$i"))
                    }
                    burstDelivered.await()
                    watcher.cancel()
                    val expected = listOf("seed") + (0 until updates).map { "v$it" }
                    assertEquals(expected, received.map { it.value.toStringUtf8() })
                    assertEquals((1L..(updates + 1L)).toList(), received.map { it.version })
                }
            }
        }

    private suspend fun <T> withHostIdentity(block: suspend () -> T): T {
        val identity =
            ProcessIdentity(
                "state.watch.test",
                "state.watch.test.instance",
                ProcessAuthority.HOST,
                null,
            )
        val context =
            Context.current().withValue(ProcessIdentityInterceptor.CURRENT_PRINCIPAL) { identity }
        val previous = context.attach()
        return try {
            block()
        } finally {
            context.detach(previous)
        }
    }

    private fun stateUpdate(
        key: String,
        value: String,
    ): StateUpdate =
        StateUpdate
            .newBuilder()
            .setKey(key)
            .setValue(ByteString.copyFromUtf8(value))
            .setValueType("string")
            .setSourceProcess("state.watch.test")
            .build()

    private fun stateKey(key: String): StateKey = StateKey.newBuilder().setKey(key).build()
}
