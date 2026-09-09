package ai.rever.boss.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarBackfillPersistenceTest {
    private val directory = createTempDirectory("backfill-persistence").toFile()
    private val jar = File(directory, "plugin.jar").apply { writeText("aaaa") }
    private val manifest =
        PluginManifest(
            pluginId = "test.plugin",
            displayName = "Test",
            version = "1.0.0",
            apiVersion = "1.0.0",
            mainClass = "test.Main",
        )

    private val previousEnforcement = System.getProperty(PluginSignatureEnforcement.PROPERTY)

    @BeforeTest
    fun disableEnforcement() {
        System.setProperty(PluginSignatureEnforcement.PROPERTY, "false")
    }

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
        if (previousEnforcement == null) {
            System.clearProperty(PluginSignatureEnforcement.PROPERTY)
        } else {
            System.setProperty(PluginSignatureEnforcement.PROPERTY, previousEnforcement)
        }
    }

    @Test
    fun `settled mismatch skips the next lookup but changed bytes retry`() =
        runTest {
            var lookups = 0
            val fetch: suspend (String, String, String) -> PluginStoreSetup.StoreSignatureOutcome = { _, _, _ ->
                lookups++
                PluginStoreSetup.StoreSignatureOutcome.Mismatch
            }
            repeat(2) {
                assertNull(PluginStoreSetup.resolveSignatureToBind(jar, manifest, "old-digest", fetch))
            }
            assertEquals(1, lookups)
            assertNull(PluginStoreSetup.resolveSignatureToBind(jar, manifest, "new-digest", fetch))
            assertEquals(2, lookups)
            assertNull(PluginSignatureSidecar.read(jar.absolutePath))
        }

    @Test
    fun `unavailable answer does not prevent a later signed answer`() =
        runTest {
            assertNull(
                PluginStoreSetup.resolveSignatureToBind(jar, manifest, "digest") { _, _, _ ->
                    PluginStoreSetup.StoreSignatureOutcome.Unavailable
                },
            )
            assertFalse(File(PluginSignatureSidecar.unsignablePathFor(jar.absolutePath)).exists())
            val signature =
                PluginStoreSetup.resolveSignatureToBind(jar, manifest, "digest") { _, _, _ ->
                    PluginStoreSetup.StoreSignatureOutcome.Signed("signature")
                }
            assertEquals("signature", signature)
        }

    @Test
    fun `enforcement bypasses a cached mismatch to recover a corrected store row`() =
        runTest {
            assertNull(
                PluginStoreSetup.resolveSignatureToBind(jar, manifest, "digest") { _, _, _ ->
                    PluginStoreSetup.StoreSignatureOutcome.Mismatch
                },
            )
            System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
            val signature =
                PluginStoreSetup.resolveSignatureToBind(jar, manifest, "digest") { _, _, _ ->
                    PluginStoreSetup.StoreSignatureOutcome.Signed("corrected-signature")
                }
            assertEquals("corrected-signature", signature)
        }

    @Test
    fun `digest guard rejects changed bytes even with identical size and timestamp`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        val modifiedAt = jar.lastModified()
        assertTrue(PluginStoreSetup.stillMatchesResolvedBytes(jar, digest))
        jar.writeText("bbbb")
        assertTrue(jar.setLastModified(modifiedAt))
        assertEquals(modifiedAt, jar.lastModified())
        assertFalse(PluginStoreSetup.stillMatchesResolvedBytes(jar, digest))
        jar.delete()
        assertFalse(PluginStoreSetup.stillMatchesResolvedBytes(jar, digest))
    }
}
