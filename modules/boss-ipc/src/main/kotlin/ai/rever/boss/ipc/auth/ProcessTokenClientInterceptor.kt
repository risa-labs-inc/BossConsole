package ai.rever.boss.ipc.auth

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor

/**
 * Attaches this process's credential to every outgoing call on a kernel IPC channel.
 *
 * The client-side counterpart to [ProcessIdentityInterceptor]. [token] is never logged by this class,
 * and nothing here reads it back out once attached — see
 * [ai.rever.boss.ipc.ChildProcessBootstrap.processToken] for where a child process obtains it.
 */
class ProcessTokenClientInterceptor(
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
                headers.put(ProcessIdentityInterceptor.PROCESS_TOKEN_METADATA_KEY, token)
                super.start(responseListener, headers)
            }
        }
}
