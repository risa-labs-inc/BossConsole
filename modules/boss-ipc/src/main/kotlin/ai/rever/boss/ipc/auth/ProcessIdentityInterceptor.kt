package ai.rever.boss.ipc.auth

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

/**
 * The kernel-issued identity of one credential generation. The token itself remains private to the
 * transport; the generation is used to bind a registration to the credential that established it.
 */
data class ProcessCredential(
    val processId: String,
    val generation: Long,
)

/**
 * Establishes a verified caller identity for every call on a kernel IPC server, independently of
 * anything the call's own request body claims (BossConsole#53).
 *
 * Reads the [PROCESS_TOKEN_METADATA_KEY] header, resolves it through [registry], and — when it names
 * a real, currently-issued credential — publishes the owning process id under [AUTHENTICATED_PROCESS_ID]
 * for the rest of the call to read via [Context]. A missing or unrecognised token leaves that key unset
 * rather than failing the call outright. Each service decides whether identity is required;
 * the interceptor itself does not authenticate the whole IPC surface. Inspect each service's
 * guards when adding an RPC rather than inferring authorization from this interceptor.
 * BossConsole#53 tracks closing the remaining unguarded services.
 */
class ProcessIdentityInterceptor(
    private val registry: ProcessTokenRegistry,
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val token = headers.get(PROCESS_TOKEN_METADATA_KEY)
        val credential = registry.credentialFor(token)
        val context =
            Context
                .current()
                .withValue(AUTHENTICATED_PROCESS_ID, credential?.processId)
                .withValue(AUTHENTICATED_CREDENTIAL, credential)
                .withValue(CURRENT_IDENTITY, { registry.identityFor(token) })
                .withValue(CURRENT_CREDENTIAL, { registry.credentialFor(token) })
        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        /** Revalidate at stream binding, since a call may wait across token revocation. */
        val CURRENT_IDENTITY: Context.Key<() -> String?> = Context.key("boss-current-process-identity")

        /** Revalidate both process identity and credential generation for long-lived calls. */
        val CURRENT_CREDENTIAL: Context.Key<() -> ProcessCredential?> =
            Context.key("boss-current-process-credential")

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

        /** Credential snapshot for unary calls and for services that need generation binding. */
        val AUTHENTICATED_CREDENTIAL: Context.Key<ProcessCredential> =
            Context.key("boss-authenticated-process-credential")
    }
}
