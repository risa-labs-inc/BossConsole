package ai.rever.boss.components.plugin

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginUpdateLeaseTest {
    @Test
    fun `separate installers contend for the same disk lease and exceptions release it`() {
        val directory = Files.createTempDirectory("plugin-update-lease").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                PluginUpdateLease.acquire(directory, "plugin").getOrThrow().use {
                    assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
                    PluginUpdateLease.acquire(directory, "other").getOrThrow().close()
                    error("Installer failed")
                }
            }
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `same JVM rejection leaves the first lease locked against another process`() {
        val directory = Files.createTempDirectory("plugin-update-process-lease").toFile()
        try {
            val held = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            try {
                assertIs<PluginUpdateLeaseBusyException>(
                    PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
                )
                val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
                assertEquals(
                    3,
                    externalProbe(directory, path),
                    "Contender closed an FD and dropped the holder's OS lock",
                )
            } finally {
                held.close()
            }
            val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
            assertEquals(0, externalProbe(directory, path), "Completed holder did not release its OS lock")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `process ownership uses bootstrap JDK values and releases only its own token`() {
        val directory = Files.createTempDirectory("plugin-update-jdk-gate").toFile()
        try {
            val held = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            val registry = System.getProperties()["boss.plugins.updateLease.processOwners"]
            assertIs<ConcurrentHashMap<*, *>>(registry)
            val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
            val token = assertNotNull(registry[path])
            assertEquals(Any::class.java, token.javaClass, "Gate value must not pin a plugin classloader")
            assertEquals(null, token.javaClass.classLoader)
            assertEquals(null, registry.javaClass.classLoader)
            assertTrue(PluginUpdateLease.acquire(directory.canonicalFile, "plugin").isFailure)
            assertTrue(registry[path] === token, "Busy acquisition removed the current owner's token")
            held.close()
            held.close()
            assertEquals(null, registry[path])
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().use {
                val replacement = assertNotNull(registry[path])
                held.close()
                assertTrue(registry[path] === replacement, "Previous owner released the replacement's process gate")
                assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun externalProbe(
        directory: File,
        path: String,
    ): Int {
        val source = File(directory, "PluginUpdateLeaseProbe.java")
        javaClass.getResourceAsStream("/PluginUpdateLeaseProbe.java")!!.use { source.writeBytes(it.readBytes()) }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val child = ProcessBuilder(java, source.absolutePath, path).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "External lock probe timed out")
            val output = child.inputStream.bufferedReader().readText()
            assertTrue(child.exitValue() in listOf(0, 3), "External lock probe failed: $output")
            return child.exitValue()
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
