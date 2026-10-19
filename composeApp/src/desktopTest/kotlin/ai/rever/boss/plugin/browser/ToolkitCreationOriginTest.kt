package ai.rever.boss.plugin.browser

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolkitCreationOriginTest {
    @Test
    fun `racing entry points retain the first published caller`() {
        val marker = ToolkitCreationOrigin()
        assertNull(marker.createdBy)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val writers =
                (0 until 8).map { number ->
                    pool.submit {
                        start.await()
                        marker.record("caller-$number")
                    }
                }
            start.countDown()
            writers.forEach { it.get(5, TimeUnit.SECONDS) }
            val first = marker.createdBy
            assertTrue(first in (0 until 8).map { "caller-$it" })
            marker.record("later")
            assertEquals(first, marker.createdBy)
        } finally {
            pool.shutdownNow()
        }
    }
}
