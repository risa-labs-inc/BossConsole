package ai.rever.boss.config

import ai.rever.boss.utils.sha256Of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EngineArchiveIntegrityVet] refuses every engine archive it cannot verify,
 * and [ChromiumAutoDownloader.installFromCandidates] installs nothing until
 * the gate accepts a candidate (the engine counterpart of the plugin update
 * jar identity vet).
 *
 * The archive this gate guards is extracted into the engine directory and its
 * binaries are later EXECUTED, yet before the gate a candidate with no pinned
 * catalog hash, the GitHub backup running while the catalog lookup is down,
 * installed with no verification at all; the fallback-checksum fix pinned that
 * exact path as "stays unverified". These tests pin the fail-closed invariant
 * instead: mismatched bytes, a truncated body, an archive whose hash cannot be
 * computed, and above all a candidate nothing pins are all refused, never
 * extracted, and the installed engine is left untouched.
 */
class EngineArchiveIntegrityVetTest {
    @TempDir
    lateinit var tempDir: Path

    private val goodBytes = "legitimate engine archive payload".repeat(64).toByteArray()

    private val tamperedBytes =
        "these bytes came from nowhere in the release pipeline".repeat(8).toByteArray()

    private val engineDir: File
        get() = File(tempDir.toFile(), "boss-chromium")

    /** A candidate whose catalog row pins the good bytes' hash. */
    private fun pinnedCandidate(source: String = "supabase") =
        EngineDownloadCandidate(
            source,
            "https://$source/boss-chromium.zip",
            sha256 = sha256OfBytes(goodBytes),
        )

    /** A candidate the catalog could not pin a hash for. */
    private fun hashlessCandidate(source: String = "github") =
        EngineDownloadCandidate(source, "https://$source/boss-chromium.zip", sha256 = null)

    private fun archiveOf(bytes: ByteArray): File =
        File(tempDir.toFile(), "archive-${bytes.contentHashCode()}.zip").apply { writeBytes(bytes) }

    private fun sha256OfBytes(bytes: ByteArray): String = sha256Of(archiveOf(bytes))

    /** A previous engine install as it looks on disk before a download attempt. */
    private fun makePreviousEngine() {
        engineDir.mkdirs()
        File(engineDir, "executable.name").writeText("BOSS")
        File(engineDir, "version.txt").writeText(PREVIOUS_VERSION)
        File(engineDir, "engine.bin").writeText("old-engine-bytes")
    }

    private fun assertPreviousEngineIntact() {
        assertEquals(
            PREVIOUS_VERSION,
            File(engineDir, "version.txt").readText(),
            "the previous engine must survive a refused install",
        )
        assertEquals("old-engine-bytes", File(engineDir, "engine.bin").readText())
        assertEquals("BOSS", File(engineDir, "executable.name").readText())
    }

    /** Fake extraction: produce the one file the installer verifies. */
    private val fakeExtract: (Path, Path) -> Unit = { _, dest ->
        dest.toFile().mkdirs()
        File(dest.toFile(), "executable.name").writeText("BOSS")
    }

    companion object {
        private const val VERSION = "9.9.9"
        private const val PREVIOUS_VERSION = "9.1.2"
    }

    // ---- the gate alone ----

    @Test
    fun `an archive matching the pinned catalog hash is accepted`() {
        val result = EngineArchiveIntegrityVet.vet(pinnedCandidate(), archiveOf(goodBytes))

        assertTrue(result.isSuccess, "bytes matching the pinned hash are verified and must install")
    }

    @Test
    fun `a tampered archive is refused with the checksum mismatch`() {
        val result = EngineArchiveIntegrityVet.vet(pinnedCandidate(), archiveOf(tamperedBytes))

        assertTrue(result.isFailure, "bytes that are not what the catalog pinned must never install")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Engine archive checksum mismatch") == true,
            "the refusal must name the mismatch, got: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `a truncated download is refused`() {
        // A connection cut mid-transfer leaves a strict prefix of the real
        // archive: the first bytes are genuine and only the tail is gone, so
        // only a byte-exact hash comparison can catch it.
        val truncated = goodBytes.copyOf(goodBytes.size / 3)

        val result = EngineArchiveIntegrityVet.vet(pinnedCandidate(), archiveOf(truncated))

        assertTrue(result.isFailure, "a partial transfer hashes differently and must be refused")
    }

    @Test
    fun `a candidate that pins no hash is refused fail closed`() {
        val result = EngineArchiveIntegrityVet.vet(hashlessCandidate(), archiveOf(goodBytes))

        assertTrue(result.isFailure, "even genuine bytes must not install when nothing pins them")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("no catalog checksum") == true,
            "the refusal must say nothing pins the archive, got: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `a blank pinned hash counts as no hash and is refused`() {
        val candidate = EngineDownloadCandidate("supabase", "https://supabase/a.zip", sha256 = "  ")

        val result = EngineArchiveIntegrityVet.vet(candidate, archiveOf(goodBytes))

        assertTrue(result.isFailure, "a whitespace-only hash pins nothing and must be refused")
    }

    @Test
    fun `an archive whose checksum cannot be computed is refused with its cause`() {
        val result =
            EngineArchiveIntegrityVet.vet(
                pinnedCandidate(),
                File(tempDir.toFile(), "never-downloaded.zip"),
            )

        assertTrue(result.isFailure, "an unreadable archive must be refused, not crash the install loop")
        assertTrue(
            result.exceptionOrNull()?.cause != null,
            "the IO failure must ride along as the refusal's cause",
        )
    }

    // ---- the gate inside installFromCandidates ----

    @Test
    fun `a hashless release reports its refusal through download progress`() =
        runBlocking {
            val progress = mutableListOf<ChromiumAutoDownloader.DownloadProgress>()
            val result =
                ChromiumAutoDownloader.downloadChromium(
                    version = VERSION,
                    staged = false,
                    onProgress = progress::add,
                    resolveCandidates = { _, _ -> listOf(hashlessCandidate()) },
                )

            assertTrue(result.isFailure)
            assertTrue(
                progress.lastOrNull()?.error?.contains("no catalog checksum") == true,
                "the first-launch setup dialog must receive the refusal",
            )
        }

    @Test
    fun `an unverifiable candidate is refused and the previous engine survives`() =
        runBlocking {
            makePreviousEngine()
            var fetched = false
            val progress = mutableListOf<ChromiumAutoDownloader.DownloadProgress>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(hashlessCandidate()),
                    version = VERSION,
                    targetDir = engineDir.toPath(),
                    staged = false,
                    onProgress = progress::add,
                    fetch = { _, _ -> fetched = true },
                    extract = { _, _ -> error("a refused archive must never be extracted") },
                )

            assertTrue(result.isFailure, "a candidate nothing pins must be refused, not installed")
            assertTrue(
                result.exceptionOrNull()?.message?.contains("no catalog checksum") == true,
                "got: ${result.exceptionOrNull()?.message}",
            )
            assertPreviousEngineIntact()
            assertTrue(!fetched, "a hashless archive must be refused before download")
            assertTrue(
                progress.lastOrNull()?.error?.contains("no catalog checksum") == true,
                "the setup dialog must receive the refusal through download progress",
            )
        }

    @Test
    fun `hashless staged candidate leaves no pending marker or download`() =
        runBlocking {
            val pending = File(tempDir.toFile(), "boss-chromium-pending")
            var fetched = false

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(hashlessCandidate()),
                    version = VERSION,
                    targetDir = pending.toPath(),
                    staged = true,
                    onProgress = {},
                    fetch = { _, _ -> fetched = true },
                    extract = { _, _ -> error("a refused archive must never be extracted") },
                )

            assertTrue(result.isFailure)
            assertTrue(!fetched)
            assertTrue(!pending.exists())
        }

    @Test
    fun `a corrupt primary falls through to a verifying candidate`() =
        runBlocking {
            makePreviousEngine()
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(pinnedCandidate("supabase"), pinnedCandidate("github")),
                    version = VERSION,
                    targetDir = engineDir.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        // The primary serves tampered bytes; the backup serves
                        // the archive the catalog's hash pins.
                        dest.toFile().writeBytes(
                            if (url.startsWith("https://supabase")) tamperedBytes else goodBytes,
                        )
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess, "a refused candidate must fall through to a verifying one")
            assertEquals(
                listOf("https://supabase/boss-chromium.zip", "https://github/boss-chromium.zip"),
                attempted,
                "both candidates must have been tried",
            )
            assertEquals(VERSION, File(engineDir, "version.txt").readText())
        }

    @Test
    fun `a corrupt primary is cleaned up and a hashless backup is skipped`() =
        runBlocking {
            makePreviousEngine()
            val attempted = mutableListOf<String>()
            var tempArchive: Path? = null

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(pinnedCandidate("supabase"), hashlessCandidate()),
                    version = VERSION,
                    targetDir = engineDir.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        tempArchive = dest
                        dest.toFile().writeBytes(tamperedBytes)
                    },
                    extract = { _, _ -> error("a refused archive must never be extracted") },
                )

            assertTrue(result.isFailure)
            assertEquals(listOf("https://supabase/boss-chromium.zip"), attempted)
            assertTrue(tempArchive?.toFile()?.exists() == false, "the refused download must be deleted")
            assertPreviousEngineIntact()
        }

    @Test
    fun `a truncated install attempt is refused and never extracted`() =
        runBlocking {
            makePreviousEngine()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(pinnedCandidate()),
                    version = VERSION,
                    targetDir = engineDir.toPath(),
                    staged = false,
                    onProgress = {},
                    // A connection cut mid-transfer: a strict prefix of the archive.
                    fetch = { _, dest ->
                        dest.toFile().writeBytes(goodBytes.copyOf(goodBytes.size / 3))
                    },
                    extract = { _, _ -> error("a truncated archive must never be extracted") },
                )

            assertTrue(result.isFailure, "a truncated download must be refused")
            assertPreviousEngineIntact()
        }
}
