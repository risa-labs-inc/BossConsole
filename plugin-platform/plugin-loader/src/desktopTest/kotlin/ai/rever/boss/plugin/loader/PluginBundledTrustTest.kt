package ai.rever.boss.plugin.loader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginBundledTrustTest {
    private val tempDir = createTempDirectory("bundled-trust-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a freshly-marked jar is trusted`() {
        val jar = File(tempDir, "bundled.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        assertTrue(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a jar with no marker is not trusted`() {
        val jar = File(tempDir, "unmarked.jar").apply { writeText("jar-bytes") }
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `content changing after the marker was written loses trust`() {
        val jar = File(tempDir, "changed.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        jar.appendBytes("more-bytes".toByteArray())
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a marker for one jar does not trust a different jar at the same path`() {
        // Same scenario as content changing, framed the way it actually happens: a stale jar is
        // deleted and a different plugin's jar lands at the same filename.
        val path = File(tempDir, "reused.jar").absolutePath
        File(path).writeText("original-plugin")
        PluginBundledTrust.markTrusted(path, FileHashing.sha256(File(path)))
        File(path).writeText("a-completely-different-plugin")
        assertFalse(PluginBundledTrust.isTrusted(path))
    }

    @Test
    fun `delete removes the marker`() {
        val jar = File(tempDir, "toDelete.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        PluginBundledTrust.delete(jar.absolutePath)
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(jar.absolutePath)).exists())
    }

    @Test
    fun `an empty marker file is not trusted`() {
        val jar = File(tempDir, "emptyMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(jar.absolutePath)).writeText("   ")
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `an older installed copy gains trust without being overwritten`() {
        val source = File(tempDir, "bundle.jar").apply { writeText("trusted-bytes") }
        val installed = source.copyTo(File(tempDir, "old-host-copy.jar"))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
        assertTrue(PluginBundledTrust.bindToBundle(installed.absolutePath, source))
        assertTrue(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `binding never trusts different installed bytes`() {
        val source = File(tempDir, "bundle.jar").apply { writeText("trusted-bytes") }
        val installed = File(tempDir, "installed.jar").apply { writeText("changed-bytes") }
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, source))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `missing bundle and unwritable marker cannot establish trust`() {
        val installed = File(tempDir, "installed.jar").apply { writeText("trusted-bytes") }
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, File(tempDir, "missing.jar")))
        File(PluginBundledTrust.pathFor(installed.absolutePath)).mkdir()
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, installed))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `copying provenance rejects changed source or destination bytes`() {
        val source = File(tempDir, "source.jar").apply { writeText("trusted") }
        val destination = source.copyTo(File(tempDir, "snapshot.jar"))
        PluginBundledTrust.bindToBundle(source.absolutePath, source)
        assertTrue(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        destination.writeText("changed")
        assertFalse(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(destination.absolutePath)).exists())
        source.writeText("changed")
        assertFalse(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        assertFalse(PluginBundledTrust.isTrusted(destination.absolutePath))
    }
}
