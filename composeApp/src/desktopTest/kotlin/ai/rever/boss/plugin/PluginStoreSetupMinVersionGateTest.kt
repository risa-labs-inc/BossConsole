package ai.rever.boss.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the system-plugin minVersion gate (editor-tab 1.4.0 bundling
 * BossEditor after the host dropped it): [PluginStoreSetup.isTooOldForHost]
 * decides whether the installed JAR must be replaced before load,
 * [PluginStoreSetup.isNewerVersion] is the comparator behind it (including
 * the release-vs-pre-release tiebreak), and [PluginStoreSetup.pickPluginJarUrl]
 * must never select a "-thin.jar" release asset (a module's default :jar
 * output, missing everything buildPluginJar bundles).
 */
class PluginStoreSetupMinVersionGateTest {

    // ---- isTooOldForHost: the load/update decision ----

    @Test
    fun `no minimum means never too old`() {
        assertFalse(PluginStoreSetup.isTooOldForHost("0.0.1", null))
        assertFalse(PluginStoreSetup.isTooOldForHost(null, null))
    }

    @Test
    fun `version equal to minimum loads`() {
        assertFalse(PluginStoreSetup.isTooOldForHost("1.4.0", "1.4.0"))
    }

    @Test
    fun `version above minimum loads`() {
        assertFalse(PluginStoreSetup.isTooOldForHost("1.4.1", "1.4.0"))
        assertFalse(PluginStoreSetup.isTooOldForHost("2.0.0", "1.4.0"))
    }

    @Test
    fun `version below minimum forces update`() {
        assertTrue(PluginStoreSetup.isTooOldForHost("1.3.2", "1.4.0"))
    }

    @Test
    fun `unreadable installed version forces update`() {
        // Only very old JARs lack a readable version — conservatively too old.
        assertTrue(PluginStoreSetup.isTooOldForHost(null, "1.4.0"))
    }

    @Test
    fun `pre-release of the minimum forces update`() {
        // 1.4.0-rc1 predates 1.4.0 and may lack the final contract.
        assertTrue(PluginStoreSetup.isTooOldForHost("1.4.0-rc1", "1.4.0"))
    }

    // ---- isNewerVersion: comparator edges ----

    @Test
    fun `basic semver ordering`() {
        assertTrue(PluginStoreSetup.isNewerVersion("1.4.0", "1.3.9"))
        assertFalse(PluginStoreSetup.isNewerVersion("1.3.9", "1.4.0"))
        assertFalse(PluginStoreSetup.isNewerVersion("1.4.0", "1.4.0"))
    }

    @Test
    fun `multi-digit segments compare numerically not lexically`() {
        assertTrue(PluginStoreSetup.isNewerVersion("1.4.10", "1.4.2"))
        assertFalse(PluginStoreSetup.isNewerVersion("1.4.2", "1.4.10"))
    }

    @Test
    fun `release is newer than its own pre-release`() {
        assertTrue(PluginStoreSetup.isNewerVersion("1.4.0", "1.4.0-rc1"))
        assertFalse(PluginStoreSetup.isNewerVersion("1.4.0-rc1", "1.4.0"))
    }

    @Test
    fun `suffixed segment counts as its numeric prefix`() {
        // 1.4.10-beta is numerically 1.4.10, which beats 1.4.2.
        assertTrue(PluginStoreSetup.isNewerVersion("1.4.10-beta", "1.4.2"))
    }

    // ---- pickPluginJarUrl: release-asset selection ----

    private fun releaseJson(vararg urls: String) =
        urls.joinToString(",", prefix = """{"assets":[""", postfix = "]}") {
            """{"browser_download_url": "$it"}"""
        }

    @Test
    fun `thin jar listed first is skipped in favor of the real jar`() {
        val json = releaseJson(
            "https://github.example/boss-plugin-editor-tab-1.4.3-thin.jar",
            "https://github.example/boss-plugin-editor-tab-1.4.3.jar"
        )
        assertEquals(
            "https://github.example/boss-plugin-editor-tab-1.4.3.jar",
            PluginStoreSetup.pickPluginJarUrl(json, "boss-plugin-editor-tab")
        )
    }

    @Test
    fun `release with only a thin jar yields null`() {
        val json = releaseJson("https://github.example/boss-plugin-editor-tab-1.4.3-thin.jar")
        assertNull(PluginStoreSetup.pickPluginJarUrl(json, "boss-plugin-editor-tab"))
    }

    @Test
    fun `release without matching assets yields null`() {
        val json = releaseJson("https://github.example/some-other-plugin-1.0.0.jar")
        assertNull(PluginStoreSetup.pickPluginJarUrl(json, "boss-plugin-editor-tab"))
    }

    // ---- extractVersionFromJarFileName: version source for the gate ----

    @Test
    fun `version extracted from standard jar filename`() {
        assertEquals(
            "1.4.0",
            PluginStoreSetup.extractVersionFromJarFileName(
                "boss-plugin-editor-tab-1.4.0.jar", "boss-plugin-editor-tab"
            )
        )
    }

    @Test
    fun `non-matching filename yields null`() {
        assertNull(
            PluginStoreSetup.extractVersionFromJarFileName(
                "other-plugin-1.4.0.jar", "boss-plugin-editor-tab"
            )
        )
    }
}
