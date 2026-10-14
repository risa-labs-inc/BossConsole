package ai.rever.boss.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlOpenReadinessQueueTest {
    @Test
    fun `cold start retains confirmation for each URL when the queue drains`() {
        val queue = UrlOpenReadinessQueue()
        assertFalse(queue.enqueueOrClaimForCaller("https://external.example", requiresConfirmation = true))
        assertFalse(queue.enqueueOrClaimForCaller("https://operator.example", requiresConfirmation = false))
        assertTrue(queue.hasQueuedURLs())

        assertEquals(
            listOf(
                QueuedUrlOpen("https://external.example", requiresConfirmation = true),
                QueuedUrlOpen("https://operator.example", requiresConfirmation = false),
            ),
            queue.markReadyAndClaimQueued(),
        )
        assertFalse(queue.hasQueuedURLs())
        assertTrue(queue.enqueueOrClaimForCaller("https://later.example", requiresConfirmation = true))
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }
}
