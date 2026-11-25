package ai.rever.boss.plugin.repository.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PublicCatalogReadsTest {
    @Test fun burstsCollapseAndExpiryFetchesAgain(): Unit = runTest {
        var now = 0L
        var reads = 0
        val cache = PublicCatalogReads({ now })
        val gate = CompletableDeferred<Unit>()
        val burst = List(100) { async { cache.read(listOf("a")) { reads++; gate.await(); "value" } } }
        runCurrent()
        assertEquals(1, reads)
        gate.complete(Unit)
        burst.forEach { assertEquals("value", it.await()) }
        assertEquals(1, reads)
        now = 30_000
        assertEquals("new", cache.read(listOf("a")) { reads++; "new" })
        assertEquals(2, reads)
    }

    @Test fun invalidationDuringFetchPreventsCacheInsertion(): Unit = runTest {
        val cache = PublicCatalogReads({ 0 })
        val gate = CompletableDeferred<Unit>()
        val first = async { cache.read(listOf("a")) { gate.await(); "old" } }
        runCurrent()
        cache.invalidate()
        gate.complete(Unit)
        assertEquals("old", first.await())
        assertEquals("new", cache.read(listOf("a")) { "new" })
    }

    @Test fun keysAndFailuresStaySeparate(): Unit = runTest {
        val cache = PublicCatalogReads({ 0 })
        assertEquals("a", cache.read(listOf("a")) { "a" })
        assertEquals("b", cache.read(listOf("b")) { "b" })
        assertFailsWith<IllegalStateException> { cache.read(listOf("c")) { error("offline") } }
        assertEquals("c", cache.read(listOf("c")) { "c" })
        cache.invalidate()
        assertEquals("changed", cache.read(listOf("a")) { "changed" })
    }
}
