package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedAppResourcesTest {
    @Test fun `late resources close immediately and one failing close cannot strand others`() {
        val owner = OwnedAppResources()
        val order = mutableListOf<Int>()
        assertTrue(owner.own(AutoCloseable { order.add(1) }))
        owner.own(
            AutoCloseable {
                order.add(2)
                error("native close failed")
            },
        )
        owner.own(AutoCloseable { order.add(3) })
        owner.close()
        owner.close()
        assertFalse(owner.own(AutoCloseable { order.add(4) }))
        assertTrue(owner.isClosed)
        assertEquals(listOf(3, 2, 1, 4), order)
    }

    @Test fun `concurrent creation and retirement cannot orphan a resource`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                val owner = OwnedAppResources()
                val start = CountDownLatch(1)
                val closes = List(16) { AtomicInteger() }
                val futures =
                    closes.map { count ->
                        pool.submit {
                            start.await()
                            owner.own(AutoCloseable { count.incrementAndGet() })
                        }
                    }
                val stop =
                    pool.submit {
                        start.await()
                        owner.close()
                    }
                start.countDown()
                futures.forEach { it.get(5, TimeUnit.SECONDS) }
                stop.get(5, TimeUnit.SECONDS)
                owner.close()
                assertTrue(closes.all { it.get() == 1 })
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
