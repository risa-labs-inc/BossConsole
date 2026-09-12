package ai.rever.boss.service.filesystem

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/** Keep the actual server exception in CI reports; a client receives only UNKNOWN for unexpected IO. */
internal object RpcFailureDiagnostics : ServerInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> =
        next.startCall(
            object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                override fun close(
                    status: Status,
                    trailers: Metadata,
                ) {
                    if (status.code == Status.Code.UNKNOWN) status.cause?.printStackTrace()
                    super.close(status, trailers)
                }
            },
            headers,
        )
}
