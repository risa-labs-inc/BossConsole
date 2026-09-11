package ai.rever.boss.updater

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpdateCompatibilityTest {
    @Test
    fun `platform variants map to stable catalog keys`() {
        assertEquals("macos", updateOsKey("macOS"))
        assertEquals("windows", updateOsKey("Windows"))
        assertEquals("linux", updateOsKey("Linux-deb"))
        assertEquals("linux", updateOsKey("Linux-rpm"))
        assertNull(updateOsKey("FreeBSD"))
    }

    @Test
    fun `numeric comparison rejects a current OS below the floor`() {
        assertFalse(satisfiesMinimumOsVersion("12.7.6", "13.0"))
        assertFalse(satisfiesMinimumOsVersion("13.6.9", "14"))
    }

    @Test
    fun `numeric comparison accepts equal and newer OS versions with zero padding`() {
        assertTrue(satisfiesMinimumOsVersion("13", "13.0.0"))
        assertTrue(satisfiesMinimumOsVersion("13.0.0", "13"))
        assertTrue(satisfiesMinimumOsVersion("13.1", "13.0.9"))
        assertTrue(satisfiesMinimumOsVersion("14.0", "13.9.9"))
    }

    @Test
    fun `missing or malformed version information fails open`() {
        assertTrue(satisfiesMinimumOsVersion(null, "13.0"))
        assertTrue(satisfiesMinimumOsVersion("unknown", "13.0"))
        assertTrue(satisfiesMinimumOsVersion("12.7", null))
        assertTrue(satisfiesMinimumOsVersion("12.7", "thirteen"))
        assertTrue(satisfiesMinimumOsVersion("12.7", "13.0.0.0.1"))
        assertTrue(satisfiesMinimumOsVersion("999999999999999999", "13.0"))
    }

    @Test
    fun `release applies only the current platform floor`() {
        val release = release("v9.5.14", minOs = mapOf("macos" to "13.0", "windows" to "11"))

        assertFalse(release.supportsOs("macOS", "12.7"))
        assertTrue(release.supportsOs("macOS", "13.0"))
        assertTrue(release.supportsOs("Windows", "11.0"))
        assertTrue(release.supportsOs("Linux-deb", "6.0"))
    }

    @Test
    fun `newest incompatible release cannot hide an older compatible update`() {
        val newest = release("v9.5.15", minOs = mapOf("macos" to "14.0"))
        val compatible = release("v9.5.14", minOs = mapOf("macos" to "13.0"))

        val selected =
            selectLatestCompatibleRelease(
                releases = listOf(compatible, newest),
                includePreReleases = false,
                platform = "macOS",
                currentOsVersion = "13.6.9",
            )

        assertSame(compatible, selected)
    }

    @Test
    fun `legacy row without metadata remains eligible`() {
        val legacy = release("v9.5.15")

        assertSame(
            legacy,
            selectLatestCompatibleRelease(listOf(legacy), false, "macOS", "12.7.6"),
        )
    }

    @Test
    fun `draft and prerelease filters remain intact`() {
        val stable = release("v9.5.14")
        val draft = release("v9.5.16", draft = true)
        val prerelease = release("v9.5.15-beta.1", prerelease = true)

        assertSame(
            stable,
            selectLatestCompatibleRelease(listOf(stable, draft, prerelease), false, "Windows", "11"),
        )
        assertSame(
            prerelease,
            selectLatestCompatibleRelease(listOf(stable, draft, prerelease), true, "Windows", "11"),
        )
    }

    @Test
    fun `invalid release tags are ignored`() {
        assertNull(
            selectLatestCompatibleRelease(
                releases = listOf(release("nightly")),
                includePreReleases = true,
                platform = "Linux",
                currentOsVersion = "6.8",
            ),
        )
    }

    private fun release(
        tag: String,
        minOs: Map<String, String> = emptyMap(),
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): GitHubRelease =
        GitHubRelease(
            tag_name = tag,
            name = tag,
            body = "",
            draft = draft,
            prerelease = prerelease,
            published_at = "2026-09-12T00:00:00Z",
            minimumOs = minOs,
        )
}
