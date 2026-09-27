package ai.rever.boss.ipc.auth

import io.grpc.Status

/**
 * Read at the side effect or stream emission, so a previously admitted call cannot retain revoked access.
 *
 * Exception: `TerminalServiceImpl.streamOutput` checks ownership once, when the stream starts, and not on
 * every chunk. That is safe only because both sides of its check are fixed for the life of the call: a
 * [ProcessIdentity] never changes under a live token (re-issuing a process id mints a new token and revokes
 * the old one), and a terminal session's owner is set at creation. So only revocation can change the
 * answer, and [ProcessIdentityInterceptor] handles revocation for every call - its revocation listener closes
 * the call as UNAUTHENTICATED right away, and its `sendMessage` guard refuses any message once the token no
 * longer resolves. Do not remove or weaken that interceptor safety net, and do not copy this exception to
 * streams whose authorization can change mid-stream (for example `StateServiceImpl.watchState`, where entry
 * ownership and sharing can change). `TerminalOwnershipTest` pins that revocation still closes an active or
 * idle terminal output stream.
 */
object IpcCall {
    fun current(): ProcessIdentity =
        ProcessIdentityInterceptor.CURRENT_PRINCIPAL.get()?.invoke()
            ?: throw Status.UNAUTHENTICATED.withDescription("A current IPC credential is required").asRuntimeException()

    fun requireHost(): ProcessIdentity = current().also { requirePermission(it.authority == ProcessAuthority.HOST) }

    fun requireOwnProcess(processId: String): ProcessIdentity {
        val caller = current()
        requirePermission(caller.processId == processId)
        return caller
    }

    fun requireProcessControl(processId: String): ProcessIdentity =
        current().also { requirePermission(it.processId == processId || it.authority != ProcessAuthority.PROCESS) }

    fun requireOwner(instanceId: String): ProcessIdentity =
        current().also { requirePermission(it.instanceId == instanceId || it.authority == ProcessAuthority.HOST) }

    fun requirePermission(allowed: Boolean) {
        if (!allowed) {
            val denied = Status.PERMISSION_DENIED.withDescription("The caller is not authorized for this operation")
            throw denied.asRuntimeException()
        }
    }
}
