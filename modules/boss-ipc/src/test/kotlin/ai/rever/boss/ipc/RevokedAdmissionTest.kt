package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RevokedAdmissionTest {
    @Test
    fun `revocation between lookup and subscription never dispatches the handler`() {
        val registry = ProcessTokenRegistry()
        val token = registry.issue("revoked")
        revokeAfterLookup(registry)
        val headers = Metadata().apply { put(ProcessIdentityInterceptor.PROCESS_TOKEN_METADATA_KEY, token) }
        val call = RecordingCall()
        var dispatched = false
        ProcessIdentityInterceptor(registry).interceptCall(
            call,
            headers,
            ServerCallHandler { _, _ ->
                dispatched = true
                object : ServerCall.Listener<String>() { }
            },
        )
        assertEquals(Status.Code.UNAUTHENTICATED, call.closed?.code)
        assertFalse(dispatched)
    }

    @Suppress("UNCHECKED_CAST")
    private fun revokeAfterLookup(registry: ProcessTokenRegistry) {
        // Force the real lookup/subscription interleaving without adding a production test hook.
        val field = ProcessTokenRegistry::class.java.getDeclaredField("credentials").apply { isAccessible = true }
        val entries = field.get(registry) as Map<String, Any>
        val once = AtomicBoolean()
        val racing =
            object : LinkedHashMap<String, Any>(entries) {
                override fun get(key: String): Any? {
                    val credential = super.get(key)
                    if (credential != null && once.compareAndSet(false, true)) registry.revoke("revoked")
                    return credential
                }
            }
        field.set(registry, racing)
    }

    private class RecordingCall : ServerCall<String, String>() {
        var closed: Status? = null

        override fun request(numMessages: Int) = Unit

        override fun sendHeaders(headers: Metadata) = Unit

        override fun sendMessage(message: String) = Unit

        override fun close(
            status: Status,
            trailers: Metadata,
        ) {
            closed = status
        }

        override fun isCancelled() = closed != null

        override fun getMethodDescriptor(): MethodDescriptor<String, String> = error("No method dispatch expected")
    }
}
