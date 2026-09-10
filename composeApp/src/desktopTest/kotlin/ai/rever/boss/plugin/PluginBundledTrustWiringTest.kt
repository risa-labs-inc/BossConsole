package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Source checks for startup's private, persistence-dependent skip paths; byte policy is tested in plugin-loader. */
class PluginBundledTrustWiringTest {
    private fun copySource(): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
        return File(
            assertNotNull(root),
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt",
        ).readText().afterAnchor("private fun copyBundledPluginsToPluginDir(")
    }

    private fun String.afterAnchor(anchor: String): String {
        assertTrue(contains(anchor), "missing source anchor: $anchor")
        return substringAfter(anchor)
    }

    private fun String.beforeAnchor(anchor: String): String {
        assertTrue(contains(anchor), "missing source anchor: $anchor")
        return substringBefore(anchor)
    }

    @Test
    fun `directory and persisted skip paths bind existing bytes before returning`() {
        val source = copySource()
        val directorySkip =
            source
                .afterAnchor("for (existingJar in existingJarsInPluginDir) {")
                .beforeAnchor("if (shouldSkip) {")
        assertTrue(directorySkip.contains("PluginBundledTrust.bindToBundle(existingJar.absolutePath, jarFile)"))
        assertFalse(
            Regex("\\bbreak\\b").containsMatchIn(directorySkip),
            "every duplicate must be considered before reconcile",
        )
        val persistedSkip =
            source
                .afterAnchor("if (existingJarsInPluginDir.isEmpty() && existingPlugin != null) {")
                .beforeAnchor("continue")
        assertTrue(persistedSkip.contains("PluginBundledTrust.bindToBundle(existingJar.absolutePath, jarFile)"))
    }

    @Test
    fun `fresh copies bind against source after copying`() {
        val afterCopy =
            copySource()
                .afterAnchor("jarFile.copyTo(destFile, overwrite = true)")
                .beforeAnchor("PluginPersistence.addInstalledPlugin(")
        assertTrue(afterCopy.contains("PluginBundledTrust.bindToBundle(destFile.absolutePath, jarFile)"))
    }
}
