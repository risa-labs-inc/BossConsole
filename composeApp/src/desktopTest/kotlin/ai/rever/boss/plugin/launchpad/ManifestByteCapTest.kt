package ai.rever.boss.plugin.launchpad

import ai.rever.boss.plugin.loader.PluginManifestReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    /**
     * Pins the wiring half of the manifest-byte-cap guarantee. The cap test above
     * only proves the limit exists; this test proves the three host-side readers
     * that actually hit `META-INF/boss-plugin/plugin.json` go through it.
     *
     * The bounded reader throws PluginManifestException at MAX_MANIFEST_BYTES, so
     * a zip-bomb manifest cannot exhaust the heap through any of these call sites.
     * The previous implementation used `jar.getInputStream(entry).bufferedReader()
     * .readText()` - which reads the full entry, unbounded - so any of the three
     * sites silently regressing to that pattern reopens a heap-exhaustion vector
     * against a hostile JAR. Both halves of the assertion matter:
     *
     *  - the positive one forces the bounded reader into every site (a missing
     *    call is a hard failure);
     *  - the negative one forbids the exact unbounded shape that escaped the
     *    PR's attention (a reintroduction is a hard failure), so the two
     *    cannot drift apart by one site reading through a different bounded
     *    helper that the loader does not publish.
     *
     * Paths are relative to the composeApp working directory, the same
     * convention PanelWindowTeardownTest uses to read sources from a desktopTest.
     */
    @Test
    fun `every host-side manifest reader is wired to PluginManifestReader_dot_readManifestContent`() {
        val manifestSources =
            listOf(
                "src/desktopMain/kotlin/ai/rever/boss/components/wizard/plugin/PluginInstallService.kt",
                "src/desktopMain/kotlin/ai/rever/boss/plugin/PluginPersistence.kt",
                "src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt",
            )
        manifestSources.forEach { relativePath ->
            val source = File(relativePath).readText()
            assertContains(
                source,
                "PluginManifestReader.readManifestContent",
                message =
                    "Expected $relativePath to call " +
                        "PluginManifestReader.readManifestContent for its META-INF/boss-plugin/plugin.json read; " +
                        "an unbounded getInputStream(...).readText() reopens a heap-exhaustion vector " +
                        "against a zip-bomb plugin.json.",
            )
            // The exact unbounded shape the PR replaced - read directly off the
            // JarFile.getInputStream stream. A different shape (e.g. through a
            // helper or a new local `readBoundedManifest`) is fine; this one is
            // the regression that motivated the guard.
            assertFalse(
                Regex("""\.getInputStream\([^)]*\)\.bufferedReader\(\)\.readText\(\)""").containsMatchIn(source),
                "Expected $relativePath to NOT contain an unbounded " +
                    "getInputStream(...).bufferedReader().readText() manifest read; route it through " +
                    "PluginManifestReader.readManifestContent so the loader's MAX_MANIFEST_BYTES bound applies.",
            )
        }
    }
}
