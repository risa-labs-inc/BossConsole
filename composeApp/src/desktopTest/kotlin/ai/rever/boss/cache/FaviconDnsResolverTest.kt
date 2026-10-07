package ai.rever.boss.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaviconDnsResolverTest {
    @Test
    fun `slow blocking DNS returns within its budget`(): Unit =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            FaviconDnsResolver(timeoutMs = 50, lookup = {
                started.countDown()
                release.await()
                listOf(InetAddress.getByName("8.8.8.8"))
            }).use { resolver ->
                try {
                    val start = System.nanoTime()
                    assertEquals(emptyList(), resolver.resolve("example.com"))
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }

    @Test
    fun `DNS work does not grow beyond three workers when the resolver ignores interruption`(): Unit =
        runBlocking {
            val release = CountDownLatch(1)
            val workers = AtomicInteger()
            FaviconDnsResolver(timeoutMs = 30, lookup = {
                workers.incrementAndGet()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // emulate a blocking platform resolver
                    }
                }
                emptyList()
            }).use { resolver ->
                try {
                    repeat(8) { assertEquals(emptyList(), resolver.resolve("example.com")) }
                    assertEquals(3, workers.get())
                } finally {
                    release.countDown()
                }
            }
        }

    @Test
    fun `cancellation stops waiting for a DNS worker`(): Unit =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            FaviconDnsResolver(lookup = {
                entered.countDown()
                release.await()
                emptyList()
            }).use { resolver ->
                try {
                    val lookup = async { resolver.resolve("example.com") }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertTrue(entered.await(1, TimeUnit.SECONDS))
                    }
                    lookup.cancelAndJoin()
                    assertTrue(lookup.isCancelled)
                } finally {
                    release.countDown()
                }
            }
        }
}
