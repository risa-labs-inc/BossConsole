package ai.rever.boss.kernel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReapSpawnGateTest {
    @Test
    fun `overlapping reaps keep admission closed until both finish`() {
        val gate = ReapSpawnGate()
        gate.beginReap()
        gate.beginReap()
        gate.endReap()
        assertTrue(gate.isReaping())
        assertFailsWith<ReapAdmissionException> { gate.spawn(gate.generation(), { true }, {}) }
        gate.endReap()
        assertFalse(gate.isReaping())
        assertTrue(gate.spawn(gate.generation(), { true }, {}))
    }

    @Test
    fun `a spawn prepared before a completed reap is refused before creating anything`() {
        val gate = ReapSpawnGate()
        val generation = gate.generation()
        gate.beginReap()
        gate.endReap()
        assertFailsWith<ReapAdmissionException> { gate.spawn(generation, { error("must not spawn") }, {}) }
    }

    @Test
    fun `a slow fork cannot block a reap and its late child is discarded`() {
        val gate = ReapSpawnGate()
        val generation = gate.generation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var discarded = false
        try {
            val spawn =
                executor.submit<Boolean> {
                    try {
                        gate.spawn(generation, {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            true
                        }, { discarded = true })
                        false
                    } catch (_: ReapAdmissionException) {
                        true
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor
                .submit {
                    gate.beginReap()
                    gate.endReap()
                }.get(5, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(spawn.get(5, TimeUnit.SECONDS))
            assertTrue(discarded)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
