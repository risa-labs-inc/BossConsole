package ai.rever.boss.ipc

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IpcTransportTest {
    @Test
    fun `legacy malformed future and oversized runtime transport markers are refused`() {
        val directory = Files.createTempDirectory("ipc-transport-")
        try {
            val jar = directory.resolve("runtime.jar")
            for (marker in listOf(null, "", "pinned-tls-v2\n", "pinned-tls-v1\n".repeat(100_000))) {
                writeJar(jar, marker?.toByteArray())
                assertFailsWith<IllegalStateException> { IpcTransport.requireCompatibleRuntime(jar) }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `explicit runtime contract is accepted and replacing the runtime is checked afresh`() {
        val directory = Files.createTempDirectory("ipc-transport-")
        try {
            assertNull(javaClass.classLoader.getResource(IpcTransport.MARKER_PATH))
            val marker = "pinned-tls-v1;subprocess-env-v1\n".toByteArray()
            val jar = directory.resolve("runtime.jar")
            writeJar(jar, marker)
            IpcTransport.requireCompatibleRuntime(jar)
            writeJar(jar, null)
            assertFailsWith<IllegalStateException> { IpcTransport.requireCompatibleRuntime(jar) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeJar(
        path: Path,
        marker: ByteArray?,
    ) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            // Merely inheriting the IPC library's old transport marker does not declare the
            // runtime-owned subprocess behavior and must never satisfy the host's gate.
            jar.putNextEntry(JarEntry("META-INF/boss-ipc/transport"))
            jar.write("pinned-tls-v1\n".toByteArray())
            jar.closeEntry()
            if (marker != null) {
                jar.putNextEntry(JarEntry(IpcTransport.MARKER_PATH))
                jar.write(marker)
                jar.closeEntry()
            }
        }
    }
}
