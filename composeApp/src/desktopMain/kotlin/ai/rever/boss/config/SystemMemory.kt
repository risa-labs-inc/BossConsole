package ai.rever.boss.config

import java.lang.management.ManagementFactory

/**
 * Physical-memory readings for the host machine.
 *
 * `getTotalPhysicalMemorySize` lives on `com.sun.management.OperatingSystemMXBean`, which is
 * a JDK-internal extension rather than part of `java.lang.management`, so it is reached
 * reflectively: a direct reference compiles only against a JDK that exports it, and this
 * value is wanted on every platform we ship. The reflective read was copy-pasted into three
 * places before this object existed; new callers belong here.
 *
 * Every method returns 0 rather than throwing when the bean cannot be read. Callers must
 * treat 0 as "unknown" and not as "no memory" - see [ResourceModeConfig.resolveResourceMode],
 * where a failed read deliberately does *not* trigger a reduced tier.
 */
object SystemMemory {
    private val osBean: Any = ManagementFactory.getOperatingSystemMXBean()

    /** Total installed RAM in bytes, or 0 when it cannot be read. */
    fun totalPhysicalBytes(): Long = readLong("getTotalPhysicalMemorySize", "getTotalMemorySize")

    /** Currently free RAM in bytes, or 0 when it cannot be read. */
    fun freePhysicalBytes(): Long = readLong("getFreePhysicalMemorySize", "getFreeMemorySize")

    /**
     * Free RAM as a fraction of total, in `0.0..1.0`, or null when either reading failed.
     *
     * Null is distinct from 0.0 on purpose: the memory-pressure watchdog must not read an
     * unreadable bean as "no memory left" and start closing the user's browsers.
     */
    fun freeFraction(): Double? {
        val total = totalPhysicalBytes()
        val free = freePhysicalBytes()
        if (total <= 0L || free <= 0L) return null
        return (free.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * Reads the first of [names] the bean actually exposes.
     *
     * Two spellings because JDK 14 renamed these: `getTotalPhysicalMemorySize` was
     * deprecated in favour of `getTotalMemorySize`. Both are tried so the reading survives
     * a JDK bump in either direction.
     */
    private fun readLong(vararg names: String): Long {
        for (name in names) {
            try {
                val method = osBean.javaClass.getMethod(name)
                method.isAccessible = true
                val value = method.invoke(osBean) as? Long ?: continue
                if (value > 0L) return value
            } catch (_: Exception) {
                // Try the next spelling; a missing method is expected on one of them.
            }
        }
        return 0L
    }
}
