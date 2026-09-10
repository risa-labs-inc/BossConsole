package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.RetiredPluginIds
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreRepairArtifactTest {
    private val plugin = SystemPluginInfo("test.plugin", "test/repo", "test-plugin", 0, minVersion = "2.0.0")

    private fun manifest(
        id: String = plugin.pluginId,
        version: String = "2.0.0",
    ) = PluginManifest(
        pluginId = id,
        displayName = "Test",
        version = version,
        apiVersion = "1.0.0",
        mainClass = "test.Main",
    )

    private fun withDownload(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("store-repair-test").toFile()
        try {
            val part = File.createTempFile("test.plugin-store-repair-", ".jar.part", dir)
            part.writeText("verified download bytes")
            PluginSignatureSidecar.persist(part.absolutePath, "test-signature")
            block(part)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `signature is available before jar becomes scannable`() =
        withDownload { part ->
            assertFalse(part.name.endsWith(".jar"))
            val target =
                StoreRepairArtifact.promote(plugin, part, manifest(), "2.0.0") { source, dest ->
                    assertFalse(dest.exists())
                    assertEquals("test-signature", PluginSignatureSidecar.read(dest.absolutePath))
                    Files.move(source.toPath(), dest.toPath())
                }
            assertEquals("verified download bytes", target.readText())
            assertEquals("test-signature", PluginSignatureSidecar.read(target.absolutePath))
            assertFalse(part.exists())
            assertFalse(File(PluginSignatureSidecar.pathFor(part.absolutePath)).exists())
        }

    @Test
    fun `move failure is reported and leaves no published artifact`() =
        withDownload { part ->
            assertFailsWith<IOException> {
                StoreRepairArtifact.promote(plugin, part, manifest(), "2.0.0") { _, _ -> throw IOException("locked") }
            }
            assertTrue(part.exists())
            assertTrue(part.parentFile.listFiles()!!.none { it.name.endsWith(".jar") || it.name.endsWith(".jar.sig") })
        }

    @Test
    fun `failure after moving cleans the unpublished destination`() =
        withDownload { part ->
            assertFailsWith<IOException> {
                StoreRepairArtifact.promote(plugin, part, manifest(), "2.0.0") { source, dest ->
                    Files.move(source.toPath(), dest.toPath())
                    throw IOException("post-move failure")
                }
            }
            assertTrue(part.parentFile.listFiles()!!.none { it.name.endsWith(".jar") || it.name.endsWith(".jar.sig") })
        }

    @Test
    fun `wrong plugin identity never gets published`() =
        withDownload { part ->
            assertFailsWith<IllegalArgumentException> {
                StoreRepairArtifact.promote(plugin, part, manifest("other"), "2.0.0")
            }
            assertTrue(part.exists())
        }

    @Test
    fun `manifest must match specifically requested store version`() =
        withDownload { part ->
            assertFailsWith<IllegalArgumentException> { StoreRepairArtifact.promote(plugin, part, manifest(), "2.1.0") }
            assertTrue(part.exists())
        }

    @Test
    fun `old prerelease and malformed versions cannot bypass host floor`() =
        withDownload { part ->
            listOf("1.9.9", "2.0.0-rc.1", "dev", "../../outside").forEach { version ->
                assertFailsWith<IllegalArgumentException> {
                    StoreRepairArtifact.promote(plugin, part, manifest(version = version), version)
                }
                assertTrue(part.exists())
            }
        }

    @Test
    fun `existing destination is never replaced`() =
        withDownload { part ->
            val existing = File(part.parentFile, part.name.removeSuffix(".part"))
            existing.writeText("existing jar")
            PluginSignatureSidecar.persist(existing.absolutePath, "existing-signature")
            assertFailsWith<IllegalStateException> { StoreRepairArtifact.promote(plugin, part, manifest(), "2.0.0") }
            assertEquals("existing jar", existing.readText())
            assertEquals("existing-signature", PluginSignatureSidecar.read(existing.absolutePath))
        }

    @Test
    fun `bootstrap artifacts are not repaired through ordinary store installation`() {
        assertFalse(StoreRepairArtifact.supports(plugin.copy(downloadOnly = true)))
        assertFalse(StoreRepairArtifact.supports(plugin.copy(pluginId = "ai.rever.boss.plugin.api")))
        assertFalse(StoreRepairArtifact.supports(plugin.copy(pluginId = "ai.rever.boss.microkernel.runtime")))
        assertTrue(StoreRepairArtifact.supports(plugin))
    }

    @Test
    fun `IPC incompatible artifact is refused before promotion`() =
        withDownload { part ->
            // Windows ARM64 intentionally omits IPC; preserve that established host policy.
            if (runCatching { Class.forName("ai.rever.boss.ipc.IpcVersion") }.isSuccess) {
                assertFailsWith<IllegalArgumentException> {
                    StoreRepairArtifact.promote(plugin, part, manifest().copy(minIpcVersion = "999.0.0"), "2.0.0")
                }
                assertTrue(part.exists())
            }
        }

    @Test
    fun `retired plugin repair stops only once its replacement is installed at the required floor`() {
        val retired = RetiredPluginIds.ALL.first()
        val candidate = plugin.copy(pluginId = retired.pluginId)
        assertFalse(StoreRepairArtifact.supports(candidate) { retired.minReplacementVersion })
        assertTrue(StoreRepairArtifact.supports(candidate) { null })
        assertTrue(StoreRepairArtifact.supports(candidate) { "0.0.0" })
    }

    @Test
    fun `unparseable operator floor remains a distinct fail closed refusal`() =
        withDownload { part ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    StoreRepairArtifact.promote(plugin.copy(minVersion = "v2.0.0"), part, manifest(), "2.0.0")
                }
            assertEquals("Invalid system plugin version floor", error.message)
            assertTrue(part.exists())
        }
}
