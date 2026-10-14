package ai.rever.boss.ipc.auth

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthGrpc
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IpcTlsIdentityTest {
    @Test
    fun `restored endpoint key accepts its pinned client and credential`() {
        val identity = IpcTlsIdentity.create()
        val restored = IpcTlsIdentity.restore(identity.certificateBase64, identity.privateKeyBase64())
        exchange(restored, identity.certificateBase64, shouldSucceed = true)
    }

    @Test
    fun `rogue listener receives no credential or RPC despite same endpoint hostname`() {
        val intended = IpcTlsIdentity.create()
        val rogue = IpcTlsIdentity.create()
        exchange(rogue, intended.certificateBase64, shouldSucceed = false)
    }

    @Test
    fun `oversized or malformed endpoint material is refused`() {
        assertFailsWith<IllegalArgumentException> { IpcTlsIdentity.clientContext("a".repeat(16_385)) }
        assertFailsWith<IllegalArgumentException> { IpcTlsIdentity.clientContext("not base64!") }
    }

    private fun exchange(
        identity: IpcTlsIdentity,
        trustedCertificate: String,
        shouldSucceed: Boolean,
    ) {
        val calls = AtomicInteger()
        val interceptor =
            object : ServerInterceptor {
                override fun <ReqT, RespT> interceptCall(
                    call: ServerCall<ReqT, RespT>,
                    headers: Metadata,
                    next: ServerCallHandler<ReqT, RespT>,
                ): ServerCall.Listener<ReqT> {
                    val credential = headers.get(ProcessIdentityInterceptor.PROCESS_TOKEN_METADATA_KEY)
                    assertEquals("synthetic-private-credential", credential)
                    calls.incrementAndGet()
                    return next.startCall(call, headers)
                }
            }
        val server =
            NettyServerBuilder
                .forAddress(InetSocketAddress("127.0.0.1", 0))
                .sslContext(identity.serverContext())
                .intercept(interceptor)
                .addService(HealthStatusManager().healthService)
                .build()
                .start()
        val channel =
            NettyChannelBuilder
                .forAddress("127.0.0.1", server.port)
                .sslContext(IpcTlsIdentity.clientContext(trustedCertificate))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
                .intercept(ProcessTokenClientInterceptor("synthetic-private-credential"))
                .build()
        try {
            val stub = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            if (shouldSucceed) {
                stub.check(HealthCheckRequest.getDefaultInstance())
                assertEquals(1, calls.get())
            } else {
                val request = HealthCheckRequest.getDefaultInstance()
                val failure = assertFailsWith<StatusRuntimeException> { stub.check(request) }
                assertEquals(Status.Code.UNAVAILABLE, failure.status.code)
                assertEquals(0, calls.get())
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
