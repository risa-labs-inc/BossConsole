package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.ClassLoaderState
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginUnloadRefusal
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plugin code for the tests below, copied out of the test classpath into a real plugin jar.
 * [run] reaches [TeardownLateDependency] only when it is called, so the JVM resolves that
 * reference - asking the plugin's loader for the class - when [run] executes, not when the plugin
 * loads. That is the shape of every filed report: a straggler (a Ktor selector, a coroutine)
 * touching a plugin class it had not needed yet, after the unload.
 *
 * `Runnable` is a `java.` type, shared between host and plugin, so the test can call the plugin's
 * copy without reflection. The package is not shared, so both classes take the child-first path
 * a real plugin's classes take.
 */
class TeardownStraggler : Runnable {
    override fun run() {
        TeardownLateDependency().touch()
    }
}

class TeardownLateDependency {
    fun touch() = Unit
}

/**
 * The #1368 carve-out through a real [PluginClassLoader] and a real unload, not a built message.
 * A plugin class resolving a reference after its loader left ACTIVE fails with a
 * `NoClassDefFoundError` on the straggler's thread; [CrashHandler.isIgnorable] - the first thing
 * the installed handler asks - must absorb it on every hit, never a live plugin's, and the
 * absorbed failure must still leave a contained report.
 */
class PluginTeardownRefusalIntegrationTest {
    private val tempJars = mutableListOf<File>()
    private val hostLoader: ClassLoader = PluginTeardownRefusalIntegrationTest::class.java.classLoader
    private lateinit var reportDir: File

    @BeforeTest
    fun setUp() {
        reportDir = Files.createTempDirectory("teardown-refusal-reports").toFile()
        CrashHandler.containedReportDirOverride = reportDir
        CrashHandler.resetContainedStateForTest()
    }

    @AfterTest
    fun cleanup() {
        CrashHandler.containedReportDirOverride = null
        CrashHandler.resetContainedStateForTest()
        reportDir.deleteRecursively()
        tempJars.forEach { it.delete() }
    }

    @Test
    fun `a class request made after the plugin unloads is refused and not reported as a crash`() {
        val loader = loaderOver(TeardownStraggler::class.java, TeardownLateDependency::class.java)
        val straggler = pluginInstance(loader)
        loader.close()
        assertEquals(ClassLoaderState.UNLOADED, loader.state)

        val error = assertIs<NoClassDefFoundError>(uncaughtFrom(straggler), "the JVM reports it at the call site")

        val refusal =
            assertIs<PluginUnloadRefusal>(
                error.cause,
                "on the first failure the JVM keeps the loader's refusal as the cause: ${error.cause}",
            )
        assertEquals(PLUGIN_ID, refusal.pluginId)
        assertEquals(TeardownLateDependency::class.java.name, refusal.className)
        assertTrue(CrashHandler.isIgnorable(error), "a teardown refusal must not reach the crash dialog")
    }

    // Review on #1368: the JVM caches a failed resolution per call site and rebuilds a later
    // error's cause by class name through the boot loader, which cannot see PluginUnloadRefusal.
    @Test
    fun `the same call site failing again after the unload is still contained`() {
        val loader = loaderOver(TeardownStraggler::class.java, TeardownLateDependency::class.java)
        val straggler = pluginInstance(loader)
        loader.close()

        val hits = List(3) { uncaughtFrom(straggler) }

        assertIs<PluginUnloadRefusal>(hits.first().cause, "the first hit carries the typed refusal")
        for (later in hits.drop(1)) {
            // Pins the JVM behaviour the fallback exists for; if this ever starts carrying the
            // typed refusal, the fallback is belt and braces rather than the only cover.
            assertFalse(later.cause is PluginUnloadRefusal, "a repeated hit loses the typed cause: ${later.cause}")
        }
        hits.forEachIndexed { i, error ->
            assertTrue(CrashHandler.isIgnorable(error), "hit ${i + 1} must be contained: $error <- ${error.cause}")
        }
    }

    @Test
    fun `a request while the plugin is still unloading is refused and contained`() {
        // The jar is still open while UNLOAD_IN_PROGRESS and serves its own classes, so the
        // refusal there is for a class the jar never had.
        val loader = loaderOver(TeardownStraggler::class.java)
        val straggler = pluginInstance(loader)
        loader.markUnloading()
        try {
            val first = uncaughtFrom(straggler)
            val again = uncaughtFrom(straggler)

            assertEquals(ClassLoaderState.UNLOAD_IN_PROGRESS, assertIs<PluginUnloadRefusal>(first.cause).loaderState)
            assertTrue(CrashHandler.isIgnorable(first))
            assertTrue(CrashHandler.isIgnorable(again), "the repeated hit is contained while unloading too")
        } finally {
            loader.close()
        }
    }

    @Test
    fun `a class missing from a live plugin is still reported as a crash, however often`() {
        val loader = loaderOver(TeardownStraggler::class.java)
        val straggler = pluginInstance(loader)
        try {
            for (hit in List(2) { uncaughtFrom(straggler) }) {
                // The JVM can rebuild ClassNotFoundException by name, so a live miss keeps one as its
                // cause on every hit. The fallback relies on exactly this to leave these reported.
                assertIs<ClassNotFoundException>(hit.cause, "a live miss keeps a real cause: ${hit.cause}")
                assertFalse(hit.cause is PluginUnloadRefusal, "a live plugin's miss is not a teardown refusal")
                assertFalse(CrashHandler.isIgnorable(hit), "a genuinely missing class must still be reported")
            }
        } finally {
            loader.close()
        }
    }

    // An update leaves the old loader unloaded and a new one live, defining classes of the same
    // names, so "a retired loader defined this class" is true for both. The live version's miss
    // still reports because its cause is a real ClassNotFoundException on every hit.
    @Test
    fun `an update's live loader is not covered by the loader it replaced`() {
        val old = loaderOver(TeardownStraggler::class.java, TeardownLateDependency::class.java)
        val oldStraggler = pluginInstance(old)
        old.close()
        // The new version's jar lacks the class: a real packaging bug in a live plugin.
        val current = loaderOver(TeardownStraggler::class.java)
        val currentStraggler = pluginInstance(current)
        try {
            repeat(2) {
                assertTrue(CrashHandler.isIgnorable(uncaughtFrom(oldStraggler)), "the old version's straggler")
                assertFalse(CrashHandler.isIgnorable(uncaughtFrom(currentStraggler)), "the live version's miss")
            }
        } finally {
            current.close()
        }
    }

    // Review on #1368: absorbing the refusal must not leave nothing behind. File logging is off by
    // default, so the contained report is the only durable record of the straggler's stack.
    @Test
    fun `the uncaught-exception route absorbs a teardown refusal and still records it, once`() {
        val (first, repeated) = refusalHits()
        val thread = Thread.currentThread()

        // Absorbed: handleCrash returns here, before the dialog slot or an exit.
        assertTrue(CrashHandler.absorbIgnorable(thread, first))
        assertTrue(CrashHandler.absorbIgnorable(thread, repeated))
        drainWriter()

        assertOneTeardownReport()
    }

    @Test
    fun `the render route records a teardown refusal instead of returning early`() {
        val (first, repeated) = refusalHits()

        CrashHandler.recordContained(first)
        CrashHandler.recordContained(repeated)
        drainWriter()

        assertOneTeardownReport()
    }

    /** The first failure at the straggler's call site after the unload, and a repeat of it. */
    private fun refusalHits(): Pair<Throwable, Throwable> {
        val loader = loaderOver(TeardownStraggler::class.java, TeardownLateDependency::class.java)
        val straggler = pluginInstance(loader)
        loader.close()
        return uncaughtFrom(straggler) to uncaughtFrom(straggler)
    }

    private fun assertOneTeardownReport() {
        val written = reports()
        assertEquals(1, written.size, "the same straggler is recorded once, not per hit: ${written.map { it.name }}")
        val text = written.single().readText()
        val headline = text.lineSequence().first()
        assertEquals("BOSS plugin teardown refusal", headline, "the report says what it is")
        assertTrue(text.contains(TeardownStraggler::class.java.name), "the straggler's frame is kept: $text")
    }

    @Test
    fun `other absorbed exceptions still write nothing`() {
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        assertTrue(CrashHandler.absorbIgnorable(Thread.currentThread(), cancelled))
        drainWriter()

        assertEquals(emptyList(), reports().map { it.name })
    }

    /** The plugin's own copy of [TeardownStraggler], constructed, linked and ready to run. */
    private fun pluginInstance(loader: PluginClassLoader): Runnable {
        val cls = loader.loadClass(TeardownStraggler::class.java.name)
        assertEquals(loader, cls.classLoader, "the straggler must be the plugin's copy, not the host's")
        return cls.getDeclaredConstructor().newInstance() as Runnable
    }

    /** Runs [body] on a thread of its own and returns what reached that thread's uncaught handler. */
    private fun uncaughtFrom(body: Runnable): Throwable {
        val caught = arrayOfNulls<Throwable>(1)
        val thread =
            Thread(body, "plugin-teardown-straggler").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e -> caught[0] = e }
                start()
            }
        thread.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(thread.isAlive, "the straggler never finished")
        return assertNotNull(caught[0], "the straggler finished without an uncaught error")
    }

    private fun reports(): List<File> =
        reportDir.listFiles { f -> f.name.startsWith("contained-") && f.name.endsWith(".txt") }?.toList().orEmpty()

    /** Waits for the contained-report writer by sending a marker through it and deleting it. */
    private fun drainWriter() {
        val marker =
            IllegalStateException("teardown-refusal-drain").apply {
                stackTrace = arrayOf(StackTraceElement("ai.rever.boss.crash.DrainMarker", "drain", "DrainMarker.kt", 1))
            }
        CrashHandler.recordContained(marker)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val written = reports().filter { runCatching { it.readText() }.getOrDefault("").contains(marker.message!!) }
            if (written.isNotEmpty() && written.all { it.delete() || !it.exists() }) return
            Thread.sleep(20)
        }
        throw AssertionError("the contained-report writer never drained")
    }

    private fun loaderOver(vararg classes: Class<*>): PluginClassLoader =
        PluginClassLoader(
            pluginId = PLUGIN_ID,
            urls = arrayOf(jarContaining(*classes).toURI().toURL()),
            parent = hostLoader,
        )

    private fun jarContaining(vararg classes: Class<*>): File {
        val jar = File.createTempFile("plugin-teardown-refusal", ".jar")
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            for (cls in classes) {
                val path = cls.name.replace('.', '/') + ".class"
                val bytes =
                    requireNotNull(hostLoader.getResourceAsStream(path)) { "$path missing from the test classpath" }
                        .use { it.readBytes() }
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.teardown-refusal-test"
    }
}
