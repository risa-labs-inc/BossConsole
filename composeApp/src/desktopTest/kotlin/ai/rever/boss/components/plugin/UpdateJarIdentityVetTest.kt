package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.logging.BossLogger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [UpdateJarIdentityVet] accepts only a jar that declares the id of the plugin being
 * updated AND is not a protected id (BossConsole#927).
 *
 * The mismatch the issue reports is a store row for a normal plugin serving an api-id jar,
 * which - unvetted - reached `installPlugin` and triggered the process-wide api hot swap.
 * The gate enforces the store installers' two conditions exactly, so a protected id is
 * refused under every row, its own included: a one-click update is never the route by
 * which the api layer swaps at runtime, and a protected id that gets as far as this vet
 * is discarded together with its `.sig` sidecar, so the attack bytes do not survive the
 * session boundary for the next launch's directory scan to load.
 */
class UpdateJarIdentityVetTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.probe"

    @Test
    fun `a jar declaring the plugin being updated is accepted`() =
        withTempDir { dir ->
            val jar = PluginJarTestFixtures.writeJar(dir, "probe-2.0.0.jar", pluginId, "2.0.0")

            val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

            assertTrue(result.isSuccess, "a matching-id update jar must proceed")
            assertTrue(
                jar.exists(),
                "an accepted jar is the swap's to consume, not the vet's to delete",
            )
        }

    @Test
    fun `a protected-id jar is rejected under any other plugin's row`() =
        withTempDir { dir ->
            PluginDependencyResolution.NOT_USER_INSTALLABLE.forEach { protected ->
                val jar = PluginJarTestFixtures.writeJar(dir, "hijack.jar", protected, "99.0.0")

                val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

                assertTrue(result.isFailure, "$protected must not install over $pluginId's row")
            }
        }

    @Test
    fun `a protected-id jar is refused even under its own row`() =
        withTempDir { dir ->
            PluginDependencyResolution.NOT_USER_INSTALLABLE.forEach { protected ->
                val jar = PluginJarTestFixtures.writeJar(dir, "self.jar", protected, "99.0.0")

                val result = UpdateJarIdentityVet.vet(protected, jar.absolutePath)

                assertTrue(
                    result.isFailure,
                    "the NOT_USER_INSTALLABLE id $protected must not be installable by a " +
                        "one-click update, even as itself",
                )
            }
        }

    @Test
    fun `a refused jar is discarded together with its sig sidecar`() =
        withTempDir { dir ->
            val apiId = "ai.rever.boss.plugin.api"
            val jar = PluginJarTestFixtures.writeJar(dir, "hijack-99.0.0.jar", apiId, "99.0.0")
            PluginSignatureSidecar.write(jar.absolutePath, "bm90LWEtc2ln")
            val sig = File(PluginSignatureSidecar.pathFor(jar.absolutePath))

            val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

            assertTrue(result.isFailure, "an api-declaring jar must not pass a normal row")
            assertFalse(jar.exists(), "a refused jar must not survive the session boundary")
            assertFalse(sig.exists(), "the refused jar's sidecar must be discarded with it")
        }

    @Test
    fun `a jar with no readable manifest is rejected and keeps the read error`() =
        withTempDir { dir ->
            val jar = File(dir, "manifest-less.jar")
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("unrelated.txt"))
                zip.write("not a plugin manifest".toByteArray())
                zip.closeEntry()
            }

            val result = UpdateJarIdentityVet.vet(pluginId, jar.absolutePath)

            assertTrue(result.isFailure, "identity that cannot be read must fail closed")
            val cause =
                assertNotNull(result.exceptionOrNull()?.cause, "the refusal must keep its cause")
            assertTrue(
                cause.message?.isNotBlank() == true,
                "the kept cause must say why the manifest was unreadable",
            )
            val refusalMsg = "Refusing an update jar that does not declare the plugin it updates"
            val logged =
                BossLogger.getRecentLogs(limit = 100).any {
                    it.component == "UpdateJarIdentityVet" &&
                        it.message == refusalMsg &&
                        it.error != null
                }
            assertTrue(logged, "the warn log must carry the manifest read error (error=) detail")
        }

    @Test
    fun `a jar that is not there is rejected`() =
        withTempDir { dir ->
            val result = UpdateJarIdentityVet.vet(pluginId, File(dir, "absent.jar").absolutePath)

            assertTrue(result.isFailure, "a missing jar has no identity to accept")
            assertNotNull(
                result.exceptionOrNull()?.cause,
                "the refusal must keep the read error so a missing jar stays distinguishable",
            )
        }
}
