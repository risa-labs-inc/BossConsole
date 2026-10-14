package ai.rever.boss.performance

import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the per-tick full thread-table scan.
 *
 * The monitor used to call `allThreadIds` + `getThreadInfo(all)` + per-thread
 * CPU/user time on every 2s CPU tick. These tests pin the two bounds
 * [TopThreadSampler] puts on that: full enumerations are throttled, and thread
 * metadata is fetched for a bounded candidate set rather than every live thread.
 */
class TopThreadSamplerTest {
    @Test
    fun `does not enumerate all threads on every sample`() {
        val allThreadIdsCalls = AtomicInteger(0)
        val bean = fakeThreadMXBean(threadCount = 500, allThreadIdsCalls = allThreadIdsCalls)
        var now = 0L
        val sampler = TopThreadSampler(bean, minScanIntervalMs = 10_000L, nowMs = { now })

        // Simulates CPU ticks at 2s spacing: five ticks inside one scan window.
        repeat(5) {
            sampler.sample()
            now += 2_000L
        }
        assertEquals(
            1,
            allThreadIdsCalls.get(),
            "allThreadIds must not be consulted on every tick within a scan window",
        )

        // After the interval elapses a fresh enumeration is allowed.
        now += 2_000L
        sampler.sample()
        assertEquals(2, allThreadIdsCalls.get(), "a new window should trigger exactly one re-scan")
    }

    @Test
    fun `thread metadata is fetched for a bounded candidate set`() {
        val getThreadInfoSizes = mutableListOf<Int>()
        val userTimeCalls = AtomicInteger(0)
        val bean =
            fakeThreadMXBean(
                threadCount = 500,
                getThreadInfoSizes = getThreadInfoSizes,
                userTimeCalls = userTimeCalls,
            )
        val sampler = TopThreadSampler(bean, minScanIntervalMs = 0L)

        sampler.sample()

        assertEquals(1, getThreadInfoSizes.size, "exactly one batched getThreadInfo call per scan")
        assertTrue(
            getThreadInfoSizes.single() <= TopThreadSampler.CANDIDATE_COUNT,
            "getThreadInfo must cover at most ${TopThreadSampler.CANDIDATE_COUNT} candidates, " +
                "not all 500 threads (was ${getThreadInfoSizes.single()})",
        )
        assertTrue(
            userTimeCalls.get() <= TopThreadSampler.THREAD_LIMIT,
            "per-thread user time is only fetched for retained threads",
        )
    }

    @Test
    fun `sample returns at most the displayed thread count`() {
        val sampler =
            TopThreadSampler(
                ManagementFactory.getThreadMXBean(),
                minScanIntervalMs = 0L,
            )
        val threads = sampler.sample()
        assertTrue(threads.isNotEmpty(), "a live JVM always has threads")
        assertTrue(threads.size <= TopThreadSampler.THREAD_LIMIT)
        assertTrue(threads.all { it.name.isNotBlank() }, "every entry should carry a thread name")
    }

    /**
     * A [ThreadMXBean] whose instrumentation is limited to what the sampler needs:
     * a fixed id set, `getThreadCpuTime` proportional to id, and a `getThreadInfo`
     * batch overload that records how many ids it was asked about and answers null
     * (a dead thread) for each - the sampler's own result content is covered by the
     * real-bean test above.
     */
    private fun fakeThreadMXBean(
        threadCount: Int,
        allThreadIdsCalls: AtomicInteger = AtomicInteger(0),
        getThreadInfoSizes: MutableList<Int> = mutableListOf(),
        userTimeCalls: AtomicInteger = AtomicInteger(0),
    ): ThreadMXBean {
        val ids = (1L..threadCount.toLong()).toList().toLongArray()
        val handler =
            InvocationHandler { proxy, method, args ->
                when {
                    method.name == "getAllThreadIds" -> {
                        allThreadIdsCalls.incrementAndGet()
                        ids
                    }

                    method.name == "isThreadCpuTimeSupported" ||
                        method.name == "isThreadCpuTimeEnabled" -> {
                        true
                    }

                    method.name == "getThreadCpuTime" -> {
                        (args[0] as Long) * 1_000_000L
                    }

                    method.name == "getThreadUserTime" -> {
                        userTimeCalls.incrementAndGet()
                        (args[0] as Long) * 1_000L
                    }

                    method.name == "getThreadInfo" && args?.get(0) is LongArray -> {
                        val requested = args[0] as LongArray
                        getThreadInfoSizes.add(requested.size)
                        arrayOfNulls<java.lang.management.ThreadInfo>(requested.size)
                    }

                    method.name == "getThreadCount" -> {
                        threadCount
                    }

                    method.name == "equals" -> {
                        proxy === args?.get(0)
                    }

                    method.name == "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    method.name == "toString" -> {
                        "FakeThreadMXBean"
                    }

                    else -> {
                        defaultValue(method.returnType)
                    }
                }
            }
        return Proxy.newProxyInstance(
            ThreadMXBean::class.java.classLoader,
            arrayOf(ThreadMXBean::class.java),
            handler,
        ) as ThreadMXBean
    }

    private fun defaultValue(type: Class<*>): Any? =
        when {
            !type.isPrimitive -> null
            type == java.lang.Boolean.TYPE -> false
            type == java.lang.Long.TYPE -> 0L
            type == java.lang.Integer.TYPE -> 0
            type == java.lang.Double.TYPE -> 0.0
            type == java.lang.Float.TYPE -> 0f
            else -> 0
        }
}
