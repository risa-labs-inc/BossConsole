package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.ProcessAuthority
import io.grpc.Status
import io.grpc.StatusException

/**
 * Only the kernel may drive the filesystem service.
 *
 * Today the service's registry holds exactly one credential, the per-spawn host token the
 * kernel keeps ([ProcessTokenRegistry.forHostController]), so the boundary is already the
 * kernel by construction. This check is that boundary stated in code rather than left to
 * token-issuance hygiene: when a later change adds credentials to this registry - a plugin
 * token, a supervisor token - the new caller is refused here until relayed through the host,
 * which is the only place that knows which plugin is asking and what it was granted. Plugin
 * filesystem access arrives as host-relayed calls with grants enforced host-side, never as
 * direct service credentials.
 *
 * Kept beside [FileSystemServiceImpl] rather than inside it so the handler class stays within
 * its detekt function budget.
 */
internal fun requireKernelCaller() {
    val caller = IpcCall.current()
    if (caller.authority != ProcessAuthority.HOST) {
        throw StatusException(
            Status
                .PERMISSION_DENIED
                .withDescription(
                    "The file system service is only reachable through the host; " +
                        "caller ${caller.processId} holds ${caller.authority}",
                ),
        )
    }
}
