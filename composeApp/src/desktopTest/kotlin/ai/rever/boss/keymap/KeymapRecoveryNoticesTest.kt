package ai.rever.boss.keymap

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeymapRecoveryNoticesTest {
    @BeforeTest
    @AfterTest
    fun clearNotice() {
        val owner = KeymapRecoveryNotices.pending.value?.owner ?: Any()
        KeymapRecoveryNotices.claim(owner)
        KeymapRecoveryNotices.acknowledge(owner)
    }

    @Test
    fun `closing an owner makes its notice available to another window`() {
        val first = Any()
        val second = Any()
        KeymapRecoveryNotices.publish("saved.json")
        val notice = assertNotNull(KeymapRecoveryNotices.claim(first))
        assertNull(KeymapRecoveryNotices.claim(second))

        KeymapRecoveryNotices.release(first)

        assertNull(assertNotNull(KeymapRecoveryNotices.pending.value).owner)
        assertSame(notice, KeymapRecoveryNotices.claim(second))
        KeymapRecoveryNotices.release(first)
        KeymapRecoveryNotices.acknowledge(first)
        assertSame(second, assertNotNull(KeymapRecoveryNotices.pending.value).owner)
    }

    @Test
    fun `acknowledging a notice retires it even after the owning window closes`() {
        val owner = Any()
        KeymapRecoveryNotices.publish("saved.json")
        assertNotNull(KeymapRecoveryNotices.claim(owner))

        KeymapRecoveryNotices.acknowledge(owner)
        KeymapRecoveryNotices.release(owner)

        assertNull(KeymapRecoveryNotices.pending.value)
        assertNull(KeymapRecoveryNotices.claim(Any()))
    }

    @Test
    fun `a stale owner cannot discard a later recovery`() {
        val oldOwner = Any()
        KeymapRecoveryNotices.publish("first.json")
        assertNotNull(KeymapRecoveryNotices.claim(oldOwner))
        KeymapRecoveryNotices.publish("second.json")

        KeymapRecoveryNotices.acknowledge(oldOwner)
        KeymapRecoveryNotices.release(oldOwner)

        assertEquals("second.json", assertNotNull(KeymapRecoveryNotices.claim(Any())).preservedFile)
    }

    @Test
    fun `a notice without a saved copy survives window handoff`() {
        val owner = Any()
        KeymapRecoveryNotices.publish(null)
        val notice = assertNotNull(KeymapRecoveryNotices.claim(owner))
        KeymapRecoveryNotices.release(owner)

        assertSame(notice, KeymapRecoveryNotices.claim(Any()))
        assertNull(notice.preservedFile)
    }

    @Test
    fun `racing windows cannot both claim the same notice`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            KeymapRecoveryNotices.publish("saved.json")
            val attempts =
                List(2) {
                    executor.submit<KeymapRecoveryNotice?> {
                        check(start.await(5, TimeUnit.SECONDS))
                        KeymapRecoveryNotices.claim(Any())
                    }
                }
            start.countDown()

            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) != null })
        } finally {
            executor.shutdownNow()
        }
    }
}
