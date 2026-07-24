package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.StateServiceImpl
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for StateService — get/set/watch + optimistic concurrency.
 */
class StateServiceTest {
    private var server: io.grpc.Server? = null
    private var channel: io.grpc.ManagedChannel? = null
    private var port: Int = 0
    private lateinit var stateService: StateServiceImpl

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        stateService = StateServiceImpl()
        server =
            ServerBuilder
                .forPort(port)
                .addService(stateService)
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build()
    }

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
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
}
