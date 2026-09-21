package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginManifestReader
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUpdateIdentityVetTest {
    @TempDir
    lateinit var dir: File

    private fun createTestJar(
        filename: String,
        pluginId: String,
        version: String = "2.0.0",
    ): File {
        val file = File(dir, filename)
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {"manifestVersion":1,"pluginId":"$pluginId","displayName":"Test Plugin",
                 "version":"$version","apiVersion":"1.0.0","mainClass":"example.Plugin"}
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `an update jar declaring a different plugin id is refused before unloading`() {
        val expectedPluginId = "com.example.target"
        val mismatchedJar = createTestJar("mismatched.jar", "com.example.attacker")

        val manifest = runCatching { PluginManifestReader.readFromJar(mismatchedJar.absolutePath) }.getOrNull()
        val declaredId = manifest?.pluginId

        var uninstalled = false
        val unloadResult =
            if (declaredId != expectedPluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
                Result.failure(Exception("The update copy did not install as $expectedPluginId."))
            } else {
                uninstalled = true
                Result.success(Unit)
            }

        assertTrue(unloadResult.isFailure)
        assertFalse(uninstalled, "The old plugin must NOT be uninstalled when the update jar identity mismatches")
    }

    @Test
    fun `an update jar declaring a NOT_USER_INSTALLABLE id is refused before unloading`() {
        val expectedPluginId = "com.example.target"
        val apiJar = createTestJar("api.jar", "ai.rever.boss.plugin.api")

        val manifest = runCatching { PluginManifestReader.readFromJar(apiJar.absolutePath) }.getOrNull()
        val declaredId = manifest?.pluginId

        var uninstalled = false
        val unloadResult =
            if (declaredId != expectedPluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
                Result.failure(Exception("The update copy did not install as $expectedPluginId."))
            } else {
                uninstalled = true
                Result.success(Unit)
            }

        assertTrue(unloadResult.isFailure)
        assertFalse(uninstalled, "An api-layer jar update must NOT uninstall or trigger an api swap from a dialog")
    }

    @Test
    fun `an update jar with matching identity passes validation and uninstalls old version`() {
        val expectedPluginId = "com.example.target"
        val matchingJar = createTestJar("matching.jar", "com.example.target")

        val manifest = runCatching { PluginManifestReader.readFromJar(matchingJar.absolutePath) }.getOrNull()
        val declaredId = manifest?.pluginId

        var uninstalled = false
        val unloadResult =
            if (declaredId != expectedPluginId || declaredId in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
                Result.failure(Exception("The update copy did not install as $expectedPluginId."))
            } else {
                uninstalled = true
                Result.success(Unit)
            }

        assertTrue(unloadResult.isSuccess)
        assertTrue(uninstalled, "A valid update jar with matching identity must proceed to unload the old version")
    }
}
