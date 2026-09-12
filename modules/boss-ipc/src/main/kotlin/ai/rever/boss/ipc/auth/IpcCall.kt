package ai.rever.boss.ipc.auth

import io.grpc.Status

/** Read at the side effect or stream emission, so a previously admitted call cannot retain revoked access. */
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
