package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppRawFrameMailboxTest {
    private fun frame() =
        AppRawCapturedFrame(
            AppRawWindowFrame(byteArrayOf(0, 0, 0, -1), 1, 1),
            1,
            AppSurfaceSnapshot(emptyList(), 0, 0, 1, 1, 1, 1),
        )

    @Test
    fun `newest frame is immediately available when publication wins the wait race`() {
        AppRawFrameMailbox().use { mailbox ->
            val first = mailbox.latest()
            val newest = frame()
            mailbox.offer(frame())
            mailbox.offer(newest)
            val result = mailbox.awaitNext(first.sequence)
            assertEquals(2L, result.sequence)
            assertSame(newest, result.frame)
        }
    }

    @Test
    fun `clear and terminal retirement release readers without resurrecting pixels`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            AppRawFrameMailbox().use { mailbox ->
                mailbox.offer(frame())
                val after = mailbox.latest().sequence
                val started = CountDownLatch(1)
                val reader =
                    executor.submit<AppRawFrameMailbox.Snapshot> {
                        started.countDown()
                        mailbox.awaitNext(after)
                    }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                mailbox.offer(null)
                val cleared = reader.get(1, TimeUnit.SECONDS)
                assertNull(cleared.frame)
                assertTrue(cleared.sequence > after)
                mailbox.close()
                val retired = mailbox.latest()
                mailbox.offer(frame())
                assertEquals(retired, mailbox.awaitNext(retired.sequence))
                assertNull(mailbox.latest().frame)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an idle reader returns the unchanged snapshot after its bounded wait`() {
        AppRawFrameMailbox().use { mailbox ->
            val before = mailbox.latest()
            assertEquals(before, mailbox.awaitNext(before.sequence, timeoutMillis = 1))
            assertEquals(before, mailbox.awaitNext(-1))
        }
    }
}
