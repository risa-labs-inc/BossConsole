package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the snapshot-under-lock contract for [ResolvedHostsStore].
 *
 * The regression: [ResolvedHostsStore.recordLoaded] used to read `hosts.toList().sorted()` on the
 * calling thread and pass that frozen list into `save`, which only acquired `saveLock` inside its
 * coroutine. Two `recordLoaded` calls landing close together captured snapshots at different set
 * sizes and then raced for the lock; whichever save acquired the lock LAST overwrote the file with
 * its own snapshot, so an older, smaller snapshot could win and silently drop a host another call
 * had already added.
 *
 * The fix reads `hosts` inside `saveLock`, at write time. These tests observe what the launched
 * production saves actually leave on disk - via [ResolvedHostsStore.awaitPendingSaves], never a
 * fresh forced save, which would mask the race - so they fail on the pre-fix call-site snapshot and
 * pass on the fix.
 */
class ResolvedHostsStoreTest {
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempFile = File.createTempFile("resolved-hosts-test-", ".json").apply { delete() }
        ResolvedHostsStore.storeFile = tempFile
        ResolvedHostsStore.clear()
    }

    @AfterTest
    fun tearDown() {
        ResolvedHostsStore.storeFile = BossDirectories.resolve("browser-resolved-hosts.json")
        ResolvedHostsStore.clear()
        tempFile.delete()
    }

    /**
     * The exact race the fix closes, as two interleaved snapshot saves. Each round records two new
     * hosts from two threads at once, then awaits the launched saves and asserts both survive. With
     * the pre-fix call-site snapshot, the save that acquires the lock last can carry a one-host
     * snapshot and overwrite the other host - so across many rounds the older snapshot wins at least
     * once and the assertion fails. With the snapshot taken under the lock, the last writer always
     * serialises the current set, so neither host is ever lost.
     */
    @Test
    fun `an older concurrent save never overwrites a newer one`() {
        repeat(ROUNDS) { round ->
            ResolvedHostsStore.clear()
            tempFile.delete()
            val a = "a-$round.example"
            val b = "b-$round.example"

            val start = CountDownLatch(1)
            val t1 =
                thread {
                    start.await()
                    ResolvedHostsStore.recordLoaded(a)
                }
            val t2 =
                thread {
                    start.await()
                    ResolvedHostsStore.recordLoaded(b)
                }
            start.countDown()
            t1.join()
            t2.join()

            ResolvedHostsStore.awaitPendingSaves()

            val persisted = decode(tempFile.readText())
            assertTrue(
                a in persisted && b in persisted,
                "round $round dropped a host to an out-of-order save: got $persisted",
            )
        }
    }

    /**
     * The same property under broad contention: a burst of concurrent `recordLoaded` calls must all
     * survive. After the launched saves drain, the file must hold every host - which only holds if
     * the last save to run reads the set under the lock rather than replaying a snapshot frozen when
     * its own call arrived.
     */
    @Test
    fun `every host from a burst of concurrent recordLoaded lands on disk`() {
        val count = 200
        val pool = Executors.newFixedThreadPool(16)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(count)
            repeat(count) { i ->
                pool.execute {
                    start.await()
                    ResolvedHostsStore.recordLoaded("burst-$i.example")
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "recordLoaded workers did not finish in time")

            ResolvedHostsStore.awaitPendingSaves()

            val persisted = decode(tempFile.readText())
            assertEquals(
                count,
                persisted.size,
                "expected every concurrent recordLoaded to land; a smaller set means a stale " +
                    "snapshot overwrote a newer one",
            )
            for (i in 0 until count) {
                assertTrue("burst-$i.example" in persisted, "burst-$i.example was recorded but is missing")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * A blank host neither mutates the set nor writes. Unchanged by the fix and identical on both
     * sides - it guards the early return in [ResolvedHostsStore.recordLoaded], not the race - so it
     * uses [ResolvedHostsStore.saveNowBlocking] to compare the bytes before and after directly.
     */
    @Test
    fun `recordLoaded of a blank host does not write or mutate the set`() {
        ResolvedHostsStore.recordLoaded("seed.example")
        val before = ResolvedHostsStore.saveNowBlocking()
        ResolvedHostsStore.recordLoaded("")
        ResolvedHostsStore.recordLoaded("   ")
        val after = ResolvedHostsStore.saveNowBlocking()
        assertEquals(before, after, "no new host means no change to the persisted bytes")
    }

    private fun decode(payload: String): Set<String> =
        kotlinx.serialization.json.Json
            .decodeFromString<List<String>>(payload)
            .toSet()

    private companion object {
        const val ROUNDS = 200
    }
}
