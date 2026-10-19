package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the F1 pin on `PluginStoreSetup.downloadSystemPluginFromGitHub`:
 * that fallback downloads system-plugin JARs from whatever repo a
 * `system_plugins` row names, with no checksum or signature on the bytes,
 * so the ONLY thing keeping a rewritten row from making the host install
 * bytes from an arbitrary GitHub repo is
 * [SystemPluginManifestService.pinnedGithubRepoOrNull], consulted before
 * any connection is opened.
 *
 * The decision tests mirror [PluginStoreSetupIpcGateTest]; the wiring test
 * mirrors [SystemPluginManifestSyncOrderingTest] and pins that the download
 * consults the gate before its first connection (the HTTP fetch is not an
 * injectable seam - `HttpURLConnection` is opened inline - so the full
 * refuse-path cannot be driven hermetically without heavy refactoring).
 */
class SystemPluginGithubRepoPinTest {
    @Test
    fun `every shipped system plugin id has a pinned repo`() {
        val pinned = SystemPluginManifestService.pinnedSystemPluginRepos()
        val expectedIds =
            setOf(
                "ai.rever.boss.plugin.api",
                ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID,
                "ai.rever.boss.plugin.dynamic.pluginmanager",
                "ai.rever.boss.plugin.dynamic.terminaltab",
                "ai.rever.boss.plugin.dynamic.terminal",
                "ai.rever.boss.plugin.dynamic.fluckbrowser",
                "ai.rever.boss.plugin.dynamic.editortab",
            )
        // A new FALLBACK row changes the security-relevant pin set - it must
        // show up here (and in review) rather than ship silently.
        assertEquals(expectedIds, pinned.keys)
        // Bootstrap plugins must always be pinned, or a rewritten row for
        // one of them would brick startup downloads.
        assertTrue("ai.rever.boss.plugin.api" in pinned)
        assertTrue("ai.rever.boss.plugin.dynamic.pluginmanager" in pinned)
    }

    @Test
    fun `a pinned repo is trusted for its pluginId`() {
        for ((pluginId, repo) in SystemPluginManifestService.pinnedSystemPluginRepos()) {
            assertEquals(
                repo,
                SystemPluginManifestService.pinnedGithubRepoOrNull(pluginId, repo),
                "expected $pluginId at $repo to be trusted",
            )
        }
    }

    @Test
    fun `a retargeted repo for a known plugin is refused`() {
        val pinned =
            SystemPluginManifestService.pinnedGithubRepoOrNull(
                "ai.rever.boss.plugin.api",
                "evil-org/boss-plugin-api",
            )
        assertNull(pinned)
    }

    @Test
    fun `a pluginId this host does not ship is refused`() {
        // Rows appended live via table edit have no pin in this host build.
        val pinned =
            SystemPluginManifestService.pinnedGithubRepoOrNull(
                "com.attacker.plugin",
                "attacker/innocent-looking",
            )
        assertNull(pinned)
    }

    @Test
    fun `the pinned repo with different casing resolves to the build-owned spelling`() {
        assertEquals(
            "risa-labs-inc/boss-plugin-api",
            SystemPluginManifestService.pinnedGithubRepoOrNull(
                "ai.rever.boss.plugin.api",
                "RISA-LABS-INC/Boss-Plugin-Api",
            ),
        )
    }

    @Test
    fun `padding is refused rather than interpolated into a request URL`() {
        assertNull(
            SystemPluginManifestService.pinnedGithubRepoOrNull(
                "ai.rever.boss.plugin.api",
                " risa-labs-inc/boss-plugin-api ",
            ),
        )
        assertNull(
            SystemPluginManifestService.pinnedGithubRepoOrNull(
                "ai.rever.boss.plugin.api",
                "risa-labs-inc/boss-plugin-api\r\n",
            ),
        )
    }

    @Test
    fun `lookalike repos are refused`() {
        val lookalikes =
            listOf(
                "risa-labs-inc/boss-plugin-api-evil",
                "risa-labs-inc.evil.com/boss-plugin-api",
                "risa-labs-inc/boss-plugin-api/../../evil",
                "risa-labs-inc/boss-plugin-api#",
            )
        for (repo in lookalikes) {
            assertNull(
                SystemPluginManifestService.pinnedGithubRepoOrNull("ai.rever.boss.plugin.api", repo),
                "expected lookalike '$repo' to be refused",
            )
        }
    }

    @Test
    fun `the download consults the repo pin before opening any connection`() {
        val relative = "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt"
        val sourceFile =
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .first { it.isFile }
        val source = sourceFile.readText()
        val download =
            source
                .substringAfter("private suspend fun downloadSystemPluginFromGitHub")
                .substringBefore("Load persisted plugins using the provided")
        val gateIdx = download.indexOf("pinnedGithubRepoOrNull")
        assertTrue(gateIdx >= 0, "downloadSystemPluginFromGitHub must consult the repo pin")
        val firstConnectionIdx = download.indexOf("openConnection")
        assertTrue(
            gateIdx < firstConnectionIdx,
            "the repo pin must run before any connection is opened",
        )
        val lockIdx = download.indexOf("withSystemPluginDownloadLock")
        assertTrue(
            gateIdx < lockIdx,
            "an untrusted repo must be refused before the download lock is even taken",
        )
        val refusalIdx = download.indexOf("return false")
        assertTrue(
            gateIdx < refusalIdx && refusalIdx < lockIdx,
            "an untrusted repo must refuse the download instead of falling through",
        )
    }
}
