package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.VersionInfo
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
 *
 * Asserted against [FluckEngine.chromiumMismatchMessage] rather than the
 * `chromiumDir` entry point: that one short-circuits on `os.name`, so a macOS-gated
 * test would assert nothing on two of the three CI legs and sleep through a
 * regression in the comparison — which is the part that decides whether an engine
 * boots.
 */
class ChromiumVersionMismatchTest {
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    /** A framework `Versions` directory carrying exactly [chromiumVersions]. */
    private fun versionsDir(vararg chromiumVersions: String): File {
        val dir = createTempDirectory("versions").toFile()
        temps.add(dir)
        chromiumVersions.forEach { File(dir, "$it/Libraries").mkdirs() }
        return dir
    }

    @Test
    fun `an engine carrying the required Chromium build is accepted`() {
        assertNull(FluckEngine.chromiumMismatchMessage(versionsDir(VersionInfo.chromiumVersion())))
    }

    @Test
    fun `a Current symlink alongside the required build does not confuse it`() {
        assertNull(
            FluckEngine.chromiumMismatchMessage(
                versionsDir(VersionInfo.chromiumVersion(), "Current"),
            ),
        )
    }

    @Test
    fun `an engine carrying a different Chromium build is refused, naming both`() {
        // The exact shape of the incident: the jar wants 151, the disk has 150.
        val message = FluckEngine.chromiumMismatchMessage(versionsDir("150.0.7871.47"))

        assertNotNull(message, "A mismatched engine must be refused before System.load")
        assertTrue(
            message.contains(VersionInfo.chromiumVersion()),
            "The message must name the Chromium build actually required",
        )
        assertTrue(message.contains("150.0.7871.47"), "and the one found on disk")
    }

    @Test
    fun `an empty Versions directory is refused rather than passed through`() {
        val message = FluckEngine.chromiumMismatchMessage(versionsDir())
        assertNotNull(message)
        assertTrue(message.contains("none"), "Nothing installed should read as 'none', not as a blank")
    }

    @Test
    fun `the message carries no filesystem path`() {
        // It reaches classifyError, which substring-matches for "host", "connect",
        // "license" and friends to choose a remedy — so a home directory containing
        // any of those would be reported as a network or licensing failure. The path
        // belongs in the log, not here.
        val dir = versionsDir("150.0.7871.47")
        val message = assertNotNull(FluckEngine.chromiumMismatchMessage(dir))
        assertFalse(
            message.contains(dir.absolutePath),
            "The engine path must not be interpolated into a message that gets substring-classified",
        )
    }
}
