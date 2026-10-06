package ai.rever.boss.mcp.telemetry

/**
 * Child JVM entry points used by the live diagnostics tests.
 *
 * Separate mains rather than one parameterised target, so a test's intent is readable from the
 * class it launches. Each prints `READY <pid>` and flushes before doing anything, which is how
 * the test knows the VM is up and what to attach to; polling `ProcessHandle.pid()` alone would
 * race the JVM's own startup and attach before the attach listener exists.
 *
 * Every target exits on its own after [SELF_DESTRUCT_MS] so a crashed or killed test run cannot
 * leave a CPU burning process behind on a developer's machine.
 */
internal const val SELF_DESTRUCT_MS: Long = 60_000

/** Burns CPU in one clearly named method, so a profile has an unambiguous right answer. */
object HotLoopMain {
    /** The method the profiler must find. Named so an assertion reads as documentation. */
    private fun hotMethodUnderTest(): Double {
        var acc = 0.0
        for (i in 1 until 2_000_000) acc += Math.sqrt(i.toDouble()) / Math.log((i + 1).toDouble())
        return acc
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("READY ${ProcessHandle.current().pid()}")
        System.out.flush()
        var sink = 0.0
        val end = System.currentTimeMillis() + SELF_DESTRUCT_MS
        while (System.currentTimeMillis() < end) sink += hotMethodUnderTest()
        // Consume the result so the JIT cannot eliminate the loop entirely.
        if (sink == Double.MIN_VALUE) println(sink)
    }
}

/** Takes two locks in opposite orders from two threads, producing a real deadlock. */
object DeadlockMain {
    private val lockA = Any()
    private val lockB = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        val bothEntered = java.util.concurrent.CountDownLatch(2)

        val left =
            Thread {
                synchronized(lockA) {
                    bothEntered.countDown()
                    bothEntered.await()
                    synchronized(lockB) { error("unreachable: this is a deadlock fixture") }
                }
            }.apply { name = "deadlock-left" }

        val right =
            Thread {
                synchronized(lockB) {
                    bothEntered.countDown()
                    bothEntered.await()
                    synchronized(lockA) { error("unreachable: this is a deadlock fixture") }
                }
            }.apply { name = "deadlock-right" }

        left.start()
        right.start()

        // Both threads must be holding their first lock and blocked on the second before the test
        // attaches; the latch guarantees the hold, and the deadlock itself is then immediate.
        Thread.sleep(SETTLE_MS)
        println("READY ${ProcessHandle.current().pid()}")
        System.out.flush()
        Thread.sleep(SELF_DESTRUCT_MS)
    }

    private const val SETTLE_MS = 300L
}
