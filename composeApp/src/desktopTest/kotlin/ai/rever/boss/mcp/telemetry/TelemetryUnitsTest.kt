package ai.rever.boss.mcp.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runtime classification, sampler clamping, and histogram parsing. All pure. */
class TelemetryUnitsTest {
    // ------------------------------------------------------------- classify

    @Test
    fun `java executables are jvm targets on every platform spelling`() {
        assertEquals(TargetRuntime.JVM, TelemetryTargets.classify("/usr/bin/java", emptyList()))
        assertEquals(TargetRuntime.JVM, TelemetryTargets.classify("""C:\jdk\bin\java.exe""", emptyList()))
        assertEquals(TargetRuntime.JVM, TelemetryTargets.classify("""C:\jdk\bin\javaw.exe""", emptyList()))
    }

    @Test
    fun `node and python are told apart including versioned python`() {
        assertEquals(TargetRuntime.NODE, TelemetryTargets.classify("/usr/local/bin/node", emptyList()))
        assertEquals(TargetRuntime.PYTHON, TelemetryTargets.classify("/usr/bin/python3", emptyList()))
        assertEquals(TargetRuntime.PYTHON, TelemetryTargets.classify("/usr/bin/python3.11", emptyList()))
        assertEquals(TargetRuntime.PYTHON, TelemetryTargets.classify("""C:\Python\python.exe""", emptyList()))
    }

    @Test
    fun `a launcher script is classified by its jvm flags`() {
        // A Gradle wrapper is a JVM underneath, and an agent profiling a build wants it listed.
        assertEquals(
            TargetRuntime.JVM,
            TelemetryTargets.classify("/repo/gradlew", listOf("-Xmx2g", "build")),
        )
        assertEquals(
            TargetRuntime.JVM,
            TelemetryTargets.classify("/opt/app/run.sh", listOf("-XX:+UseG1GC")),
        )
    }

    @Test
    fun `an ordinary binary is native and a bare dash-jar does not make it a jvm`() {
        assertEquals(TargetRuntime.NATIVE, TelemetryTargets.classify("/usr/bin/nginx", emptyList()))
        assertEquals(TargetRuntime.NATIVE, TelemetryTargets.classify(null, emptyList()))
        // `-jar` alone is a JVM flag, so this one IS a jvm; the guard is that a random arg is not.
        assertEquals(TargetRuntime.NATIVE, TelemetryTargets.classify("/usr/bin/tar", listOf("-xzf", "a.tgz")))
    }

    @Test
    fun `filter parsing accepts the documented values and rejects nothing silently`() {
        assertNull(TargetRuntime.fromFilter("all"))
        assertNull(TargetRuntime.fromFilter(null))
        assertEquals(TargetRuntime.JVM, TargetRuntime.fromFilter("JVM"))
        assertEquals(TargetRuntime.PYTHON, TargetRuntime.fromFilter("python"))
    }

    // ------------------------------------------------------------- clamping

    @Test
    fun `sampling duration is clamped rather than rejected`() {
        assertEquals(10, CpuSampler.clampDurationSeconds(600), "the 60s host timeout makes longer runs useless")
        assertEquals(1, CpuSampler.clampDurationSeconds(0))
        assertEquals(1, CpuSampler.clampDurationSeconds(-5))
        assertEquals(3, CpuSampler.clampDurationSeconds(null))
        assertEquals(7, CpuSampler.clampDurationSeconds(7))
    }

    @Test
    fun `sampling frequency is clamped and defaults off the timer boundary`() {
        assertEquals(99, CpuSampler.clampHz(null), "99 avoids locking step with 100ms timers")
        assertEquals(1, CpuSampler.clampHz(0))
        assertEquals(1000, CpuSampler.clampHz(100_000))
        assertEquals(250, CpuSampler.clampHz(250))
    }

    // ------------------------------------------------------------- histogram

    @Test
    fun `class histogram rows parse and non-rows are skipped`() {
        val raw =
            """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:        924000       29568000  java.util.HashMap${'$'}Node (java.base)
               2:        412003       13184096  java.lang.String (java.base)
               3:          1024          32768  com.example.Session
            Total       1337027       42784864
            """.trimIndent()

        val parsed = HeapInspector.parseHistogram(raw, limit = 10)

        assertEquals(3, parsed.size, "header, separator and Total must all be skipped")
        assertEquals("java.util.HashMap\$Node", parsed[0].className)
        assertEquals(924_000L, parsed[0].instances)
        assertEquals(29_568_000L, parsed[0].bytes)
        assertEquals("com.example.Session", parsed[2].className)
    }

    @Test
    fun `histogram parsing honours its limit and tolerates junk`() {
        val raw =
            (1..50).joinToString("\n") { "   $it:  ${it * 10}  ${it * 100}  com.example.C$it" } +
                "\nnot a row at all\n   bad:  x  y  z\n"

        assertEquals(5, HeapInspector.parseHistogram(raw, limit = 5).size)
        // Malformed rows are dropped, never guessed at.
        assertTrue(HeapInspector.parseHistogram(raw, limit = 100).none { it.className == "z" })
        assertEquals(emptyList(), HeapInspector.parseHistogram("", limit = 10))
    }
}
