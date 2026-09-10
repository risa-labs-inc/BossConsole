package ai.rever.boss.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepLinkForwardingTest {
    @Test
    fun `a deadline gives an unknown outcome and cancels the pending dispatch`() =
        runBlocking {
            val verdict = CompletableDeferred<Boolean>()
            assertNull(awaitPluginAction(verdict, 1))
            assertTrue(verdict.isCancelled)
        }

    @Test
    fun `a cancelled dispatch returns a negative verdict without escaping to the socket handler`() =
        runBlocking {
            val verdict = CompletableDeferred<Boolean>()
            verdict.cancel()
            assertEquals(false, awaitPluginAction(verdict, 5000))
        }

    @Test
    fun `an unsuccessful action is submitted only once even if a retry would succeed`() {
        val links = listOf("boss://plugin?id=x&action=run", "BOSS://PLUGIN?action=run", "boss://plugin?id=x&action=")
        for (link in links) {
            var sends = 0
            var pauses = 0
            val accepted = forwardDeepLinkWithRetry(link, { ++sends > 1 }, { pauses++ })
            assertFalse(accepted)
            assertEquals(1, sends)
            assertEquals(0, pauses)
        }
    }

    @Test
    fun `auth and panel opens retain bounded retries and stop at success`() {
        for (link in listOf("boss://auth/verify?token=test", "boss://plugin?id=x", "boss://plugins?action=run")) {
            var sends = 0
            var pauses = 0
            assertTrue(forwardDeepLinkWithRetry(link, { ++sends == 2 }, { pauses++ }))
            assertEquals(2, sends)
            assertEquals(1, pauses)
            sends = 0
            pauses = 0
            assertFalse(
                forwardDeepLinkWithRetry(link, {
                    sends++
                    false
                }, { pauses++ }),
            )
            assertEquals(3, sends)
            assertEquals(2, pauses)
        }
    }
}
