package ai.rever.boss.ipc.auth

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

/**
 * Header a client attaches its spawn-time token under.
 *
 * Not a bearer token in the HTTP sense: it never leaves the local host, and it grants nothing by itself
 * beyond "the kernel believes this call came from the process it spawned under this token". Plain ASCII,
 * not `-bin`: the value is already URL-safe base64 from [ProcessAuthRegistry.issueToken].
 */
val PROCESS_TOKEN_METADATA_KEY: Metadata.Key<String> =
    Metadata.Key.of("x-boss-process-token", Metadata.ASCII_STRING_MARSHALLER)

/**
 * The identity [ProcessAuthServerInterceptor] verified for the current call, if any.
 *
 * Absent - not an error - when the caller sent no token, or one [ProcessAuthRegistry] does not recognise;
 * see that class's doc for why that is the expected case during rollout rather than a fault to reject on.
 *
 * Read this at the very top of an RPC method, before anything that can suspend or hand control to another
 * coroutine, and capture it into a local value rather than re-reading it later. `io.grpc.Context` is
 * attached per-call by [Contexts.interceptCall], which guarantees it is current on the thread grpc-kotlin
 * invokes the method body on - it does not guarantee anything about whatever thread a coroutine launched
 * from inside that body, or resumed after a suspension point, happens to run on. A plain captured value
 * survives that; a lazy re-read of this key does not.
 */
val AUTHENTICATED_PROCESS_ID: Context.Key<String> = Context.key("boss-authenticated-process-id")

/**
 * Server-side half of process authentication: resolves the caller's token to a verified process id.
 *
 * Install on the kernel's `BossIpcServer` - the "single IPC server every plugin connects to" that
 * `RemoteUiSurfaceRegistry` refers to. Each individual process's own server, for calls addressed to it
 * directly, is out of scope for this: see the PR this shipped with for why that is a deliberate first
 * step rather than an oversight.
 *
 * Unauthenticated calls are not rejected here - a missing or unrecognised token simply leaves
 * [AUTHENTICATED_PROCESS_ID] unset for that call, and it is up to each RPC handler to decide what that
 * means for it. Rejecting centrally would also reject every call this has not been rolled out to yet.
 */
class ProcessAuthServerInterceptor(
    private val registry: ProcessAuthRegistry,
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val processId = headers.get(PROCESS_TOKEN_METADATA_KEY)?.let(registry::processIdFor)
        val context =
            if (processId != null) {
                Context.current().withValue(AUTHENTICATED_PROCESS_ID, processId)
            } else {
                Context.current()
            }
        // Contexts.interceptCall, not context.attach(): it also wraps the returned Listener so this
        // Context - not whatever was current before this call - is current for every one of that
        // listener's callbacks, which is what makes it visible to the coroutine grpc-kotlin launches from
        // onHalfClose/onMessage/onReady to actually run the handler.
        return Contexts.interceptCall(context, call, headers, next)
    }
}

/**
 * Client-side half: attaches this process's spawn-time token to every call made on the channel it wraps.
 *
 * [token] is the whole point - a value only the legitimate process holds, delivered once by
 * `ProcessSpawner` the same way `BOSS_PROCESS_ID` is - so build one of these per channel to the kernel,
 * not per call.
 */
class ProcessAuthClientInterceptor(
    private val token: String,
) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                headers.put(PROCESS_TOKEN_METADATA_KEY, token)
                super.start(responseListener, headers)
            }
        }
}
