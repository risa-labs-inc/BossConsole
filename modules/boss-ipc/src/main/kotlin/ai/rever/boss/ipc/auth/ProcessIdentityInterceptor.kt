package ai.rever.boss.ipc.auth

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.kotlin.CoroutineContextServerInterceptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Authenticates every RPC, including services registered after the server starts. */
class ProcessIdentityInterceptor(
    private val registry: ProcessTokenRegistry,
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val token = headers.get(PROCESS_TOKEN_METADATA_KEY)
        val principal = registry.principalFor(token)
        if (token == null || principal == null) {
            call.close(UNAUTHENTICATED, Metadata())
            return object : ServerCall.Listener<ReqT>() { }
        }
        val context =
            Context
                .current()
                .withValue(AUTHENTICATED_PROCESS_ID, principal.processId)
                .withValue(CURRENT_IDENTITY, { registry.identityFor(token) })
                .withValue(CURRENT_PRINCIPAL, { registry.principalFor(token) })
                .withCancellation()
        val finished = AtomicBoolean()
        val callJob = SupervisorJob()
        val subscription = AtomicReference<AutoCloseable?>()
        val guardedCall =
            object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                override fun close(
                    status: Status,
                    trailers: Metadata,
                ) {
                    if (finished.compareAndSet(false, true)) {
                        subscription.getAndSet(null)?.close()
                        super.close(status, trailers)
                    }
                }

                override fun sendMessage(message: RespT) {
                    if (registry.principalFor(token) == null) {
                        close(UNAUTHENTICATED, Metadata())
                    } else if (!finished.get()) {
                        super.sendMessage(message)
                    }
                }
            }
        val registration =
            registry.onRevoked(token) {
                guardedCall.close(UNAUTHENTICATED, Metadata())
                callJob.cancel()
                context.cancel(UNAUTHENTICATED.asRuntimeException())
            }
        subscription.set(registration)
        if (finished.get()) subscription.getAndSet(null)?.close()
        var bound = false
        try {
            // Cancelling a gRPC Context alone does not cancel grpc-kotlin's RPC coroutine.
            val cancellableHandler =
                ServerCallHandler<ReqT, RespT> { incoming, metadata ->
                    CallCoroutineContext(callJob).interceptCall(incoming, metadata, next)
                }
            val listener = Contexts.interceptCall(context, guardedCall, headers, cancellableHandler)
            bound = true
            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
                override fun onCancel() {
                    try {
                        super.onCancel()
                    } finally {
                        subscription.getAndSet(null)?.close()
                        callJob.cancel()
                        context.cancel(null)
                    }
                }

                override fun onComplete() {
                    try {
                        super.onComplete()
                    } finally {
                        subscription.getAndSet(null)?.close()
                        callJob.complete()
                        context.cancel(null)
                    }
                }
            }
        } finally {
            if (!bound) {
                subscription.getAndSet(null)?.close()
                callJob.cancel()
                context.cancel(null)
            }
        }
    }

    companion object {
        private val UNAUTHENTICATED = Status.UNAUTHENTICATED.withDescription("A current IPC credential is required")
        val CURRENT_IDENTITY: Context.Key<() -> String?> = Context.key("boss-current-process-identity")
        val CURRENT_PRINCIPAL: Context.Key<() -> ProcessIdentity?> = Context.key("boss-current-process-principal")
        val PROCESS_TOKEN_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("boss-process-token", Metadata.ASCII_STRING_MARSHALLER)
        val AUTHENTICATED_PROCESS_ID: Context.Key<String> = Context.key("boss-authenticated-process-id")
    }
}

private class CallCoroutineContext(
    private val job: Job,
) : CoroutineContextServerInterceptor() {
    override fun coroutineContext(
        call: ServerCall<*, *>,
        headers: Metadata,
    ): CoroutineContext = job
}
