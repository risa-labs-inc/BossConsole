package ai.rever.boss.config

import ai.rever.boss.utils.sha256Of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the staged-engine promotion swap: the multi-step move-aside /
 * promote / restore file logic in [ChromiumAutoDownloader.promotePendingInstall].
 */
class ChromiumAutoDownloaderTest {
    @TempDir
    lateinit var root: File

    private val pending get() = File(root, "boss-chromium.pending")
    private val target get() = File(root, "boss-chromium")
    private val backup get() = File(root, "boss-chromium.old")

    /** A staging dir as downloadChromium(staged=true) leaves it on success. */
    private fun makeCompleteStaging(version: String = "9.2.0") {
        pending.mkdirs()
        File(pending, "executable.name").writeText("BOSS")
        File(pending, "version.txt").writeText(version)
        File(pending, "payload.bin").writeText("new-engine")
        File(pending, ".staging-complete").writeText(version)
    }

    private fun makeExistingTarget(version: String = "9.1.2") {
        target.mkdirs()
        File(target, "executable.name").writeText("BOSS")
        File(target, "version.txt").writeText(version)
        File(target, "payload.bin").writeText("old-engine")
    }

    private fun promote() = ChromiumAutoDownloader.promotePendingInstall(pending, target, backup)

    @Test
    fun `complete staged install replaces the existing engine`() {
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")

        promote()

        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertEquals("new-engine", File(target, "payload.bin").readText())
        assertFalse(File(target, ".staging-complete").exists(), "commit marker should be removed after promote")
        assertFalse(pending.exists(), "staging dir should be gone")
        assertFalse(backup.exists(), "backup should be deleted after successful promote")
    }

    @Test
    fun `staging without commit marker is discarded and engine preserved`() {
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")
        File(pending, ".staging-complete").delete() // simulate interrupted extraction

        promote()

        assertFalse(pending.exists(), "incomplete staging should be discarded")
        assertEquals("9.1.2", File(target, "version.txt").readText(), "existing engine must be untouched")
        assertEquals("old-engine", File(target, "payload.bin").readText())
    }

    @Test
    fun `staging with markers from the archive but no commit marker is not promoted`() {
        // executable.name and version.txt can plausibly come from the archive itself;
        // only the strictly-last commit marker proves extraction finished.
        makeExistingTarget("9.1.2")
        pending.mkdirs()
        File(pending, "executable.name").writeText("BOSS")
        File(pending, "version.txt").writeText("9.2.0")

        promote()

        assertFalse(pending.exists())
        assertEquals("9.1.2", File(target, "version.txt").readText())
    }

    @Test
    fun `promotes onto a missing target (fresh install)`() {
        makeCompleteStaging("9.2.0")

        promote()

        assertTrue(target.exists())
        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertFalse(pending.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `stale backup from an earlier failed swap is cleaned up`() {
        backup.mkdirs()
        File(backup, "stale.bin").writeText("stale")
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")

        promote()

        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertFalse(backup.exists(), "stale backup must not survive a successful swap")
    }

    @Test
    fun `no-op when nothing is staged`() {
        makeExistingTarget("9.1.2")

        promote()

        assertEquals("9.1.2", File(target, "version.txt").readText())
        assertFalse(backup.exists())
    }

    // ---- installFromCandidates: source fallback + checksum verification ----

    private fun candidate(
        source: String,
        url: String,
        sha: String? = null,
    ) = EngineDownloadCandidate(source, url, sha)

    /** Fake extraction: produce the one file the installer verifies. */
    private val fakeExtract: (java.nio.file.Path, java.nio.file.Path) -> Unit = { _, dest ->
        dest.toFile().mkdirs()
        File(dest.toFile(), "executable.name").writeText("BOSS")
    }

    @Test
    fun `falls back to the next source when a fetch fails`() =
        runBlocking {
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("supabase", "https://supabase/a.zip"), candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        if (url.startsWith("https://supabase")) throw IllegalStateException("supabase down")
                        dest.toFile().writeText("zip-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip", "https://github/a.zip"), attempted)
            assertEquals("9.2.0", File(target, "version.txt").readText())
        }

    @Test
    fun `checksum mismatch rejects the candidate and falls through to the next source`() =
        runBlocking {
            val goodSha = sha256Of(File(root, "sha-src").apply { writeText("good-bytes") })
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates =
                        listOf(
                            candidate("supabase", "https://supabase/a.zip", sha = goodSha),
                            candidate("github", "https://github/a.zip"), // no hash available
                        ),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        // Supabase serves corrupted bytes that won't match goodSha
                        dest.toFile().writeText(if (url.startsWith("https://supabase")) "corrupted" else "good-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip", "https://github/a.zip"), attempted)
        }

    @Test
    fun `matching checksum is accepted`() =
        runBlocking {
            val goodSha = sha256Of(File(root, "sha-src").apply { writeText("good-bytes") })
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates =
                        listOf(
                            candidate("supabase", "https://supabase/a.zip", sha = goodSha),
                            candidate("github", "https://github/a.zip"),
                        ),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        dest.toFile().writeText("good-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip"), attempted, "github must not be attempted after a verified supabase download")
        }

    @Test
    fun `staged install writes the commit marker last`() =
        runBlocking {
            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = pending.toPath(),
                    staged = true,
                    onProgress = {},
                    fetch = { _, dest -> dest.toFile().writeText("zip-bytes") },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals("9.2.0", File(pending, ".staging-complete").readText())
        }

    @Test
    fun `all candidates failing returns failure and reports the error`() =
        runBlocking {
            var reportedError: String? = null

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("supabase", "https://supabase/a.zip"), candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = { p -> if (p.error != null) reportedError = p.error },
                    fetch = { _, _ -> throw IllegalStateException("network down") },
                    extract = fakeExtract,
                )

            assertTrue(result.isFailure)
            assertEquals("network down", reportedError)
            assertFalse(File(target, "version.txt").exists())
        }
}
