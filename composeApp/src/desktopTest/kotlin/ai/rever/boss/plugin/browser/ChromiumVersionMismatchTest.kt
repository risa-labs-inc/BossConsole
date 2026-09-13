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
    /**
     * Keep the expected version independent of the developer's engine override.
     */
    private val requiredVersion = "9.9.9-test"

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
    fun `the usability predicate alone cannot police the cache off macOS`() {
        // Pins WHY resolveEngineDir gates the cache on isChromiumInstalled() rather
        // than relying on the predicate. frameworkVersionsDir returns null off
        // macOS by design, so chromiumMismatchMessage makes no claim there and the
        // predicate collapses to "executable.name exists" — a stale Windows/Linux
        // cache would sail through, pre-warm against the wrong engine, and bring
        // back the UnsatisfiedLinkError. version.txt is the only cross-platform
        // version signal, and isChromiumInstalled is the only thing that reads it.
        val stale = engineBundle("150.0.7871.47")

        if (isMac) {
            assertNotNull(
                FluckEngine.chromiumVersionMismatch(stale),
                "On macOS the framework layout is readable, so the predicate does catch it",
            )
        } else {
            assertNull(
                FluckEngine.chromiumVersionMismatch(stale),
                "Off macOS the predicate cannot tell - which is exactly why the cache " +
                    "candidate must be gated on isChromiumInstalled() instead",
            )
        }
    }

    @Test
    fun `an unhealthy cache is not offered as a candidate`() {
        // The regression this PR's first cut shipped: swapping isChromiumInstalled()
        // for the usability predicate removed the version check on Windows and Linux
        // entirely, because frameworkVersionsDir makes no claim off macOS. Runs on
        // every leg — the point is that the gate is applied at all, not what it
        // decides.
        val cache = engineBundle("150.0.7871.47")

        assertEquals(
            emptyList(),
            FluckEngine.engineCandidates(bundled = null, cache = cache, cacheHealthy = false),
            "A cache that isChromiumInstalled() rejects must not be a candidate",
        )
        assertEquals(
            listOf(cache),
            FluckEngine.engineCandidates(bundled = null, cache = cache, cacheHealthy = true),
            "A healthy cache must still be offered",
        )
    }

    @Test
    fun `the bundled engine outranks the cache`() {
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        val cache = engineBundle(VersionInfo.chromiumVersion())

        assertEquals(
            listOf(bundled, cache),
            FluckEngine.engineCandidates(bundled = bundled, cache = cache, cacheHealthy = true),
        )
    }

    @Test
    fun `a corrupt cache is still downloaded, not written off as archive skew`() {
        // The regression this PR's second cut shipped. Suppressing the download on
        // the version stamp alone caught states a re-download DOES repair — a
        // missing executable.name, or a macOS binary that lost its execute bit —
        // because version.txt still reads correctly in both. That turned ordinary
        // local corruption into a terminal state with no in-app way out.
        assertEquals(
            FluckEngine.EngineStartupAction.Download,
            FluckEngine.engineStartupAction(hasUsableEngine = false, cacheHealthy = false),
        )
    }

    @Test
    fun `a healthy cache that is still unusable means archive skew, not a re-download`() {
        // Healthy and stamped with the version we would fetch, yet unusable: the
        // published archive does not carry the build this jar needs. Re-fetching
        // would download hundreds of MB every launch and never converge.
        assertEquals(
            FluckEngine.EngineStartupAction.BootAndReport,
            FluckEngine.engineStartupAction(hasUsableEngine = true.not(), cacheHealthy = true),
        )
    }

    @Test
    fun `a usable engine just boots`() {
        assertEquals(
            FluckEngine.EngineStartupAction.Boot,
            FluckEngine.engineStartupAction(hasUsableEngine = true, cacheHealthy = false),
        )
        assertEquals(
            FluckEngine.EngineStartupAction.Boot,
            FluckEngine.engineStartupAction(hasUsableEngine = true, cacheHealthy = true),
        )
    }

    /** Writes a version stamp into [dir], as packaging and the downloader both do. */
    private fun stamp(
        dir: java.nio.file.Path,
        version: String,
    ) = File(dir.toFile(), "version.txt").writeText(version)

    @Test
    fun `a bundled engine stamped with a different version is not a candidate`() {
        // The #123 fix. Off macOS the framework probe makes no claim, so before
        // packaging wrote a stamp a stale BUNDLED engine won first priority with no
        // check at all — and could not be repaired, because the download writes to
        // the cache the resolver then never reached. Runs on every leg: the stamp is
        // the only cross-platform version signal, which is the whole point.
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, "0.0.0-not-this-build")

        assertEquals(
            emptyList(),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = bundled,
                cacheHealthy = false,
                required = requiredVersion,
            ),
            "A bundled engine stamped for a different build must be skipped",
        )
    }

    @Test
    fun `a bundled engine stamped with the required version is kept`() {
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, requiredVersion)

        assertEquals(
            listOf(bundled),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = bundled,
                cacheHealthy = false,
                required = requiredVersion,
            ),
        )
    }

    @Test
    fun `an unstamped bundled engine is still allowed through`() {
        // Deliberately more lenient than the cache, which treats a missing stamp as
        // a broken extraction. App images built before packaging stamped carry no
        // version.txt, and refusing those would make every such user re-download
        // for no reason — so "can't tell" stays fail-open, matching the framework
        // probe's rule.
        val bundled = engineBundle(VersionInfo.chromiumVersion())

        assertEquals(
            listOf(bundled),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = bundled,
                cacheHealthy = false,
                required = requiredVersion,
            ),
        )
    }

    @Test
    fun `the diagnosis can still see an engine the selection rule rejected`() {
        // engineLocations is deliberately unfiltered. Deriving the "no usable
        // engine" reason from the candidate list instead would hide a stale
        // bundled engine and report "not found" while it sat right there — the
        // wrong-message problem #122 removed, reintroduced for the bundled case.
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, "0.0.0-not-this-build")
        val cache = engineBundle(VersionInfo.chromiumVersion())

        assertEquals(
            emptyList(),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = cache,
                cacheHealthy = false,
                required = requiredVersion,
            ),
            "precondition: the stale bundled engine is rejected as a candidate",
        )
        assertEquals(
            listOf(bundled, cache),
            FluckEngine.engineLocations(bundled = bundled, cache = cache),
            "but both remain visible to the diagnosis",
        )
    }

    @Test
    fun `a present-but-stale engine is never reported as not found`() {
        // The defect this PR's own review caught: deriving the reason from the
        // FILTERED candidate list hid a rejected bundled engine, so the user was
        // told "BOSS-branded Chromium not found" while it sat right there. Asserts
        // on the reason itself rather than on the helpers, because swapping which
        // list it reads is invisible to a helper-level test — verified: it was.
        val stale = engineBundle("150.0.7871.47")

        val reason = FluckEngine.noUsableEngineReason(listOf(stale))

        assertFalse(
            reason.contains("not found"),
            "An engine that is present and merely stale must not be reported as missing",
        )
        if (isMac) {
            assertTrue(
                reason.contains("150.0.7871.47"),
                "On macOS the framework layout is readable, so name the build found",
            )
        }
    }

    @Test
    fun `a genuinely absent engine is still reported as not found`() {
        assertTrue(FluckEngine.noUsableEngineReason(emptyList()).contains("not found"))
    }

    @Test
    fun `a stale-stamped bundled engine yields to the downloaded cache`() {
        // The behaviour #123 is actually about, which the filter tests did not
        // reach: they all passed cacheHealthy = false and asserted an empty list,
        // so none showed the repair path working. Runs on every leg — the cache is
        // gated on cacheHealthy, not on the macOS framework probe.
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, "0.0.0-not-this-build")
        val cache = engineBundle(VersionInfo.chromiumVersion())
        stamp(cache, requiredVersion)

        assertEquals(
            listOf(cache),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = cache,
                cacheHealthy = true,
                required = requiredVersion,
            ),
            "A stale bundled engine must step aside so the downloaded cache can be used",
        )
    }

    @Test
    fun `a trailing newline in the stamp is tolerated`() {
        // echo -n is tidy, not load-bearing: installedVersionAt trims. Pinning it
        // means a future bundling site using plain echo does not silently produce a
        // stamp that never matches.
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, "$requiredVersion\n")

        assertEquals(
            listOf(bundled),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = bundled,
                cacheHealthy = false,
                required = requiredVersion,
            ),
        )
    }

    @Test
    fun `a BOM-prefixed stamp is not accepted`() {
        // Why the PowerShell site uses WriteAllText rather than Set-Content: U+FEFF
        // is not Character.isWhitespace, so trim() does not remove it and the
        // comparison fails. Without this the reason lives only in a workflow comment.
        val bundled = engineBundle(VersionInfo.chromiumVersion())
        stamp(bundled, "\uFEFF$requiredVersion")

        assertEquals(
            emptyList(),
            FluckEngine.engineCandidates(
                bundled = bundled,
                cache = bundled,
                cacheHealthy = false,
                required = requiredVersion,
            ),
            "A BOM-prefixed stamp must not silently pass as a match",
        )
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
