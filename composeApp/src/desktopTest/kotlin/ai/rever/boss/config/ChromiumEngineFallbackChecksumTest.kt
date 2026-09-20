package ai.rever.boss.config

import ai.rever.boss.updater.GitHubAsset
import ai.rever.boss.updater.GitHubRelease
import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.utils.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end regression tests for the GitHub-backup engine archive checksum —
 * the Chromium engine downloader gap BossConsole#798 recorded in its own body.
 *
 * [ChromiumReleaseResolver.downloadCandidates] used to build its GitHub backup
 * candidate with `sha256 = null`, so the one engine download that runs
 * precisely because the primary source was already misbehaving was extracted —
 * and its binaries later EXECUTED as the browser engine — with no integrity
 * check at all. The backup fetches the same archive of the same version the
 * catalog row describes, and the release pipeline publishes one artifact to
 * both sources, so the catalog's hash must bind the backup's bytes exactly as
 * it binds the primary's (mirroring the app updater's #798 fallback fix).
 *
 * Each test drives the real chain — [ChromiumReleaseResolver.downloadCandidates]
 * into [ChromiumAutoDownloader.installFromCandidates] with the production HTTP
 * fetch — against a local HTTP server standing in for BossConsole-Releases,
 * with the primary URL on a dead port so only the backup can serve the
 * archive. Restoring `sha256 = null` at the backup candidate lets the tampered
 * body install, failing the first test; the candidate threading itself is
 * pinned in [ChromiumReleaseSourceTest].
 */
class ChromiumEngineFallbackChecksumTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer

    /** The bytes the backup server serves — tests rewrite this per case. */
    private var servedBytes: ByteArray = ByteArray(0)

    private val goodBytes = "legitimate engine archive payload".repeat(64).toByteArray()

    private val tamperedBytes = "these bytes came from nowhere in the release pipeline".repeat(8).toByteArray()

    /** Paths the local server has served, so tests can prove which candidate fetched. */
    private val requestedPaths: MutableList<String> = CopyOnWriteArrayList()

    private val engineDir: File
        get() = File(tempDir.toFile(), "boss-chromium")

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.path
            exchange.sendResponseHeaders(200, servedBytes.size.toLong())
            exchange.responseBody.use { it.write(servedBytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    /** A port with nothing listening on it, so the primary download fails fast. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** SHA-256 of [bytes] via the production hashing util, computed off a temp file. */
    private fun sha256OfBytes(bytes: ByteArray): String =
        sha256Of(
            File(tempDir.toFile(), "sha-src-${bytes.contentHashCode()}").apply {
                writeBytes(bytes)
            },
        )

    /** Fake catalog source serving one release row (the Supabase stand-in). */
    private class FakeCatalogSource(
        private val release: GitHubRelease?,
    ) : UpdateSource {
        override val name: String = "supabase"

        override suspend fun listReleases(): List<GitHubRelease> = listOfNotNull(release)

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? = release?.takeIf { it.tag_name == tag }
    }

    // The GitHub source is never queried for download candidates — the backup
    // URL is constructed — so any stub satisfies the resolver's second source.
    private object FakeGitHubSource : UpdateSource {
        override val name: String = "github"

        override suspend fun listReleases(): List<GitHubRelease> = emptyList()

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? = null
    }

    /** A catalog row describing the archive at [url], optionally with its hash. */
    private fun catalogRow(
        url: String,
        sha256: String?,
    ): GitHubRelease =
        GitHubRelease(
            tag_name = "v$VERSION",
            name = VERSION,
            body = "",
            published_at = "2026-09-17T00:00:00Z",
            assets =
                listOf(
                    GitHubAsset(
                        name = ARCHIVE_NAME,
                        browser_download_url = url,
                        sha256 = sha256,
                    ),
                ),
        )

    /**
     * Run the production chain over [catalog]: resolve the real candidates
     * (primary first, constructed GitHub backup second), then install with the
     * production HTTP fetch. Only the extraction is faked — it produces the one
     * file the installer verifies — so every byte of the transfer/verify/discard
     * path that runs is production code.
     */
    private fun installViaBackupChain(catalog: UpdateSource): Result<Path> =
        runBlocking {
            val candidates =
                ChromiumReleaseResolver(
                    supabaseSource = catalog,
                    gitHubSource = FakeGitHubSource,
                    // Point the constructed backup base at the local stand-in server
                    // so the production HTTP fetch can actually reach the backup.
                    gitHubReleasesBase = "http://127.0.0.1:${server.address.port}",
                ).downloadCandidates(VERSION, ARCHIVE_NAME)
            ChromiumAutoDownloader.installFromCandidates(
                candidates = candidates,
                version = VERSION,
                targetDir = engineDir.toPath(),
                staged = false,
                onProgress = {},
                extract = fakeExtract,
            )
        }

    /** Fake extraction: produce the one file the installer verifies. */
    private val fakeExtract: (Path, Path) -> Unit = { _, dest ->
        dest.toFile().mkdirs()
        File(dest.toFile(), "executable.name").writeText("BOSS")
    }

    /** A previous engine install as it looks on disk before a download attempt. */
    private fun makePreviousEngine() {
        engineDir.mkdirs()
        File(engineDir, "executable.name").writeText("BOSS")
        File(engineDir, "version.txt").writeText(PREVIOUS_VERSION)
        File(engineDir, "engine.bin").writeText("old-engine-bytes")
    }

    private fun assertPreviousEngineIntact() {
        assertEquals(PREVIOUS_VERSION, File(engineDir, "version.txt").readText(), "previous engine must be untouched")
        assertEquals("old-engine-bytes", File(engineDir, "engine.bin").readText())
        assertEquals("BOSS", File(engineDir, "executable.name").readText())
    }

    companion object {
        private const val VERSION = "9.9.9"
        private const val PREVIOUS_VERSION = "9.1.2"
        private const val ARCHIVE_NAME = "boss-chromium-linux-x64.zip"
        private const val BACKUP_PATH = "/chromium-v$VERSION/$ARCHIVE_NAME"
    }

    @Test
    fun `a backup body that mismatches the catalog sha256 is discarded and the previous engine survives`() {
        servedBytes = tamperedBytes
        makePreviousEngine()
        val catalog =
            FakeCatalogSource(
                // Primary on a dead port: only the backup can serve the archive.
                catalogRow(
                    url = "http://127.0.0.1:${deadPort()}/primary/$ARCHIVE_NAME",
                    sha256 = sha256OfBytes(goodBytes),
                ),
            )

        val result = installViaBackupChain(catalog)

        assertTrue(result.isFailure, "a tampered backup body must never install")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Engine archive checksum mismatch") == true,
            "the failure must come from the checksum gate, got: ${result.exceptionOrNull()?.message}",
        )
        assertPreviousEngineIntact()
        assertEquals(listOf(BACKUP_PATH), requestedPaths, "the archive must have been fetched from the backup")
    }

    @Test
    fun `a backup body that matches the catalog sha256 installs`() {
        servedBytes = goodBytes
        val catalog =
            FakeCatalogSource(
                catalogRow(
                    url = "http://127.0.0.1:${deadPort()}/primary/$ARCHIVE_NAME",
                    sha256 = sha256OfBytes(goodBytes),
                ),
            )

        val result = installViaBackupChain(catalog)

        assertTrue(result.isSuccess, "a matching backup body must install")
        assertEquals(VERSION, File(engineDir, "version.txt").readText())
        assertTrue(File(engineDir, "executable.name").exists(), "the engine must be extracted, not just downloaded")
        assertEquals(listOf(BACKUP_PATH), requestedPaths)
    }

    @Test
    fun `a backup body with no catalog checksum to pin it is refused fail closed`() {
        // Rows published before the hash column, and lookups that fail (pinned
        // in ChromiumReleaseSourceTest), have no hash to describe the archive
        // with. The fallback fix let the backup proceed unverified and pinned
        // that as its boundary; the engine integrity gate now refuses instead,
        // mirroring the plugin update jar identity vet. The archive is
        // extracted into the engine directory and its binaries are later
        // EXECUTED, so when nothing pins its bytes it must not install: the
        // attempt fails and can be retried once the catalog can vouch for the
        // archive again.
        servedBytes = goodBytes
        val catalog =
            FakeCatalogSource(
                catalogRow(
                    url = "http://127.0.0.1:${deadPort()}/primary/$ARCHIVE_NAME",
                    sha256 = null,
                ),
            )

        val result = installViaBackupChain(catalog)

        assertTrue(result.isFailure, "a hash-less catalog row must not install unverified bytes")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("no catalog checksum") == true,
            "the refusal must say nothing pins the archive, got: ${result.exceptionOrNull()?.message}",
        )
        assertEquals(listOf(BACKUP_PATH), requestedPaths)
    }

    @Test
    fun `a working primary with a matching hash installs without touching the backup`() {
        servedBytes = goodBytes
        val catalog =
            FakeCatalogSource(
                catalogRow(
                    url = "http://127.0.0.1:${server.address.port}/primary/$ARCHIVE_NAME",
                    sha256 = sha256OfBytes(goodBytes),
                ),
            )

        val result = installViaBackupChain(catalog)

        assertTrue(result.isSuccess, "the verified primary path must keep installing")
        assertEquals(VERSION, File(engineDir, "version.txt").readText())
        assertEquals(
            listOf("/primary/$ARCHIVE_NAME"),
            requestedPaths,
            "the backup must not be fetched after a verified primary download",
        )
    }
}
