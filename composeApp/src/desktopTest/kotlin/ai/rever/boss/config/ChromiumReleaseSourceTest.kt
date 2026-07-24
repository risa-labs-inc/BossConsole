package ai.rever.boss.config

import ai.rever.boss.updater.GitHubAsset
import ai.rever.boss.updater.GitHubRelease
import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.updater.source.UpdateSourceException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the engine release resolver: numeric version ordering, the
 * Supabase-primary/GitHub-backup download candidates (with sha256 threading),
 * and the merge/partial-failure behavior of the version listing.
 */
class ChromiumReleaseSourceTest {
    private class FakeSource(
        override val name: String,
        private val releases: List<GitHubRelease> = emptyList(),
        private val failing: Boolean = false,
    ) : UpdateSource {
        override suspend fun listReleases(): List<GitHubRelease> {
            if (failing) throw UpdateSourceException("$name unavailable")
            return releases
        }

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? {
            if (failing) throw UpdateSourceException("$name unavailable")
            return releases.firstOrNull { it.tag_name == tag }
        }
    }

    private fun release(
        tag: String,
        vararg assets: GitHubAsset,
    ) = GitHubRelease(
        tag_name = tag,
        name = tag,
        body = "",
        published_at = "2026-07-04T00:00:00Z",
        assets = assets.toList(),
    )

    private fun asset(
        name: String,
        url: String,
        sha256: String? = null,
    ) = GitHubAsset(name = name, browser_download_url = url, sha256 = sha256)

    // ---- version ordering ----

    @Test
    fun `versions sort numerically not lexicographically`() {
        val key = ChromiumReleaseResolver.Companion::versionKey
        val cmp = ChromiumReleaseResolver.versionComparator()

        assertTrue(cmp.compare(key("9.10.0"), key("9.9.0")) > 0)
        assertTrue(cmp.compare(key("9.9.0"), key("9.10.0")) < 0)
        assertTrue(cmp.compare(key("10.0.0"), key("9.99.99")) > 0)
        assertEquals(0, cmp.compare(key("9.1.2"), key("9.1.2")))
        // Shorter version compares as if padded with zeros
        assertTrue(cmp.compare(key("9.1"), key("9.1.1")) < 0)
    }

    @Test
    fun `stable release outranks its own pre-releases`() {
        val key = ChromiumReleaseResolver.Companion::versionKey
        val cmp = ChromiumReleaseResolver.versionComparator()

        assertTrue(cmp.compare(key("9.1.2"), key("9.1.2-2")) > 0)
        assertTrue(cmp.compare(key("9.1.2-rc1"), key("9.1.2")) < 0)
        // Pre-releases of the same version compare among themselves
        assertTrue(cmp.compare(key("9.1.2-2"), key("9.1.2-1")) > 0)
        assertTrue(cmp.compare(key("9.1.2-rc.2"), key("9.1.2-rc.1")) > 0)
        // The release part still dominates pre-release status
        assertTrue(cmp.compare(key("9.1.3-1"), key("9.1.2")) > 0)
        // Numeric identifiers sort below alphanumeric ones (semver)
        assertTrue(cmp.compare(key("9.1.2-alpha"), key("9.1.2-1")) > 0)
    }

    @Test
    fun `availableVersions returns newest first across sources with dedup`() =
        runBlocking {
            val supabase = FakeSource("supabase", listOf(release("v9.1.2"), release("v9.10.0")))
            val gitHub =
                FakeSource(
                    "github",
                    listOf(
                        release("chromium-v9.9.0"),
                        release("chromium-v9.1.2"), // duplicate of the Supabase row
                        release("v9.2.17"), // app release — must be ignored
                    ),
                )
            val listing = ChromiumReleaseResolver(supabase, gitHub).availableVersions()

            assertEquals(listOf("9.10.0", "9.9.0", "9.1.2"), listing.versions)
            assertTrue(listing.failedSources.isEmpty())
        }

    // ---- partial failure ----

    @Test
    fun `single source failure is reported not thrown`() =
        runBlocking {
            val supabase = FakeSource("supabase", listOf(release("v9.1.2")))
            val gitHub = FakeSource("github", failing = true)
            val listing = ChromiumReleaseResolver(supabase, gitHub).availableVersions()

            assertEquals(listOf("9.1.2"), listing.versions)
            assertEquals(listOf("github"), listing.failedSources)
        }

    @Test
    fun `both sources failing throws`() {
        val resolver =
            ChromiumReleaseResolver(
                FakeSource("supabase", failing = true),
                FakeSource("github", failing = true),
            )
        assertThrows<IllegalStateException> { runBlocking { resolver.availableVersions() } }
    }

    // ---- download candidates ----

    @Test
    fun `supabase candidate comes first and carries sha256, github backup has none`() =
        runBlocking {
            val supabase =
                FakeSource(
                    "supabase",
                    listOf(
                        release(
                            "v9.1.2",
                            asset(
                                "boss-chromium-macos-arm64.zip",
                                "https://cdn/app-releases/boss-chromium/9.1.2/boss-chromium-macos-arm64.zip",
                                sha256 = "d17b12664e1b",
                            ),
                        ),
                    ),
                )
            val candidates =
                ChromiumReleaseResolver(supabase, FakeSource("github"))
                    .downloadCandidates("9.1.2", "boss-chromium-macos-arm64.zip")

            assertEquals(2, candidates.size)
            assertEquals("supabase", candidates[0].sourceName)
            assertEquals("https://cdn/app-releases/boss-chromium/9.1.2/boss-chromium-macos-arm64.zip", candidates[0].url)
            assertEquals("d17b12664e1b", candidates[0].sha256)
            assertEquals("github", candidates[1].sourceName)
            assertEquals(
                "https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/" +
                    "chromium-v9.1.2/boss-chromium-macos-arm64.zip",
                candidates[1].url,
            )
            assertNull(candidates[1].sha256)
        }

    @Test
    fun `github fallback is the only candidate when supabase has no row or fails`() =
        runBlocking {
            val expectUrl =
                "https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/" +
                    "chromium-v9.1.2/boss-chromium-linux-x64.zip"

            for (supabase in listOf(
                FakeSource("supabase"), // no row
                FakeSource("supabase", failing = true), // lookup error
            )) {
                val candidates =
                    ChromiumReleaseResolver(supabase, FakeSource("github"))
                        .downloadCandidates("9.1.2", "boss-chromium-linux-x64.zip")
                assertEquals(1, candidates.size)
                assertEquals("github", candidates[0].sourceName)
                assertEquals(expectUrl, candidates[0].url)
            }
        }

    @Test
    fun `asset matching is by exact archive name`() =
        runBlocking {
            val supabase =
                FakeSource(
                    "supabase",
                    listOf(
                        release(
                            "v9.1.2",
                            asset("boss-chromium-linux-x64.zip", "https://cdn/linux-x64.zip", "aaa"),
                            asset("boss-chromium-macos-arm64.zip", "https://cdn/macos-arm64.zip", "bbb"),
                        ),
                    ),
                )
            val candidates =
                ChromiumReleaseResolver(supabase, FakeSource("github"))
                    .downloadCandidates("9.1.2", "boss-chromium-macos-arm64.zip")

            assertEquals("https://cdn/macos-arm64.zip", candidates[0].url)
            assertEquals("bbb", candidates[0].sha256)
        }
}
