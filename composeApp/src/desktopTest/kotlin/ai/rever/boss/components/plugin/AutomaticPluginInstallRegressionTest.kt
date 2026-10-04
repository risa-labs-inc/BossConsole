package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticPluginInstallRegressionTest {
    @Test
    fun `cleanup after successful activation preserves the committed artifact`() {
        val directory = Files.createTempDirectory("plugin-update-committed").toFile()
        try {
            val artifact = PluginUpdateArtifact(java.io.File(directory, "plugin-2.jar"))
            artifact.download.writeText("new bytes")
            artifact.promote().getOrThrow()
            artifact.commit()
            artifact.discardRejected().getOrThrow()
            assertTrue(artifact.target.exists())
            assertFalse(java.io.File("${artifact.target.absolutePath}.rejected-update").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `download is not a startup candidate and rejection removes promoted bytes and signature`() {
        val directory = Files.createTempDirectory("plugin-update-artifact").toFile()
        try {
            val artifact = PluginUpdateArtifact(java.io.File(directory, "plugin-2.jar"))
            artifact.download.writeText("new bytes")
            PluginSignatureSidecar.write(artifact.download.absolutePath, "signature")
            assertTrue(directory.listFiles()!!.none { it.extension == "jar" })
            artifact.promote().getOrThrow()
            assertTrue(artifact.target.exists())
            assertEquals("signature", PluginSignatureSidecar.read(artifact.target.absolutePath))
            artifact.discardRejected().getOrThrow()
            assertTrue(directory.listFiles()!!.none { it.extension == "jar" })
            assertFalse(java.io.File(PluginSignatureSidecar.pathFor(artifact.target.absolutePath)).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `promotion failure keeps the existing version untouched`() {
        val directory = Files.createTempDirectory("plugin-update-collision").toFile()
        try {
            val original = java.io.File(directory, "plugin-2.jar").apply { writeText("working bytes") }
            val artifact = PluginUpdateArtifact(original)
            assertFalse(artifact.promote().isSuccess)
            artifact.discardRejected().getOrThrow()
            assertEquals("working bytes", original.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `post download view reopen and opt out abort rather than stage`() {
        val busy = automaticActivationAfterDownload(AutomaticPluginUpdatePlan.WAIT)
        assertTrue(busy.exceptionOrNull() is PluginViewsBusyException)
        assertTrue(automaticActivationAfterDownload(AutomaticPluginUpdatePlan.RELOAD, enabled = false).isFailure)
        assertTrue(automaticActivationAfterDownload(AutomaticPluginUpdatePlan.STAGE).getOrThrow())
        assertFalse(automaticActivationAfterDownload(AutomaticPluginUpdatePlan.RELOAD).getOrThrow())
    }

    @Test
    fun `same release stops after three failures and a new release can proceed`() {
        val retry = AutomaticPluginRetryPolicy()
        assertTrue(retry.failed("plugin", "2", 0))
        assertFalse(retry.canAttempt("plugin", "2", 299_999))
        assertTrue(retry.canAttempt("plugin", "2", 300_000))
        assertTrue(retry.failed("plugin", "2", 300_000))
        assertFalse(retry.failed("plugin", "2", 600_000))
        assertFalse(retry.canAttempt("plugin", "2", Long.MAX_VALUE))
        assertTrue(retry.canAttempt("plugin", "3", 600_000))
    }
}
