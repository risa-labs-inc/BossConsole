package ai.rever.boss.plugin.loader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PluginSignatureSidecarTest {
    private val tempDir = createTempDirectory("sidecar-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `write then read round-trips the signature`() {
        val jar = File(tempDir, "plugin.jar").apply { writeText("jar") }
        PluginSignatureSidecar.write(jar.absolutePath, "base64sig==")
        assertEquals("base64sig==", PluginSignatureSidecar.read(jar.absolutePath))
    }

    @Test
    fun `read returns null when no sidecar exists`() {
        val jar = File(tempDir, "unsigned.jar").apply { writeText("jar") }
        assertNull(PluginSignatureSidecar.read(jar.absolutePath))
    }

    @Test
    fun `read returns null for an empty sidecar`() {
        val jar = File(tempDir, "empty.jar").apply { writeText("jar") }
        File(PluginSignatureSidecar.pathFor(jar.absolutePath)).writeText("   ")
        assertNull(PluginSignatureSidecar.read(jar.absolutePath))
    }

    @Test
    fun `delete removes the sidecar`() {
        val jar = File(tempDir, "p.jar").apply { writeText("jar") }
        PluginSignatureSidecar.write(jar.absolutePath, "sig")
        PluginSignatureSidecar.delete(jar.absolutePath)
        assertFalse(File(PluginSignatureSidecar.pathFor(jar.absolutePath)).exists())
    }

    @Test
    fun `persist with a signature writes it`() {
        val jar = File(tempDir, "signed.jar").apply { writeText("jar") }
        PluginSignatureSidecar.persist(jar.absolutePath, "sig==")
        assertEquals("sig==", PluginSignatureSidecar.read(jar.absolutePath))
    }

    @Test
    fun `persist with null clears a stale sidecar`() {
        val jar = File(tempDir, "wasSigned.jar").apply { writeText("jar") }
        PluginSignatureSidecar.write(jar.absolutePath, "old-sig")
        PluginSignatureSidecar.persist(jar.absolutePath, null)
        assertFalse(File(PluginSignatureSidecar.pathFor(jar.absolutePath)).exists())
    }

    @Test
    fun `persist with blank clears rather than writing an empty sidecar`() {
        val jar = File(tempDir, "blankSig.jar").apply { writeText("jar") }
        PluginSignatureSidecar.write(jar.absolutePath, "old-sig")
        PluginSignatureSidecar.persist(jar.absolutePath, "   ")
        assertFalse(File(PluginSignatureSidecar.pathFor(jar.absolutePath)).exists())
    }
}
