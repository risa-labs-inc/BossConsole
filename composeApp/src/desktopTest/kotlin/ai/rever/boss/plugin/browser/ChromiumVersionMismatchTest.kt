package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.VersionInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `the full bundle layout resolves through the chromiumDir entry point`() {
        // Splitting the comparison out left frameworkVersionsDir — the layout
        // composition — untested on every leg. It assumes executable.name holds the
        // bundle name WITHOUT its suffix and appends ".app"; if that ever drifts the
        // preflight silently degrades to a no-op (null, no claim made) and the
        // defence-in-depth disappears with nothing failing. Genuinely macOS-only, so
        // skipping on the other two legs is honest here.
        assumeTrue(
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac"),
            "The Versions/<chromium> bundle layout is macOS-specific",
        )
        val engine = createTempDirectory("engine").toFile()
        temps.add(engine)
        File(engine, "executable.name").writeText("BOSS")
        File(
            engine,
            "BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/150.0.7871.47/Libraries",
        ).mkdirs()

        val message = FluckEngine.chromiumVersionMismatch(engine.toPath())

        assertNotNull(message, "A stale engine must be refused through the real entry point")
        assertTrue(message.contains("150.0.7871.47"))
    }

    @Test
    fun `an unusable candidate is skipped so a download can repair it`() {
        // The reason the version check lives inside the resolver rather than acting
        // as a veto afterwards. ChromiumAutoDownloader writes only to the cache, so
        // if a stale BUNDLED engine won first priority unconditionally, every
        // download would land somewhere the resolver then ignored and the repair
        // path could never repair anything (BossConsole#121).
        //
        // Expressed against the predicate the resolver uses, so it holds on every
        // leg: a directory carrying the wrong Chromium build must not be treated as
        // usable, while one carrying the right build must.
        val stale = versionsDir("150.0.7871.47")
        val good = versionsDir(VersionInfo.chromiumVersion())

        assertNotNull(
            FluckEngine.chromiumMismatchMessage(stale),
            "A stale candidate must be rejected, not chosen and then vetoed",
        )
        assertNull(
            FluckEngine.chromiumMismatchMessage(good),
            "A matching candidate must remain selectable",
        )
    }

    /** A full engine bundle at [chromiumVersion], the shape getChromiumDir returns. */
    private fun engineBundle(chromiumVersion: String): java.nio.file.Path {
        val dir = createTempDirectory("candidate").toFile()
        temps.add(dir)
        File(dir, "executable.name").writeText("BOSS")
        File(
            dir,
            "BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/$chromiumVersion/Libraries",
        ).mkdirs()
        return dir.toPath()
    }

    @Test
    fun `a stale first candidate is skipped in favour of a usable later one`() {
        assumeTrue(isMac, "Candidate usability is decided by the macOS-only framework layout")
        // This is the #121 fix. ChromiumAutoDownloader writes only to the cache, so
        // if a stale BUNDLED engine won first priority unconditionally, every
        // download would land somewhere the resolver ignored and the repair path
        // could never repair anything. Order matters: stale first, good second.
        val stale = engineBundle("150.0.7871.47")
        val good = engineBundle(VersionInfo.chromiumVersion())

        assertEquals(good, FluckEngine.firstUsableEngineDir(listOf(stale, good)))
    }

    @Test
    fun `priority still holds when the first candidate is usable`() {
        assumeTrue(isMac, "Candidate usability is decided by the macOS-only framework layout")
        // Skipping must be driven by usability, not by preferring the last entry.
        val firstGood = engineBundle(VersionInfo.chromiumVersion())
        val secondGood = engineBundle(VersionInfo.chromiumVersion())

        assertEquals(firstGood, FluckEngine.firstUsableEngineDir(listOf(firstGood, secondGood)))
    }

    @Test
    fun `no usable candidate yields null rather than a stale one`() {
        assumeTrue(isMac, "Candidate usability is decided by the macOS-only framework layout")
        assertNull(FluckEngine.firstUsableEngineDir(listOf(engineBundle("150.0.7871.47"))))
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
