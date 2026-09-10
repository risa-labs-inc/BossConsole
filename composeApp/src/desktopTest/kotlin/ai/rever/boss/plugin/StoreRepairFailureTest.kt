package ai.rever.boss.plugin

import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoreRepairFailureTest {
    @Test
    fun `DNS timeout and TLS failures enqueue a store retry without leaking exception details`() {
        listOf(
            UnknownHostException("private-host"),
            SocketTimeoutException("token=secret"),
            SSLException("private-url"),
        ).forEach { failure ->
            val queued = mutableListOf<String>()
            queueStoreRepairAfterGitHubFailure(failure, queued::add)
            assertEquals(listOf("GitHub request failed (${failure.javaClass.simpleName})"), queued)
        }
    }

    @Test
    fun `cancellation propagates without enqueuing a fallback`() {
        val queued = mutableListOf<String>()
        val cancelled = CancellationException("shutdown")
        val thrown =
            assertFailsWith<CancellationException> {
                queueStoreRepairAfterGitHubFailure(cancelled, queued::add)
            }
        assertSame(cancelled, thrown)
        assertTrue(queued.isEmpty())
    }
}
