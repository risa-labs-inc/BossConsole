package ai.rever.boss.components.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for the Update-button identity vet (#927): the host's own
 * update path must refuse a downloaded jar whose manifest declares a
 * different plugin id (or a NOT_USER_INSTALLABLE id like the api layer),
 * exactly like the store installers already do - nothing binds a store row to
 * the plugin id its jar declares, and installPlugin acts on the incoming
 * manifest, so an identity-mismatched jar could otherwise uninstall the real
 * plugin and hot-swap the process-wide API layer.
 */
class PluginUpdateIdentityVetTest {
    @Test
    fun `a jar declaring the expected id passes the vet`(
        @TempDir tmp: File,
    ) {
        val jar = PluginJarTestFixtures.writeJar(tmp, "my-plugin-2.0.0.jar", "com.example.my-plugin", "2.0.0")
        assertNull(PluginUpdateBridge.vetUpdateJarIdentity("com.example.my-plugin", jar.absolutePath))
    }

    @Test
    fun `a jar declaring a different plugin id is refused`(
        @TempDir tmp: File,
    ) {
        // The exact #927 shape: plugin X's row serves bytes whose manifest
        // declares some other plugin.
        val jar = PluginJarTestFixtures.writeJar(tmp, "attacker.jar", "com.evil.other-plugin", "99.0.0")
        val refusal = PluginUpdateBridge.vetUpdateJarIdentity("com.example.my-plugin", jar.absolutePath)
        assertNotNull(refusal)
        assertEquals(true, refusal.startsWith("declared id com.evil.other-plugin"))
    }

    @Test
    fun `a jar declaring the NOT_USER_INSTALLABLE api id is refused even for the matching id`(
        @TempDir tmp: File,
    ) {
        // A newer api jar reached from an Update button is the hot-swap shape
        // NOT_USER_INSTALLABLE exists to keep out of a two-button dialog.
        val apiId = PluginDependencyResolution.NOT_USER_INSTALLABLE.first()
        val jar = PluginJarTestFixtures.writeJar(tmp, "api.jar", apiId, "99.0.0")
        val refusal = PluginUpdateBridge.vetUpdateJarIdentity(apiId, jar.absolutePath)
        assertNotNull(refusal)
    }

    @Test
    fun `the vet is read-only - a refused jar stays on disk for the caller to discard`(
        @TempDir tmp: File,
    ) {
        val jar = PluginJarTestFixtures.writeJar(tmp, "evil.jar", "com.evil.other-plugin", "1.0.0")
        val refusal = PluginUpdateBridge.vetUpdateJarIdentity("com.example.my-plugin", jar.absolutePath)
        assertNotNull(refusal)
        assertEquals(true, jar.exists(), "the vet itself must not delete; activateUpdate discards")
    }

    @Test
    fun `a jar with no readable manifest is refused rather than loaded blind`(
        @TempDir tmp: File,
    ) {
        val notAJar = File(tmp, "corrupt.jar").apply { writeText("this is not a zip") }
        val refusal = PluginUpdateBridge.vetUpdateJarIdentity("com.example.my-plugin", notAJar.absolutePath)
        assertNotNull(refusal)
        assertEquals("unreadable manifest", refusal)
    }
}
