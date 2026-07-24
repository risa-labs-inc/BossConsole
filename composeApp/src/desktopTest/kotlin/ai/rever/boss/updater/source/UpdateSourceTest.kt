package ai.rever.boss.updater.source

import ai.rever.boss.updater.GitHubRelease
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the update-source seam: Supabase `app_releases` row mapping and the
 * Supabase-primary / GitHub-backup fallback behavior.
 */
class UpdateSourceTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // ---- AppReleaseRow JSON -> GitHubRelease mapping ----

    @Test
    fun `app_releases row maps to GitHubRelease with assets and sha256`() {
        val raw =
            """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "app": "boss",
              "version": "9.2.17",
              "channel": "stable",
              "prerelease": false,
              "release_notes": "Bug fixes and improvements",
              "assets": [
                {"name": "BOSS-9.2.17-Universal.dmg", "url": "https://cdn/boss/9.2.17/BOSS-9.2.17-Universal.dmg", "size": 287654321, "sha256": "deadbeef"}
              ],
              "published_at": "2026-06-30T12:00:00Z",
              "created_at": "2026-06-30T12:00:00Z"
            }
            """.trimIndent()

        val row = json.decodeFromString<AppReleaseRow>(raw)
        val release = row.toGitHubRelease()

        assertEquals("v9.2.17", release.tag_name)
        assertEquals("Bug fixes and improvements", release.body)
        assertEquals("2026-06-30T12:00:00Z", release.published_at)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertEquals(1, release.assets.size)
        val asset = release.assets.first()
        assertEquals("BOSS-9.2.17-Universal.dmg", asset.name)
        assertEquals("https://cdn/boss/9.2.17/BOSS-9.2.17-Universal.dmg", asset.browser_download_url)
        assertEquals(287654321L, asset.size)
        assertEquals("deadbeef", asset.sha256)
    }

    @Test
    fun `prerelease row maps prerelease flag`() {
        val raw =
            """
            {"app":"boss","version":"9.3.0-alpha.1","channel":"alpha","prerelease":true,"assets":[]}
            """.trimIndent()
        val release = json.decodeFromString<AppReleaseRow>(raw).toGitHubRelease()
        assertTrue(release.prerelease)
        assertEquals("v9.3.0-alpha.1", release.tag_name)
    }

    @Test
    fun `multi-asset row maps every asset and tolerates missing sha256`() {
        val raw =
            """
            {"app":"boss","version":"9.4.0","assets":[
              {"name":"BOSS-9.4.0-Universal.dmg","url":"https://x/a.dmg","size":1,"sha256":"aa"},
              {"name":"BOSS-9.4.0.msi","url":"https://x/a.msi","size":2}
            ]}
            """.trimIndent()
        val release = json.decodeFromString<AppReleaseRow>(raw).toGitHubRelease()
        assertEquals(listOf("BOSS-9.4.0-Universal.dmg", "BOSS-9.4.0.msi"), release.assets.map { it.name })
        assertEquals("aa", release.assets[0].sha256)
        assertNull(release.assets[1].sha256) // asset without sha256 stays null
    }

    // ---- FallbackUpdateSource ----

    private fun release(tag: String) =
        GitHubRelease(
            tag_name = tag,
            name = tag,
            body = "",
            published_at = "2026-01-01T00:00:00Z",
        )

    private class FakeSource(
        override val name: String,
        private val releases: () -> List<GitHubRelease>,
        private val byTag: (String) -> GitHubRelease? = { null },
    ) : UpdateSource {
        var listCalls = 0
        var tagCalls = 0

        override suspend fun listReleases(): List<GitHubRelease> {
            listCalls++
            return releases()
        }

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? {
            tagCalls++
            return byTag(tag)
        }
    }

    @Test
    fun `uses primary when it returns releases and does not call backup`() =
        runBlocking {
            val primary = FakeSource("supabase", { listOf(release("v2.0.0")) })
            val backup = FakeSource("github", { error("backup must not be called") })
            val fallback = FallbackUpdateSource(primary, backup)

            val result = fallback.listReleases()

            assertEquals(listOf("v2.0.0"), result.map { it.tag_name })
            assertEquals(1, primary.listCalls)
            assertEquals(0, backup.listCalls)
        }

    @Test
    fun `falls back to backup when primary throws`() =
        runBlocking {
            val primary = FakeSource("supabase", { throw UpdateSourceException("supabase down") })
            val backup = FakeSource("github", { listOf(release("v1.0.0")) })

            val result = FallbackUpdateSource(primary, backup).listReleases()

            assertEquals(listOf("v1.0.0"), result.map { it.tag_name })
            assertEquals(1, backup.listCalls)
        }

    @Test
    fun `falls back to backup when primary returns empty`() =
        runBlocking {
            val primary = FakeSource("supabase", { emptyList() })
            val backup = FakeSource("github", { listOf(release("v1.0.0")) })

            val result = FallbackUpdateSource(primary, backup).listReleases()

            assertEquals(listOf("v1.0.0"), result.map { it.tag_name })
            assertEquals(1, primary.listCalls)
            assertEquals(1, backup.listCalls)
        }

    @Test
    fun `returns empty when both sources fail`() =
        runBlocking {
            val primary = FakeSource("supabase", { throw UpdateSourceException("down") })
            val backup = FakeSource("github", { throw UpdateSourceException("also down") })

            val result = FallbackUpdateSource(primary, backup).listReleases()

            assertTrue(result.isEmpty())
        }

    @Test
    fun `getReleaseByTag falls back to backup when primary has none`() =
        runBlocking {
            val primary = FakeSource("supabase", { emptyList() }, byTag = { null })
            val backup = FakeSource("github", { emptyList() }, byTag = { release(it) })

            val result = FallbackUpdateSource(primary, backup).getReleaseByTag("v3.1.4")

            assertEquals("v3.1.4", result?.tag_name)
            assertEquals(1, primary.tagCalls)
            assertEquals(1, backup.tagCalls)
        }

    @Test
    fun `getReleaseByTag prefers primary`() =
        runBlocking {
            val primary = FakeSource("supabase", { emptyList() }, byTag = { release(it) })
            val backup = FakeSource("github", { emptyList() }, byTag = { error("backup must not be called") })

            val result = FallbackUpdateSource(primary, backup).getReleaseByTag("v3.1.4")

            assertEquals("v3.1.4", result?.tag_name)
            assertEquals(0, backup.tagCalls)
        }
}
