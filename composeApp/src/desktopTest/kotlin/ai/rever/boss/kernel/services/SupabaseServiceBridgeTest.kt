package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.services.SupabaseRpcRequest
import ai.rever.boss.ipc.proto.services.SupabaseSelectRequest
import ai.rever.boss.ipc.proto.services.SupabaseServiceGrpcKt
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `SupabaseServiceBridge`, exercised over a real gRPC server with a real client - see
 * [PluginUIServiceBridgeTest]'s own KDoc for why a bridge is tested at the wire level rather than
 * by calling its methods directly in-process: only the wire path actually goes through
 * [ProcessIdentityInterceptor], which is what BossConsole#53 adds here.
 *
 * The assertion that matters for every refusal is not just "PERMISSION_DENIED came back" but that
 * [FakeSupabaseDataProvider] - standing in for the signed-in user's real, RLS-scoped Postgrest
 * session, per the class KDoc on [SupabaseServiceBridge] - was never touched. `rpc` is the more
 * dangerous of the two calls (it can reach `create_secret`/`update_secret`, per AGENTS.md's
 * "Decoding Supabase payloads"), so it gets its own explicit "never invoked" proof rather than
 * relying on `select`'s.
 */
class SupabaseServiceBridgeTest {
    private lateinit var provider: FakeSupabaseDataProvider
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var authenticated: SupabaseServiceGrpcKt.SupabaseServiceCoroutineStub
    private val extraChannels = mutableListOf<ManagedChannel>()

    @BeforeTest
    fun setUp() {
        provider = FakeSupabaseDataProvider()
        tokenRegistry = ProcessTokenRegistry()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(SupabaseServiceBridge(provider))
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        authenticated = SupabaseServiceGrpcKt.SupabaseServiceCoroutineStub(channel)
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

    private fun anonymous(): SupabaseServiceGrpcKt.SupabaseServiceCoroutineStub {
        val unauthenticatedChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        extraChannels += unauthenticatedChannel
        return SupabaseServiceGrpcKt.SupabaseServiceCoroutineStub(unauthenticatedChannel)
    }

    @Test
    fun `select with no credential is refused and never reaches the provider`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymous().select(
                        SupabaseSelectRequest
                            .newBuilder()
                            .setTable("secrets")
                            .setColumns("*")
                            .build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.selectCalls.isEmpty())
        }

    @Test
    fun `rpc with no credential is refused and never reaches the provider`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    anonymous().rpc(
                        SupabaseRpcRequest
                            .newBuilder()
                            .setFunction("create_secret")
                            .setParametersJson("""{"password":"hunter2"}""")
                            .build(),
                    )
                }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
            assertTrue(provider.rpcCalls.isEmpty(), "create_secret must never have reached the provider")
        }

    @Test
    fun `an authenticated caller's select reaches the provider with its arguments intact`() =
        runBlocking {
            val response =
                authenticated.select(
                    SupabaseSelectRequest
                        .newBuilder()
                        .setTable("secrets")
                        .setColumns("id,name")
                        .build(),
                )

            assertTrue(response.success)
            assertEquals(listOf("secrets" to "id,name"), provider.selectCalls)
        }

    @Test
    fun `an authenticated caller's rpc reaches the provider with its arguments intact`() =
        runBlocking {
            val response =
                authenticated.rpc(
                    SupabaseRpcRequest
                        .newBuilder()
                        .setFunction("create_secret")
                        .setParametersJson("""{"password":"hunter2"}""")
                        .build(),
                )

            assertTrue(response.success)
            assertEquals(listOf("create_secret" to """{"password":"hunter2"}"""), provider.rpcCalls)
        }

    /** Records every call it actually receives, standing in for the real RLS-scoped session. */
    private class FakeSupabaseDataProvider : SupabaseDataProvider {
        val selectCalls = mutableListOf<Pair<String, String>>()
        val rpcCalls = mutableListOf<Pair<String, String>>()

        override suspend fun select(
            table: String,
            columns: String,
            filters: List<QueryFilter>,
            range: QueryRange?,
        ): Result<String> {
            selectCalls += table to columns
            return Result.success("[]")
        }

        override suspend fun rpc(
            function: String,
            parameters: String,
        ): Result<String> {
            rpcCalls += function to parameters
            return Result.success("{}")
        }
    }

    private companion object {
        const val CALLER = "test-caller"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
