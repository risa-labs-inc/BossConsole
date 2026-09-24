package ai.rever.boss.config

import ai.rever.boss.utils.ZipArchiveFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the engine install to the hardened extraction gate.
 *
 * WHY: installFromCandidates extracts whatever archive the fetch produced with the real
 * extractor, and the engine directory is where binaries the app executes live. A malicious
 * release candidate must fail the install rather than land anything outside the engine
 * directory - and the failure must happen before the version stamp exists, so a refused
 * archive can never be mistaken for an installed engine.
 *
 * The install-side atomic swap (backup, rollback and re-install of the live engine) is a
 * separate open invariant owned by PR #946 and is deliberately not tested here; these tests
 * pin only what the extraction gate refuses.
 */
class ChromiumArchiveExtractionGateTest {
    @TempDir
    lateinit var root: File

    private val target: File get() = File(root, "boss-chromium")

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { out ->
                entries.forEach { (name, content) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(content)
                    out.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun install(zipBytes: ByteArray): Result<Path> =
        runBlocking {
            ChromiumAutoDownloader.installFromCandidates(
                candidates = listOf(EngineDownloadCandidate("supabase", "https://supabase/a.zip")),
                version = "9.2.0",
                targetDir = target.toPath(),
                staged = false,
                onProgress = {},
                fetch = { _, dest -> dest.toFile().writeBytes(zipBytes) },
            )
        }

    @Test
    fun `a zip-slip archive fails the install and writes nothing outside the engine dir`() {
        val zip =
            zipBytes(
                "executable.name" to "BOSS".toByteArray(),
                "../escape.txt" to "pwned".toByteArray(),
            )

        val result = install(zip)

        assertTrue(result.isFailure, "an archive with an escaping entry must not install")
        assertFalse(File(root, "escape.txt").exists(), "nothing may land outside the engine dir")
        assertFalse(File(target, "version.txt").exists(), "a refused archive is never stamped installed")
    }

    @Test
    fun `a symlink-mode archive fails the install and installs nothing`() {
        val zip = ZipArchiveFixtures.symlinkModeEntry(File(root, "symlink.zip"), "innocent.txt")

        val result = install(zip.readBytes())

        assertTrue(result.isFailure, "an archive with a symlink entry must not install")
        assertFalse(File(target, "innocent.txt").exists(), "the link must never be materialized")
        assertFalse(File(target, "version.txt").exists(), "a refused archive is never stamped installed")
    }

    @Test
    fun `a declared-bomb archive fails the install before extraction starts`() {
        // An archive whose central directory declares past the engine limits (a bomb
        // wearing a small compressed body), written through the fixture writer because
        // ZipOutputStream always writes honest sizes.
        val crafted =
            ZipArchiveFixtures.deflatedEntryWithUnderstatedSize(
                File(root, "bomb.zip"),
                "executable.name",
                actualBytes = 16,
                // Must stay under 4 GiB: the central directory's size field is unsigned 32-bit, so a
                // 5 GiB declaration silently wraps to 1 GiB and the bomb passes as a kitten. 3 GiB
                // exceeds the 2 GiB per-entry cap while still fitting the field.
                declaredSize = 3L * 1024L * 1024L * 1024L,
            )

        val result = install(crafted.readBytes())

        assertTrue(result.isFailure, "an archive declaring past the engine limits must not install")
        assertFalse(File(target, "version.txt").exists(), "a refused archive is never stamped installed")
    }
}
