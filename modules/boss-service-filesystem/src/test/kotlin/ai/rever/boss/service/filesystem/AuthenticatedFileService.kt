package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import io.grpc.BindableService
import io.grpc.ManagedChannel

/**
 * Stands the production authenticated transport up in front of [FileSystemServiceImpl], with
 * explicit callers: `channelFor()` issues a token into the same registry the server reads, so a
 * test names exactly who is calling. The authorization under test lives in the handler, between
 * the interceptor's authentication and the filesystem, and can only be observed through a channel
 * that actually carries a credential.
 */
class AuthenticatedFileService(
    vararg services: BindableService,
) : AutoCloseable {
    val registry = ProcessTokenRegistry()
    private val identity = IpcTlsIdentity.create()
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, identity)
    private val channels = mutableListOf<ManagedChannel>()

    init {
        services.forEach(server::addService)
        server.start()
    }

    /** A caller with the process authority a plugin child would hold - authenticated, not host. */
    fun channelFor(
        processId: String,
        authority: ProcessAuthority = ProcessAuthority.PROCESS,
    ): ManagedChannel {
        val token = registry.issue(processId, authority)
        return BossIpcClient(
            "tcp://127.0.0.1:${server.port}",
            IpcClientCredentials(identity.certificateBase64, token),
        ).channel.also { channels.add(it) }
    }

    override fun close() {
        channels.forEach { it.shutdownNow() }
        server.stop(2_000)
    }

    companion object {
        fun stub(channel: ManagedChannel): FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub =
            FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(channel)
    }
}
