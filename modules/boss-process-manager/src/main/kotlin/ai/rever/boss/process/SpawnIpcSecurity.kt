package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry

/** Owns the credentials for one spawn attempt, including its eventual exit callback. */
internal class SpawnIpcSecurity private constructor(
    private val registry: ProcessTokenRegistry,
    private val processId: String,
    private val processToken: String,
    private val environment: Map<String, String>,
    val client: BossIpcClient,
) {
    fun install(target: MutableMap<String, String>) {
        target.putAll(environment)
    }

    fun revoke() {
        registry.revokeIfCurrent(processId, processToken)
        client.shutdown(0)
    }

    companion object {
        fun create(
            registry: ProcessTokenRegistry?,
            kernelIdentity: IpcTlsIdentity?,
            config: ProcessConfig,
            address: String,
        ): SpawnIpcSecurity? {
            require((registry == null) == (kernelIdentity == null)) {
                "Managed IPC processes require both a host credential registry and kernel TLS identity"
            }
            if (registry == null || kernelIdentity == null) return null
            val childIdentity = IpcTlsIdentity.create()
            val hostToken = ProcessTokenRegistry().issue("host", ProcessAuthority.HOST)
            val authority =
                if (config.processType == ProcessType.ORCHESTRATOR) {
                    ProcessAuthority.SUPERVISOR
                } else {
                    ProcessAuthority.PROCESS
                }
            val client = BossIpcClient(address, IpcClientCredentials(childIdentity.certificateBase64, hostToken))
            val environment =
                mapOf(
                    IpcEnvironment.KERNEL_CERTIFICATE to kernelIdentity.certificateBase64,
                    IpcEnvironment.SERVER_CERTIFICATE to childIdentity.certificateBase64,
                    IpcEnvironment.SERVER_PRIVATE_KEY to childIdentity.privateKeyBase64(),
                    IpcEnvironment.HOST_TOKEN to hostToken,
                )
            val processToken = registry.issue(config.processId, authority, address)
            return SpawnIpcSecurity(
                registry,
                config.processId,
                processToken,
                environment + (IpcEnvironment.PROCESS_TOKEN to processToken),
                client,
            )
        }
    }
}
