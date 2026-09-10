package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.DynamicPluginLoaderImpl
import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginClassException
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one kept file that turns "the plugin is gone" into a button.
 *
 * An update ends with `PluginJarReconciler.reconcilePluginDir`, which deletes every jar for a plugin
 * except the highest version - so without a snapshot the version that worked does not exist anywhere
 * by the time anyone discovers the new one will not load. These pin the properties that make the copy
 * usable rather than merely present.
 */
class PluginRollbackStoreTest {
    private val pluginId = "com.example.rollback"
    private val dir = createTempDirectory("rollback").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A jar that `PluginManifestReader` can read a version out of. */
    private fun writeJar(
        name: String,
        version: String,
        id: String = pluginId,
    ): File {
        val jar = File(dir, name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/boss-plugin/plugin.json"))
            zip.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$id",
                  "displayName": "Test Plugin",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Main"
                }
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `a snapshot records the version it kept`() {
        val jar = writeJar("plugin-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, jar.absolutePath)
        assertEquals("1.2.21", PluginRollbackStore.availableVersion(dir, pluginId))
    }

    @Test
    fun `the source jar survives the snapshot`() {
        // A copy, not a move. The caller may still be running from this jar, and an update that
        // removed the live plugin in order to back it up would be absurd.
        val jar = writeJar("plugin-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, jar.absolutePath)
        assertTrue(jar.isFile, "the snapshot consumed the jar it was meant to copy")
    }

    @Test
    fun `the kept copy survives a version-renaming update`() {
        // THE case the first design could not handle. The host's update path downloads to a new
        // filename and reconcile deletes the old one, so a rollback addressed by the old jar's path
        // is unreachable afterwards. Keyed by plugin id, it is still there.
        val old = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
        assertTrue(old.delete(), "could not simulate reconcile deleting the previous jar")
        writeJar("com.example.rollback-1.2.22.jar", "1.2.22")
        assertEquals("1.2.21", PluginRollbackStore.availableVersion(dir, pluginId))
    }

    @Test
    fun `a restore puts the kept version back and removes the broken one`() {
        val old = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
        old.delete()
        val broken = writeJar("com.example.rollback-1.2.22.jar", "1.2.22")

        val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, broken.absolutePath))
        assertTrue(restored.isFile)
        assertFalse(broken.isFile, "the version that would not load is still on disk")
        // Two jars for one plugin id would give the directory scan a choice it cannot make, which
        // is why the restore removes rather than overwrites.
        val jars = dir.listFiles { f: File -> f.name.endsWith(".jar") }?.map { it.name } ?: emptyList()
        assertEquals(listOf(restored.name), jars)
    }

    @Test
    fun `the kept copy is not consumed by a restore`() {
        // A restore interrupted after this point must not have destroyed the only good jar, and a
        // second attempt after a failed load has to be possible.
        val old = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
        old.delete()
        PluginRollbackStore.restore(dir, pluginId, null)
        assertEquals("1.2.21", PluginRollbackStore.availableVersion(dir, pluginId))
    }

    @Test
    fun `a restore onto the same path does not delete what it wrote`() {
        // Reachable: restoring 1.2.21 while the broken jar happens to be named for 1.2.21 too,
        // which is exactly what an in-place overwrite by the plugin-side updater leaves behind.
        val jar = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, jar.absolutePath)
        val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, jar.absolutePath))
        assertTrue(restored.isFile, "the restore deleted the file it had just written")
    }

    @Test
    fun `nothing is offered when no snapshot was taken`() {
        assertNull(PluginRollbackStore.availableVersion(dir, pluginId))
        assertNull(PluginRollbackStore.restore(dir, pluginId, null))
    }

    @Test
    fun `a missing source jar is not an error`() {
        // Reached on a first install, where there is nothing to keep. It must not throw and must
        // not leave a half-written copy.
        PluginRollbackStore.snapshot(dir, pluginId, File(dir, "absent.jar").absolutePath)
        assertNull(PluginRollbackStore.availableVersion(dir, pluginId))
    }

    @Test
    fun `an unreadable jar is not offered as a rollback`() {
        // No version means the button cannot say what it will restore. Offering it blind is worse
        // than not offering it.
        val notAJar = File(dir, "corrupt.jar")
        notAJar.writeText("this is not a zip")
        PluginRollbackStore.snapshot(dir, pluginId, notAJar.absolutePath)
        assertNull(PluginRollbackStore.availableVersion(dir, pluginId))
    }

    @Test
    fun `a failed snapshot leaves the previous one in place`() {
        // The earlier copy is correctly labelled with its OWN version, so restoring it lands on
        // something older than ideal but working. Discarding it because a later snapshot could not
        // be taken would trade a slightly-stale way back for none at all.
        val good = writeJar("com.example.rollback-1.2.20.jar", "1.2.20")
        PluginRollbackStore.snapshot(dir, pluginId, good.absolutePath)
        assertEquals("1.2.20", PluginRollbackStore.availableVersion(dir, pluginId))

        val corrupt = File(dir, "corrupt.jar")
        corrupt.writeText("not a zip")
        PluginRollbackStore.snapshot(dir, pluginId, corrupt.absolutePath)
        assertEquals(
            "1.2.20",
            PluginRollbackStore.availableVersion(dir, pluginId),
            "a usable rollback was thrown away because a later snapshot failed",
        )
    }

    @Test
    fun `the signature sidecar travels with the copy and back`() {
        // A jar whose signature file belongs to different bytes hard-fails the load, which is worse
        // than being unsigned. So the sidecar has to follow the jar in both directions.
        val old = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginSignatureSidecar.persist(old.absolutePath, "c2lnbmF0dXJlLTEuMi4yMQ==")
        PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
        old.delete()

        val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, null))
        assertEquals(
            "c2lnbmF0dXJlLTEuMi4yMQ==",
            PluginSignatureSidecar.read(restored.absolutePath),
            "the restored jar lost the signature that verifies it",
        )
    }

    @Test
    fun `an unsigned rollback does not inherit the signature it is replacing`() {
        // The sidecar on disk belongs to the version being rolled back FROM. Leaving it beside the
        // restored bytes fails the load outright, so it has to be deleted rather than kept.
        val old = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
        old.delete()
        val restoredName = "com_example_rollback-1.2.21.jar"
        PluginSignatureSidecar.persist(File(dir, restoredName).absolutePath, "d3Jvbmctc2lnbmF0dXJl")

        val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, null))
        assertNull(
            PluginSignatureSidecar.read(restored.absolutePath),
            "the restored jar kept a signature belonging to different bytes",
        )
    }

    @Test
    fun `the kept jar is not visible to a plugin directory scan`() {
        // A copy the scan can see is a second installed plugin with the same id. It lives in a
        // dot-prefixed subdirectory for exactly this reason.
        val jar = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, jar.absolutePath)
        val topLevelJars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }?.map { it.name }
        assertEquals(listOf(jar.name), topLevelJars, "the rollback copy is loadable as a plugin")
    }

    @Test
    fun `a plugin id that would escape the directory is sanitized`() {
        // The KEY is sanitized, not the manifest. `PluginManifestReader` rejects an id containing a
        // path separator outright, so such an id cannot arrive inside a jar - but snapshot takes the
        // id from its caller, which reads it off an exception or an installed.json entry rather than
        // a validated manifest. So the filename is defended here regardless.
        //
        // This runs in its OWN nested sandbox, and that is the whole point. Two earlier versions of
        // this test passed against unsanitized code:
        //
        // - asserting that `/etc` does not exist tested the machine, not this code, and on Linux
        //   the temp directory sits two levels below the root so `/etc` exists whatever happens;
        // - walking only the plugin directory missed the escape entirely, because an escape by
        //   definition writes OUTSIDE it - and `File.copyTo` calls `mkdirs()` on the destination's
        //   parent, so `..\/..\/etc/evil.jar` really does land two directories up.
        //
        // The nesting is deep enough to absorb the `../..` in the id, and the sandbox is private so
        // a diff of it is not noise from other processes the way a diff of /tmp would be.
        val sandbox = createTempDirectory("rollback-sandbox").toFile()
        try {
            val plugins = File(sandbox, "nested/plugins")
            assertTrue(plugins.mkdirs(), "could not create the nested plugin directory")
            val jar = File(plugins, "escape.jar")
            writeJar("escape.jar", "1.0.0").copyTo(jar, overwrite = true)

            val escaping = "../../etc/evil"
            val before = sandbox.walkTopDown().map { it.canonicalPath }.toSet()

            PluginRollbackStore.snapshot(plugins, escaping, jar.absolutePath)

            assertEquals("1.0.0", PluginRollbackStore.availableVersion(plugins, escaping))
            val rollbackDir = File(plugins, ".rollback").canonicalFile
            val created = sandbox.walkTopDown().map { it.canonicalPath }.toSet() - before
            assertTrue(created.isNotEmpty(), "the snapshot wrote nothing, so this proves nothing")
            created.forEach { path ->
                assertTrue(
                    path.startsWith(rollbackDir.path),
                    "a snapshot escaped the rollback directory: $path",
                )
            }
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun `discard removes everything the offer depends on`() {
        val jar = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginSignatureSidecar.persist(jar.absolutePath, "c2ln")
        PluginRollbackStore.snapshot(dir, pluginId, jar.absolutePath)
        PluginRollbackStore.discard(dir, pluginId)
        assertNull(PluginRollbackStore.availableVersion(dir, pluginId))
        assertNull(PluginRollbackStore.restore(dir, pluginId, null))
    }

    @Test
    fun `a second snapshot replaces the first`() {
        // One generation deep, on purpose: a history is a directory of multi-megabyte jars nobody
        // prunes, and recovery only needs the state immediately before the change that broke it.
        val first = writeJar("com.example.rollback-1.2.20.jar", "1.2.20")
        PluginRollbackStore.snapshot(dir, pluginId, first.absolutePath)
        val second = writeJar("com.example.rollback-1.2.21.jar", "1.2.21")
        PluginRollbackStore.snapshot(dir, pluginId, second.absolutePath)
        assertEquals("1.2.21", PluginRollbackStore.availableVersion(dir, pluginId))
        assertEquals(
            1,
            File(dir, ".rollback").listFiles { f: File -> f.name.endsWith(".jar") }?.size,
            "the rollback directory is accumulating jars",
        )
    }

    @Test
    fun `bundled rollback preserves trust through renamed snapshot and restore`() =
        runBlocking<Unit> {
            val old = writeJar("bundled-original.jar", "1.0.0")
            PluginBundledTrust.bindToBundle(old.absolutePath, old)
            PluginRollbackStore.snapshot(dir, pluginId, old.absolutePath)
            old.delete()
            PluginBundledTrust.delete(old.absolutePath)
            val broken = writeJar("broken.jar", "2.0.0")
            val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, broken.absolutePath))
            assertTrue(PluginBundledTrust.isTrusted(restored.absolutePath))

            val previousEnforcement = System.getProperty(PluginSignatureEnforcement.PROPERTY)
            val previousDev = System.getProperty("boss.dev.mode")
            try {
                System.setProperty(PluginSignatureEnforcement.PROPERTY, "true")
                System.setProperty("boss.dev.mode", "false")
                val result = DynamicPluginLoaderImpl().loadPlugin(restored.absolutePath)
                assertIs<PluginClassException>(result.exceptionOrNull())
            } finally {
                if (previousEnforcement == null) {
                    System.clearProperty(PluginSignatureEnforcement.PROPERTY)
                } else {
                    System.setProperty(PluginSignatureEnforcement.PROPERTY, previousEnforcement)
                }
                if (previousDev == null) {
                    System.clearProperty("boss.dev.mode")
                } else {
                    System.setProperty("boss.dev.mode", previousDev)
                }
            }
            PluginRollbackStore.discard(dir, pluginId)
            assertFalse(File(dir, ".rollback").listFiles().orEmpty().any { it.name.endsWith(".bundled-trust") })
        }

    @Test
    fun `an untrusted snapshot never inherits a prior snapshots marker`() {
        val trusted = writeJar("trusted.jar", "1.0.0")
        PluginBundledTrust.bindToBundle(trusted.absolutePath, trusted)
        PluginRollbackStore.snapshot(dir, pluginId, trusted.absolutePath)
        val untrusted = writeJar("unsigned.jar", "2.0.0")
        PluginRollbackStore.snapshot(dir, pluginId, untrusted.absolutePath)

        val restored = assertNotNull(PluginRollbackStore.restore(dir, pluginId, null))
        assertFalse(PluginBundledTrust.isTrusted(restored.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(restored.absolutePath)).exists())
    }
}
