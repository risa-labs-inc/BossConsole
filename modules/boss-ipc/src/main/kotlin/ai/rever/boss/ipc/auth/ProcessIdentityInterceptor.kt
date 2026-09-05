package ai.rever.boss.ipc.auth

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

/**
 * Establishes a verified caller identity for every call on a kernel IPC server, independently of
 * anything the call's own request body claims (BossConsole#53).
 *
 * Reads the [PROCESS_TOKEN_METADATA_KEY] header, resolves it through [registry], and — when it names
 * a real, currently-issued credential — publishes the owning process id under [AUTHENTICATED_PROCESS_ID]
 * for the rest of the call to read via [Context]. A missing or unrecognised token leaves that key unset
 * rather than failing the call outright: most of the kernel's IPC surface (fourteen other service
 * bridges as of writing, none of them authenticated today) does not check identity at all, and
 * rejecting here would be authentication for services that never asked for it — the same reason a
 * missing/invalid token must not weaken or change behaviour for those. Whether the *absence* of an
 * identity is acceptable is each service's own call; [ai.rever.boss.kernel.services.PluginUIServiceBridge]
 * is the one that currently makes it.
 */
class ProcessIdentityInterceptor(
    private val registry: ProcessTokenRegistry,
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val identity = registry.identityFor(headers.get(PROCESS_TOKEN_METADATA_KEY))
        val context =
            if (identity != null) {
                Context.current().withValue(AUTHENTICATED_PROCESS_ID, identity)
            } else {
                Context.current()
            }
        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        /**
         * Wire name of the credential header. ASCII marshaller: the value is an opaque hex token, not
         * binary data that needs one.
         */
        val PROCESS_TOKEN_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("boss-process-token", Metadata.ASCII_STRING_MARSHALLER)

        /**
         * The process id this call's credential was issued to, or unset when the call presented none —
         * missing, unknown, or from a process the registry no longer recognises (a stale token after a
         * respawn). Read with `AUTHENTICATED_PROCESS_ID.get()` from inside a call this interceptor
         * scoped; unset reads back as null.
         */
        val AUTHENTICATED_PROCESS_ID: Context.Key<String> = Context.key("boss-authenticated-process-id")
    }
}
