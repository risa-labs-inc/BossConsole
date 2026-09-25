package ai.rever.boss.plugin.browser

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The preload must name exactly the files JxBrowser will `System.load` later - the same
 * `Versions/<chromium>/Libraries` directory `FluckEngine.chromiumVersionMismatch` checks - or the
 * JVM would load a second copy from another path, and with it a second allocator zone swap. And
 * it must never throw, since it sits on the startup path.
 */
class ChromiumToolkitPreloadTest {
    private val version = "152.0.7977.65"
    private val created = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        created.forEach { it.toFile().deleteRecursively() }
        System.clearProperty("boss.toolkit.preload")
    }

    private fun engine(
        version: String,
        vararg libs: String,
        executableName: String? = "BOSS",
    ): Path {
        val dir = Files.createTempDirectory("engine").also { created.add(it) }
        val libraries =
            dir.resolve("BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/$version/Libraries")
        Files.createDirectories(libraries)
        libs.forEach { Files.createFile(libraries.resolve(it)) }
        executableName?.let { dir.resolve("executable.name").toFile().writeText("$it\n") }
        return dir
    }

    private fun plan(
        dir: Path,
        name: String? = "BOSS",
        isMac: Boolean = true,
        chromium: String = version,
    ) = ChromiumToolkitPreload.plan(dir, isMac, { name }, chromium)

    private fun skipReason(plan: ChromiumToolkitPreload.Plan) = assertIs<ChromiumToolkitPreload.Plan.Skip>(plan).reason

    @Test
    fun `resolves toolkit then ipc under the jar's chromium version`() {
        val dir = engine(version, "libtoolkit.dylib", "libipc.dylib", "libawt_toolkit.dylib")
        val files = assertIs<ChromiumToolkitPreload.Plan.Load>(plan(dir)).files
        assertEquals(listOf("libtoolkit.dylib", "libipc.dylib"), files.map { it.name })
        // invariantSeparatorsPath: the rule must hold on the Windows runner too, where File.path uses "\\".
        assertTrue(files.all { it.invariantSeparatorsPath.contains("/Versions/$version/Libraries/") })
    }

    @Test
    fun `awt_toolkit is never preloaded because it links libjawt`() {
        assertFalse("libawt_toolkit.dylib" in ChromiumToolkitPreload.PRELOADED_LIBRARIES)
    }

    @Test
    fun `each reason to skip is named`() {
        val ok = engine(version, "libtoolkit.dylib", "libipc.dylib")
        assertEquals("not macOS", skipReason(plan(ok, isMac = false)))
        assertEquals("no usable executable.name", skipReason(plan(ok, name = null)))
        assertEquals("no usable executable.name", skipReason(plan(ok, name = "  ")))
        val mismatched = engine("151.0.7922.138", "libtoolkit.dylib", "libipc.dylib")
        assertTrue(skipReason(plan(mismatched)).startsWith("engine does not carry"))
        val partial = engine(version, "libtoolkit.dylib")
        assertTrue(skipReason(plan(partial)).startsWith("engine does not carry"))
    }

    @Test
    fun `off macOS the executable name is never read`() {
        var read = false
        ChromiumToolkitPreload.plan(engine(version), isMac = false, executableName = {
            read = true
            "BOSS"
        }, chromiumVersion = version)
        assertFalse(read)
    }

    @Test
    fun `an executable name that could escape the engine directory is refused`() {
        val dir = engine(version, "libtoolkit.dylib", "libipc.dylib")
        listOf("../BOSS", "a/b", "..", "BOSS\\x").forEach { name ->
            assertIs<ChromiumToolkitPreload.Plan.Skip>(plan(dir, name = name), name)
        }
    }

    @Test
    fun `off switch accepts the usual falsy spellings and ignores a blank env`() {
        assertTrue(ChromiumToolkitPreload.disabledFrom("false", null))
        assertTrue(ChromiumToolkitPreload.disabledFrom(" OFF ", null))
        assertTrue(ChromiumToolkitPreload.disabledFrom("", "0"))
        assertFalse(ChromiumToolkitPreload.disabledFrom(null, null))
        assertFalse(ChromiumToolkitPreload.disabledFrom("true", "false"))
    }

    @Test
    fun `a missing engine directory preloads nothing`() {
        assertEquals(0, ChromiumToolkitPreload.preload(null) { error("must not load") })
    }

    @Test
    fun `the system property switches the preload off`() {
        System.setProperty("boss.toolkit.preload", "false")
        val dir = engine(version, "libtoolkit.dylib", "libipc.dylib")
        assertEquals(0, ChromiumToolkitPreload.preload(dir) { error("must not load") })
    }

    @Test
    fun `on macOS it loads in order, stops at the first failure, and never throws`() {
        if (!System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        ) {
            return
        }
        // The real jar's Chromium build, so the plan resolves against this fixture.
        val jarChromium =
            com.teamdev.jxbrowser.VersionInfo
                .chromiumVersion()
        val dir = engine(jarChromium, "libtoolkit.dylib", "libipc.dylib")

        val loaded = mutableListOf<String>()
        assertEquals(2, ChromiumToolkitPreload.preload(dir) { loaded += File(it).name })
        assertEquals(listOf("libtoolkit.dylib", "libipc.dylib"), loaded)

        loaded.clear()
        val count =
            ChromiumToolkitPreload.preload(dir) {
                loaded += File(it).name
                throw UnsatisfiedLinkError("simulated")
            }
        assertEquals(0, count)
        assertEquals(listOf("libtoolkit.dylib"), loaded, "stops after the first failure")

        assertEquals(0, ChromiumToolkitPreload.preload(dir) { throw IllegalStateException("boom") })
    }
}
