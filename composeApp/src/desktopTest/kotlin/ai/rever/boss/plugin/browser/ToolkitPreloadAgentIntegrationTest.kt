package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.browser.ToolkitPreloadManifest.AgentResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the REAL compiled agent in a child JVM against a manifest written by the REAL Kotlin side,
 * because the contract between them is a text format parsed in C: a unit test on either half alone
 * passes against a drift between the two. The "libraries" are copies of the agent dylib itself,
 * which is a loadable image with no dependencies outside the system, so no engine is needed.
 *
 * macOS only. The agent is built by `prepareToolkitPreloadAgent`, which the test task depends on
 * there, so a missing agent on macOS is a failure rather than a skip.
 */
class ToolkitPreloadAgentIntegrationTest {
    private val created = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        created.forEach { it.toFile().deleteRecursively() }
    }

    private val isMac =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    private fun agent(): File {
        val path = System.getProperty("boss.test.toolkitAgent") ?: fail("boss.test.toolkitAgent not set by the build")
        return File(path).also { assertTrue(it.isFile, "agent not built at $it") }
    }

    /** A fake engine whose two libraries are loadable dylibs, plus a manifest written by Kotlin. */
    private inner class Fixture(
        stamp: String = STAMP,
    ) {
        val root: Path = Files.createTempDirectory("agent-it").also { created.add(it) }
        val engine: Path = root.resolve("boss-chromium")
        private val libraries =
            engine.resolve("BOSS.app/Contents/Frameworks/Chromium Framework.framework/Versions/1/Libraries")
        val toolkit: File = libraries.resolve("libtoolkit.dylib").toFile()
        val ipc: File = libraries.resolve("libipc.dylib").toFile()
        val settings: File = root.resolve("browser-engine-settings.json").toFile()
        val pending: File = root.resolve("boss-chromium.pending").toFile()
        val manifest: File = root.resolve("boss-chromium.preload").toFile()

        init {
            Files.createDirectories(libraries)
            agent().copyTo(toolkit)
            agent().copyTo(ipc)
            engine.resolve("version.txt").toFile().writeText("1\n")
            engine.resolve("executable.name").toFile().writeText("BOSS\n")
            settings.writeText("{}")
            AgentHandOff(stamp, AgentResult.NotInstalled, manifest, settings, pending)
                .remember(engine, "BOSS", listOf(toolkit, ipc))
            assertTrue(manifest.isFile, "Kotlin did not write a manifest")
        }
    }

    /** Starts a JVM with the agent and returns its exit code and the agent's report. */
    private fun launch(
        fixture: Fixture,
        stamp: String = STAMP,
        declareResult: Boolean = true,
        env: Map<String, String> = emptyMap(),
    ): Pair<Int, String?> {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val command =
            buildList {
                add(java)
                add("-Dboss.toolkit.preload.stamp=$stamp")
                if (declareResult) add("-D${ToolkitPreloadManifest.AGENT_RESULT_PROPERTY}=")
                add("-agentpath:${agent().absolutePath}=manifest=${fixture.manifest.absolutePath}")
                add("-XshowSettings:properties")
                add("-version")
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { pb -> env.forEach { (k, v) -> pb.environment()[k] = v } }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child JVM hung")
        val report =
            output
                .lines()
                .firstOrNull { it.trim().startsWith("${ToolkitPreloadManifest.AGENT_RESULT_PROPERTY} =") }
                ?.substringAfter("=")
                ?.trim()
        return process.exitValue() to report
    }

    @Test
    fun `loads what Kotlin recorded, before the JVM has started its own threads`() {
        if (!isMac) return
        val fixture = Fixture()
        val (exit, report) = launch(fixture)
        assertEquals(0, exit)
        val loaded = assertIs<AgentResult.Loaded>(ToolkitPreloadManifest.parseAgentResult(report), report)
        assertEquals(listOf(fixture.toolkit.canonicalPath, fixture.ipc.canonicalPath), loaded.paths)
        // The launcher's primordial thread, its pthread_join thread, and the agent's own: no GC,
        // no compiler threads. Anything more means the agent no longer runs ahead of them.
        val threads = loaded.threads ?: fail("no thread count in $report")
        assertTrue(threads in 1..3, "agent ran with $threads threads alive: $report")
    }

    @Test
    fun `any change to a guarded file makes it load nothing`() {
        if (!isMac) return
        val settingsChanged = Fixture().apply { settings.writeText("{\"pinnedVersion\":\"x\"}") }
        assertSkipped(settingsChanged, "guarded file changed")

        val libraryReplaced = Fixture().apply { toolkit.appendText("x") }
        assertSkipped(libraryReplaced, "guarded file changed")

        val executableBitLost =
            Fixture().apply {
                // The engine executable is guarded by mode too; create it and re-record first.
                val exe = engine.resolve("BOSS.app/Contents/MacOS/BOSS").toFile()
                exe.parentFile.mkdirs()
                exe.writeText("#!/bin/sh\n")
                exe.setExecutable(true)
                AgentHandOff(STAMP, AgentResult.NotInstalled, manifest, settings, pending)
                    .remember(engine, "BOSS", listOf(toolkit, ipc))
                exe.setExecutable(false)
            }
        assertSkipped(executableBitLost, "guarded file changed")
    }

    @Test
    fun `a staged engine install, a new build or a missing record each make it load nothing`() {
        if (!isMac) return
        assertSkipped(Fixture().apply { pending.mkdirs() }, "a path that must be absent exists")
        assertSkipped(Fixture(), "stamp differs from this build", stamp = "9.9.9+jxbrowser-0")
        assertSkipped(Fixture().apply { manifest.delete() }, "no manifest")
        assertSkipped(Fixture().apply { manifest.writeText("not a manifest\n") }, "unknown manifest version")
        assertSkipped(
            Fixture().apply {
                manifest.writeText(manifest.readText() + "load /etc/hosts.dylib\n")
            },
            "load target not guarded",
        )
    }

    @Test
    fun `the off switch is honoured before anything is read`() {
        if (!isMac) return
        assertSkipped(Fixture(), "disabled", env = mapOf("BOSS_TOOLKIT_PRELOAD" to "false"))
    }

    @Test
    fun `the JVM starts even when the launcher did not declare the result property`() {
        if (!isMac) return
        val (exit, report) = launch(Fixture(), declareResult = false)
        assertEquals(0, exit)
        assertEquals(null, report)
    }

    private fun assertSkipped(
        fixture: Fixture,
        reason: String,
        stamp: String = STAMP,
        env: Map<String, String> = emptyMap(),
    ) {
        val (exit, report) = launch(fixture, stamp = stamp, env = env)
        assertEquals(0, exit, "the JVM must start whatever the manifest says")
        assertEquals(AgentResult.Skipped(reason), ToolkitPreloadManifest.parseAgentResult(report))
    }

    private companion object {
        const val STAMP = "9.5.38+jxbrowser-9.5.2"
    }
}
