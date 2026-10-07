package ai.rever.boss.components.plugin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
}
