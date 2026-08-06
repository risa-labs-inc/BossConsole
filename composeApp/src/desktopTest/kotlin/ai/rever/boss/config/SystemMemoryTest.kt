package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that [SystemMemory] can actually read this machine's memory.
 *
 * Worth its own test because every other memory test in this feature injects the byte counts
 * directly, so all of them stay green while the real reading silently returns 0. And 0 is not a
 * loud failure: [ResourceModeConfig.detectResourceMode] treats it as "unknown" and resolves FULL,
 * which is indistinguishable from a roomy machine. The whole automatic tier selection and the
 * entire memory-pressure watchdog would be dead code and nothing would say so.
 *
 * The specific hazard is JDK strong encapsulation. `ManagementFactory.getOperatingSystemMXBean()`
 * returns `com.sun.management.internal.OperatingSystemImpl`, whose package `jdk.management` does
 * not export; resolving a method on that implementation class and calling `setAccessible(true)`
 * throws `InaccessibleObjectException` on JDK 17+ without an `--add-opens`. Resolving against the
 * exported `com.sun.management.OperatingSystemMXBean` interface avoids it. A JDK bump can
 * reintroduce this, hence a permanent test rather than a one-off check.
 */
class SystemMemoryTest {
    @Test
    fun `total physical memory is readable on this JVM`() {
        val total = SystemMemory.totalPhysicalBytes()
        assertTrue(
            total > 0L,
            "SystemMemory.totalPhysicalBytes() returned $total. Auto tier selection and the " +
                "memory-pressure watchdog are both dead when this is 0.",
        )
    }

    @Test
    fun `available memory is readable on this JVM`() {
        val available = SystemMemory.availableBytes()
        assertTrue(
            available > 0L,
            "SystemMemory.availableBytes() returned $available. MemoryPressureWatchdog can never " +
                "act when this is 0.",
        )
    }

    @Test
    fun `the free fraction is a sane ratio`() {
        val fraction = assertNotNull(SystemMemory.freeFraction(), "freeFraction() was null")
        assertTrue(fraction > 0.0 && fraction <= 1.0, "freeFraction() was $fraction")
    }

    @Test
    fun `available never exceeds total`() {
        assertTrue(SystemMemory.availableBytes() <= SystemMemory.totalPhysicalBytes())
    }

    /**
     * The specific regression that motivated this file: resolving these readings against the
     * **implementation** class rather than the exported interface. `setAccessible(true)` on a
     * member of `com.sun.management.internal` throws on every JDK we support, and the reflective
     * version only appeared to work because the deprecated `getTotalPhysicalMemorySize` shim
     * lives on the exported interface. If someone reintroduces reflection, this is the assertion
     * that should stop them.
     */
    @Test
    fun `the bean is reachable without opening a JDK-internal package`() {
        val bean =
            java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()
        assertTrue(
            bean is com.sun.management.OperatingSystemMXBean,
            "the platform bean must satisfy the exported com.sun.management interface; " +
                "reaching its impl class reflectively needs an --add-opens that packaged " +
                "builds do not have",
        )
    }
}
