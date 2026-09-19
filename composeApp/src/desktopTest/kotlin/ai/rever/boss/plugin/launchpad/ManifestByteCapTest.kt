package ai.rever.boss.plugin.launchpad

import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two manifest byte caps together, the same way RetiredPluginsTest
 * pins RetiredPluginIds against RetiredPlugins.ALL: DevPluginArtifacts and
 * PluginManifestReader each define a MAX_MANIFEST_BYTES for the same
 * META-INF/boss-plugin/plugin.json entry, and both KDocs say to keep the
 * caps in sync. This assertEquals makes that comment enforceable, so the
 * two constants cannot drift apart silently.
 *
 * The two readers deliberately fail differently at the cap -
 * DevPluginArtifacts.readBoundedUtf8String returns null, while
 * PluginManifestReader.readManifestContent throws PluginManifestException -
 * which is the right failure mode for each caller, not an accident.
 */
class ManifestByteCapTest {
    @Test
    fun `the app-module and loader manifest caps stay in sync`() {
        assertEquals(
            DevPluginArtifacts.MAX_MANIFEST_BYTES,
            PluginManifestReader.MAX_MANIFEST_BYTES,
            "DevPluginArtifacts.MAX_MANIFEST_BYTES and PluginManifestReader.MAX_MANIFEST_BYTES " +
                "must move together; update both or neither",
        )
    }

    @Test
    fun `host jar manifest readers use the bounded loader primitive`() {
        val readers =
            listOf(
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/wizard/plugin/PluginInstallService.kt",
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginPersistence.kt",
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt",
            )
        val unboundedJarRead = Regex("""getInputStream\([^)]*\)\.bufferedReader\(\)\.readText\(\)""")

        readers.forEach { path ->
            val source = File(repoRoot(), path).readText()
            assertTrue(
                source.contains("PluginManifestReader.readManifestContent("),
                "$path must route compressed plugin manifests through the shared byte cap",
            )
            assertFalse(
                unboundedJarRead.containsMatchIn(source),
                "$path must not inflate a JAR entry with unbounded readText()",
            )
        }
    }
}
