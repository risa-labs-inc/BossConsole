package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import java.util.concurrent.TimeUnit

/** Exercises the production authenticated transport with fresh endpoint keys and explicit callers. */
class IpcTestServer(
    vararg services: BindableService,
) : AutoCloseable {
    val registry = ProcessTokenRegistry()
    val identity = IpcTlsIdentity.create()
    val server = BossIpcServer("tcp://127.0.0.1:0", registry, identity)
    private val channels = mutableListOf<ManagedChannel>()

    init {
        services.forEach(server::addService)
        server.start()
    }

    fun channelFor(
        processId: String,
        expectedAddress: String? = null,
        authority: ProcessAuthority = ProcessAuthority.PROCESS,
    ): ManagedChannel {
        val token = registry.issue(processId, authority, expectedAddress)
        return BossIpcClient(
            "tcp://127.0.0.1:${server.port}",
            IpcClientCredentials(identity.certificateBase64, token),
        ).channel.also { channels.add(it) }
    }

    override fun close() {
        channels.forEach { it.shutdownNow().awaitTermination(5, TimeUnit.SECONDS) }
        server.stop(2_000)
    }

    /** Negative tests deliberately omit or corrupt authentication while retaining the correct TLS pin. */
    fun channelWithToken(token: String?): ManagedChannel {
        val builder =
            NettyChannelBuilder
                .forAddress("127.0.0.1", server.port)
                .sslContext(IpcTlsIdentity.clientContext(identity.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
        token?.let { builder.intercept(ProcessTokenClientInterceptor(it)) }
        return builder.build().also { channels.add(it) }
    }
}
