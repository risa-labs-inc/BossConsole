package ai.rever.boss.plugin.loader

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The negative marker from BossConsole#108.
 *
 * The signature backfill retries on "sidecar missing", which the genuine-mismatch
 * case deliberately never resolves: the store vouches for different bytes than the
 * GitHub asset on disk, so no signature may be bound and none ever will be. Left
 * alone that means a `getDownloadUrl` on every launch forever, and that call is not
 * read-only, so it books a `plugin_downloads` row each time and feeds the store's
 * default download ranking.
 */
class UnsignableMarkerTest {
    private val tempDir = createTempDirectory("unsignable-test").toFile()
    private val anchor = "ai.rever.boss.plugin.dynamic.terminaltab|2.5.71|abc123"

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun jar(name: String) = File(tempDir, name).apply { writeText("jar") }

    @Test
    fun `an unmarked JAR is not known unsignable`() {
        assertFalse(PluginSignatureSidecar.isKnownUnsignable(jar("a.jar").absolutePath, anchor))
    }

    @Test
    fun `a marked anchor is remembered`() {
        val j = jar("b.jar")
        PluginSignatureSidecar.markUnsignable(j.absolutePath, anchor)
        assertTrue(PluginSignatureSidecar.isKnownUnsignable(j.absolutePath, anchor))
    }

    @Test
    fun `the marker is keyed on the whole anchor, so a new version retries`() {
        // Self-invalidation is the entire safety story: nothing ever clears this
        // marker on purpose, so it must stop matching by itself the moment the
        // plugin, its version, or its bytes change. A marker that outlived a
        // version bump would leave a signable plugin permanently unsigned.
        val j = jar("c.jar")
        PluginSignatureSidecar.markUnsignable(j.absolutePath, anchor)

        val newVersion = "ai.rever.boss.plugin.dynamic.terminaltab|2.5.72|abc123"
        val newBytes = "ai.rever.boss.plugin.dynamic.terminaltab|2.5.71|def456"
        val newPlugin = "ai.rever.boss.plugin.dynamic.editortab|2.5.71|abc123"

        assertFalse(PluginSignatureSidecar.isKnownUnsignable(j.absolutePath, newVersion))
        assertFalse(PluginSignatureSidecar.isKnownUnsignable(j.absolutePath, newBytes))
        assertFalse(PluginSignatureSidecar.isKnownUnsignable(j.absolutePath, newPlugin))
    }

    @Test
    fun `delete takes the marker with the sidecar`() {
        // Why markUnsignable needs no cleanup obligations of its own: every path
        // that retires a JAR already calls delete, so the marker rides along.
        val j = jar("d.jar")
        PluginSignatureSidecar.write(j.absolutePath, "sig==")
        PluginSignatureSidecar.markUnsignable(j.absolutePath, anchor)

        PluginSignatureSidecar.delete(j.absolutePath)

        assertFalse(File(PluginSignatureSidecar.pathFor(j.absolutePath)).exists())
        assertFalse(File(PluginSignatureSidecar.unsignablePathFor(j.absolutePath)).exists())
    }

    @Test
    fun `a marker never becomes a signature`() {
        // The two files must stay independent. A marker that read back as a
        // present-but-invalid signature would hard-fail load, which is far worse
        // than the unsigned state it is recording.
        val j = jar("e.jar")
        PluginSignatureSidecar.markUnsignable(j.absolutePath, anchor)
        assertEquals(null, PluginSignatureSidecar.read(j.absolutePath))
    }
}

/**
 * `write` staged through a temp file whose name was derived only from the JAR, so
 * two writers racing one JAR shared one temp path: A writes it, B truncates and
 * rewrites it, A moves B's partial bytes into place, B's own move then throws on
 * a file that is already gone. The call sites wrap this in `runCatching`, so it
 * showed up as a warn rather than a crash, while the sidecar left behind could be
 * the interleaved one.
 */
class ConcurrentSidecarWriteTest {
    private val tempDir = createTempDirectory("sidecar-race-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `concurrent writers each leave a whole signature, never a blend`() {
        val jar = File(tempDir, "contended.jar").apply { writeText("jar") }
        // Different lengths as well as contents: a torn write shows up as a
        // wrong-length read even if the bytes happen to look plausible.
        val a = "a".repeat(512)
        val b = "b".repeat(2048)

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        try {
            val writers =
                List(threads) { i ->
                    pool.submit {
                        start.await()
                        repeat(40) {
                            runCatching { PluginSignatureSidecar.write(jar.absolutePath, if (i % 2 == 0) a else b) }
                                .onFailure { failures.add(it) }
                            val read = PluginSignatureSidecar.read(jar.absolutePath)
                            if (read != a && read != b) {
                                failures.add(AssertionError("missing or torn sidecar of length ${read?.length}"))
                            }
                        }
                    }
                }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers did not finish")
            writers.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n") { it.stackTraceToString() })
        assertTrue(PluginSignatureSidecar.read(jar.absolutePath) in listOf(a, b))
    }

    @Test
    fun `no temp files are left behind`() {
        val jar = File(tempDir, "leftovers.jar").apply { writeText("jar") }
        repeat(5) { PluginSignatureSidecar.write(jar.absolutePath, "sig$it") }
        val strays = tempDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertEquals(emptyList(), strays.map { it.name })
    }
}
