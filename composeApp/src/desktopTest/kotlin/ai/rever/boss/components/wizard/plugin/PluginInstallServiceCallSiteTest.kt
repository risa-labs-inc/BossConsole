package ai.rever.boss.components.wizard.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both install paths promote through [stageAndInstall], and neither falls back to an inline move.
 *
 * Every behavioural test drives the free `stageAndInstall` directly, so reverting either call site
 * in `PluginInstallService` to the old inline `finalFile.atomicMoveFrom(downloadedFile)` kept the
 * suite green - while losing everything that function guarantees: refusing an occupied destination,
 * all-or-nothing promotion, and the sidecar travelling with its jar. (#563 merge review, #1590.)
 *
 * This reads the source rather than running the paths. Running them means injecting the download as
 * well as the promotion into a class that fetches over HTTP, which is the thorough fix and is still
 * open. What this closes is the specific regression that was named: an inline `atomicMoveFrom` at a
 * call site. The repository already pins structural facts this way (`SystemPluginGithubRepoPinTest`
 * reads `PluginStoreSetup.kt`), and like that one it strips comments first, because the KDoc here
 * names `atomicMoveFrom` deliberately to explain why it is not used.
 */
class PluginInstallServiceCallSiteTest {
    private val relative =
        "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/wizard/plugin/PluginInstallService.kt"

    private val code: String by lazy {
        val source =
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .firstOrNull { it.isFile }
                ?.readText()
        checkNotNull(source) { "could not find $relative from ${File(".").absolutePath}" }
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `no install path promotes a download with an inline atomicMoveFrom`() {
        assertTrue(
            "atomicMoveFrom(" !in code,
            "an inline atomicMoveFrom replaces the destination and skips every guard in stageAndInstall",
        )
    }

    @Test
    fun `both install paths go through stageAndInstall`() {
        val calls = Regex("""(?<!fun )\bstageAndInstall\(""").findAll(code).count()
        assertEquals(
            2,
            calls,
            "the store path and the GitHub path each promote through stageAndInstall; " +
                "a new path should too, and changing this count means deciding that on purpose",
        )
    }
}
