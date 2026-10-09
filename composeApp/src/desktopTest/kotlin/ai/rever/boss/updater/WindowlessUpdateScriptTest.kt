package ai.rever.boss.updater

import ai.rever.boss.utils.SystemUtils
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowlessUpdateScriptTest {
    @Test
    fun `deferred macOS helper relaunches without windows only when requested`() {
        assumeFalse(SystemUtils.isWindows, "The helper requires a POSIX shell")
        for (requestRelaunch in listOf(false, true)) {
            val dir = Files.createTempDirectory("boss-idle-update").toFile()
            var helper: Process? = null
            var app: Process? = null
            var script: File? = null
            try {
                val volume = File(dir, "Volumes/BOSS").apply { mkdirs() }
                val sourceApp = File(volume, "BOSS.app").apply { mkdirs() }
                File(sourceApp, "version").writeText("new version")
                val targetApp = File(dir, "Installed BOSS.app").apply { mkdirs() }
                File(targetApp, "version").writeText("old version")
                val marker = File(dir, "windowless.request")
                if (requestRelaunch) marker.writeText("--no-window\n")
                val openArgs = File(dir, "open-args")
                val bin = File(dir, "bin").apply { mkdirs() }

                // Stub macOS tools and sleep. Copy/removal operate only on these
                // temporary fixture bundles; no app is installed or launched.
                command(bin, "hdiutil", "printf '/dev/disk9s1\\tApple_HFS\\t%s\\n' '${volume.absolutePath}'")
                command(bin, "xattr", "exit 0")
                command(bin, "sleep", "/bin/sleep 0.01")
                command(bin, "open", "printf '%s\\n' \"\$@\" >> '${openArgs.absolutePath}'")
                app = ProcessBuilder("/bin/sleep", "60").start()
                script =
                    UpdateScriptGenerator.generateMacOSUpdateScript(
                        dmgPath = File(dir, "update.dmg").absolutePath,
                        targetAppPath = targetApp.absolutePath,
                        appPid = app.pid(),
                        restartAutomatically = false,
                        windowlessRelaunchRequestPath = marker.absolutePath,
                    )
                val helperLog = File(dir, "helper.log")
                helper = startHelper(script, bin, helperLog)
                awaitHelperWaiting(helperLog)
                assertTrue(helper.isAlive, "The helper must wait while its owning app is alive")
                assertEquals("old version", File(targetApp, "version").readText())
                assertFalse(openArgs.exists())
                app.destroyForcibly().waitFor()
                assertTrue(helper.waitFor(10, TimeUnit.SECONDS), "Helper did not finish")
                assertEquals(0, helper.exitValue(), helperLog.readText())
                assertEquals("new version", File(targetApp, "version").readText())
                if (requestRelaunch) {
                    assertEquals(listOf("-n", targetApp.absolutePath, "--args", "--no-window"), openArgs.readLines())
                } else {
                    assertFalse(openArgs.exists(), "Manual quit must not relaunch the app")
                }
                assertFalse(marker.exists(), "The helper must clean up its relaunch request")
            } finally {
                helper?.destroyForcibly()
                app?.destroyForcibly()
                script?.delete()
                dir.deleteRecursively()
            }
        }
    }

    private fun startHelper(
        script: File,
        bin: File,
        helperLog: File,
    ): Process =
        ProcessBuilder("bash", script.absolutePath)
            .apply {
                environment()["PATH"] = "${bin.absolutePath}:/usr/bin:/bin"
                redirectErrorStream(true)
                redirectOutput(helperLog)
            }.start()

    private fun command(
        bin: File,
        name: String,
        body: String,
    ) {
        File(bin, name).apply {
            writeText("#!/bin/sh\n$body\n")
            assertTrue(setExecutable(true))
        }
    }

    private fun awaitHelperWaiting(helperLog: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!helperLog.readText().contains("Waiting for BOSS to quit") && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(helperLog.readText().contains("Waiting for BOSS to quit"), helperLog.readText())
    }
}
