package ai.rever.boss.updater

import ai.rever.boss.utils.SystemUtils
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredUpdateScriptTest {
    private fun checkUnixScript(generate: () -> File) {
        val script = generate()
        try {
            val content = script.readText()
            assertTrue(content.contains("MAX_WAIT=0"), "Must wait for a manual quit without timing out")
            assertTrue(content.contains("MAX_WAIT -gt 0"), "Zero timeout must disable the timeout check")
            assertFalse(content.contains("open '"), "Must not open the app or the installer")
            assertFalse(content.contains("nohup /"), "Must not relaunch BOSS")
            assertTrue(content.contains("Open BOSS again manually."))
            if (!SystemUtils.isWindows) {
                val syntax = ProcessBuilder("bash", "-n", script.absolutePath).redirectErrorStream(true).start()
                val output = syntax.inputStream.bufferedReader().readText()
                assertEquals(0, syntax.waitFor(), output)
            }
        } finally {
            script.delete()
        }
    }

    @Test
    fun `macOS helper waits for manual quit and never relaunches`() =
        checkUnixScript {
            UpdateScriptGenerator.generateMacOSUpdateScript("/tmp/update.dmg", "/Applications/BOSS.app", 12345, false)
        }

    @Test
    fun `linux package helpers wait for manual quit and never relaunch`() {
        checkUnixScript { UpdateScriptGenerator.generateLinuxDebUpdateScript("/tmp/update.deb", 12345, false) }
        checkUnixScript { UpdateScriptGenerator.generateLinuxRpmUpdateScript("/tmp/update.rpm", 12345, false) }
    }

    @Test
    fun `windows helper waits for manual quit and never starts the installer UI`() {
        val script =
            UpdateScriptGenerator.generateWindowsUpdateScript(
                "C:\\Temp\\update.msi",
                12345,
                "C:\\BOSS\\BOSS.exe",
                false,
            )
        try {
            val content = script.readText()
            assertTrue(content.contains(":waitloop"))
            assertTrue(content.contains("msiexec /i"))
            assertTrue(content.contains("/norestart"))
            assertFalse(content.contains("start \"\""))
        } finally {
            script.delete()
        }
    }

    @Test
    fun `jar helper leaves running app untouched and replaces it after manual exit`() {
        if (SystemUtils.isWindows) return // The JAR helper targets Unix launchers.
        val directory = Files.createTempDirectory("boss-deferred-test").toFile()
        val current = File(directory, "current.jar").apply { writeText("old version") }
        val downloaded = File(directory, "update.jar").apply { writeText("new version") }
        val runningApp = ProcessBuilder("sleep", "60").start()
        val script = generateDeferredJarUpdateScript(downloaded.absolutePath, current.absolutePath, runningApp.pid())
        val helper = ProcessBuilder("bash", script.absolutePath).redirectErrorStream(true).start()
        try {
            assertFalse(helper.waitFor(200, TimeUnit.MILLISECONDS), "Helper must wait for the current process")
            assertEquals("old version", current.readText())
            runningApp.destroy()
            assertTrue(runningApp.waitFor(5, TimeUnit.SECONDS))
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue(), helper.inputStream.bufferedReader().readText())
            assertEquals("new version", current.readText())
            assertEquals("old version", File(directory, "current.jar.backup").readText())
        } finally {
            runningApp.destroyForcibly()
            helper.destroyForcibly()
            script.delete()
            directory.deleteRecursively()
        }
    }
}
