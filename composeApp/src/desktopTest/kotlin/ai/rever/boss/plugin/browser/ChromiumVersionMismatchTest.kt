package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.VersionInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the preflight that turns an engine/library mismatch into a legible failure.
 *
 * Shipping JxBrowser 9.4.0 while users still had the 9.3.0 engine on disk produced
 * `UnsatisfiedLinkError: Can't load library: .../Versions/150.0.7871.47/Libraries/libtoolkit.dylib`
 * — a path with no cause — because JxBrowser resolves its native toolkit under a
 * directory named after the Chromium build compiled into the jar. Startup checks the
 * installed version before booting anything, but that ordering is a convention, and
 * this is what catches it when the convention breaks.
 */
class ChromiumVersionMismatchTest {
    private val temps = mutableListOf<File>()
    private val isMac =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    /** An engine directory laid out the way the branded bundle is, carrying [chromiumVersion]. */
    private fun engineDir(chromiumVersion: String?): File {
        val dir = createTempDirectory("engine").toFile()
        temps.add(dir)
        File(dir, "executable.name").writeText("BOSS")
        if (chromiumVersion != null) {
            File(
                dir,
                "BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/$chromiumVersion/Libraries",
            ).mkdirs()
        }
        return dir
    }

    @Test
    fun `an engine carrying the required Chromium build is accepted`() {
        assumeTrue(isMac, "The Versions/<chromium> layout is macOS-specific")
        val dir = engineDir(VersionInfo.chromiumVersion())
        assertNull(FluckEngine.chromiumVersionMismatch(dir.toPath()))
    }

    @Test
    fun `an engine carrying a different Chromium build is refused, naming both`() {
        assumeTrue(isMac, "The Versions/<chromium> layout is macOS-specific")
        // The exact shape of the incident: jar wants 151, disk has 150.
        val dir = engineDir("150.0.7871.47")

        val message = FluckEngine.chromiumVersionMismatch(dir.toPath())

        assertNotNull(message, "A mismatched engine must be refused before System.load")
        assertTrue(
            message.contains(VersionInfo.chromiumVersion()),
            "The message must name the Chromium build actually required",
        )
        assertTrue(message.contains("150.0.7871.47"), "and the one found on disk")
    }

    @Test
    fun `an unrecognised layout makes no claim`() {
        assumeTrue(isMac, "The Versions/<chromium> layout is macOS-specific")
        // Fail open rather than block a directory shape this check does not model —
        // refusing to boot on a guess would be worse than the error it prevents.
        assertNull(FluckEngine.chromiumVersionMismatch(engineDir(null).toPath()))
    }

    @Test
    fun `a directory with no executable name makes no claim`() {
        val dir = createTempDirectory("engine-bare").toFile()
        temps.add(dir)
        assertNull(FluckEngine.chromiumVersionMismatch(dir.toPath()))
    }
}
