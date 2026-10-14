package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.browser.ToolkitPreloadManifest.AgentResult
import ai.rever.boss.plugin.browser.ToolkitPreloadManifest.Guard
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest is the whole contract between this launch and the next launch's native agent, so
 * its two failure directions are pinned separately: anything it cannot describe exactly must not
 * be written (the agent would load something JxBrowser does not), and the agent's report must be
 * read back faithfully (a mismatch is the one thing worth an ERROR).
 */
class ToolkitPreloadManifestTest {
    private val created = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        created.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("preload-manifest").also { created.add(it) }

    private fun guard(path: String) = Guard(path, size = 10, inode = 20, mtimeSeconds = 30, mode = 0b111_101_101)

    /** [ToolkitPreloadManifest.render] with every guard built by [guard] and one library by default. */
    private fun render(
        stamp: String = "s",
        guarded: List<String> = listOf(LIB),
        absent: List<String> = emptyList(),
        loads: List<String> = listOf(LIB),
    ) = ToolkitPreloadManifest.render(stamp, guarded.map(::guard), absent, loads)

    private fun handOff(
        stamp: String?,
        root: Path,
        manifest: File,
    ) = AgentHandOff(
        stamp = stamp,
        agentResult = AgentResult.NotInstalled,
        manifestFile = manifest,
        settingsFile = root.resolve("s.json").toFile(),
        pendingDir = root.resolve("p").toFile(),
    )

    @Test
    fun `renders header, stamp, guards, absents and loads in that order`() {
        val text =
            ToolkitPreloadManifest.render(
                stamp = "9.5.38+jxbrowser-9.5.2",
                guards = listOf(guard("/e/Libraries/libtoolkit.dylib")),
                absent = listOf("/root/boss-chromium.pending"),
                loads = listOf("/e/Libraries/libtoolkit.dylib"),
            )
        assertEquals(
            listOf(
                "boss-toolkit-preload 1",
                "stamp 9.5.38+jxbrowser-9.5.2",
                "guard 10 20 30 755 /e/Libraries/libtoolkit.dylib",
                "absent /root/boss-chromium.pending",
                "load /e/Libraries/libtoolkit.dylib",
            ),
            text?.lines()?.dropLast(1),
        )
        assertTrue(text!!.endsWith("\n"))
    }

    @Test
    fun `paths with spaces are kept whole because the path is always the last field`() {
        val path = "/Users/u/.boss/boss-chromium/BOSS.app/Contents/Frameworks/Chromium Framework.framework/libtoolkit"
        val text = ToolkitPreloadManifest.render("s", listOf(guard(path)), emptyList(), listOf(path))
        assertTrue(text!!.lines().contains("load $path"))
    }

    @Test
    fun `refuses what it cannot describe exactly`() {
        assertNotNull(render(), "the baseline renders")
        assertNull(render(loads = emptyList()), "nothing to load")
        assertNull(render(stamp = ""), "blank stamp")
        assertNull(render(stamp = "a\nb"), "stamp line break")
        assertNull(render(guarded = emptyList()), "unguarded load")
        assertNull(render(guarded = listOf("e/rel.dylib"), loads = listOf("e/rel.dylib")), "relative")
        val newline = "/e/lib\ntoolkit.dylib"
        assertNull(render(guarded = listOf(newline), loads = listOf(newline)), "line break")
        assertNull(render(absent = listOf("/x\r")), "carriage return")
    }

    @Test
    fun `reads every shape of agent report`() {
        assertEquals(AgentResult.NotInstalled, ToolkitPreloadManifest.parseAgentResult(null))
        assertEquals(AgentResult.NotInstalled, ToolkitPreloadManifest.parseAgentResult(""))
        assertEquals(
            AgentResult.Skipped("stamp differs from this build"),
            ToolkitPreloadManifest.parseAgentResult("skipped stamp differs from this build"),
        )
        val report = "loaded 2 threads=3 /a b/libtoolkit.dylib|/a b/libipc.dylib"
        val loaded = ToolkitPreloadManifest.parseAgentResult(report)
        assertEquals(AgentResult.Loaded(listOf("/a b/libtoolkit.dylib", "/a b/libipc.dylib"), 3), loaded)
    }

    @Test
    fun `a report whose count disagrees with its paths is not trusted`() {
        assertIs<AgentResult.Skipped>(ToolkitPreloadManifest.parseAgentResult("loaded 2 threads=3 /only/one.dylib"))
        assertIs<AgentResult.Skipped>(ToolkitPreloadManifest.parseAgentResult("something else"))
    }

    @Test
    fun `a count that overflows Int is an unrecognised report, not an exception`() {
        val report = ToolkitPreloadManifest.parseAgentResult("loaded 99999999999 threads=1 /x.dylib")
        assertIs<AgentResult.Skipped>(report)
    }

    @Test
    fun `the agent's work is classified against what this launch resolved`() {
        val toolkit = "/e/libtoolkit.dylib"
        val ipc = "/e/libipc.dylib"
        val expected = listOf(toolkit, ipc)

        fun verdictOf(result: AgentResult) = ToolkitPreloadManifest.verdict(result, expected)

        assertEquals(ToolkitPreloadManifest.AgentVerdict.NOTHING_LOADED, verdictOf(AgentResult.NotInstalled))
        assertEquals(ToolkitPreloadManifest.AgentVerdict.NOTHING_LOADED, verdictOf(AgentResult.Skipped("x")))
        assertEquals(ToolkitPreloadManifest.AgentVerdict.MATCHED, verdictOf(AgentResult.Loaded(expected, 3)))
        // libtoolkit loaded, libipc failed: the swap already happened, so this is not a second engine.
        assertEquals(ToolkitPreloadManifest.AgentVerdict.PARTIAL, verdictOf(AgentResult.Loaded(listOf(toolkit), 3)))
        assertEquals(
            ToolkitPreloadManifest.AgentVerdict.DIFFERENT,
            verdictOf(AgentResult.Loaded(listOf("/other/libtoolkit.dylib"), 3)),
        )
        assertEquals(ToolkitPreloadManifest.AgentVerdict.DIFFERENT, verdictOf(AgentResult.Loaded(listOf(ipc), 3)))
        assertEquals(
            ToolkitPreloadManifest.AgentVerdict.DIFFERENT,
            verdictOf(AgentResult.Loaded(expected + "/e/extra.dylib", 3)),
        )
    }

    @Test
    fun `a guard is what stat reports, and a missing file has none`() {
        if (!isMac()) return
        val file = tempDir().resolve("lib.dylib").toFile().apply { writeText("x".repeat(123)) }
        file.setExecutable(true)
        val g = assertNotNull(ToolkitPreloadManifest.guardFor(file.toPath()))
        // %z size, %i inode, %m mtime seconds, %Lp permission bits in octal: what the agent compares.
        val stat =
            ProcessBuilder("stat", "-f", "%z %i %m %Lp", file.path)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertEquals(stat, "${g.size} ${g.inode} ${g.mtimeSeconds} ${Integer.toOctalString(g.mode)}")
        assertNull(ToolkitPreloadManifest.guardFor(file.toPath().resolveSibling("missing")))
    }

    @Test
    fun `inputs guard the libraries canonically and list missing files as absent`() {
        if (!isMac()) return
        val engine = tempDir()
        val libs = engine.resolve("BOSS.app/Contents/Frameworks/Libraries").also { Files.createDirectories(it) }
        val toolkit = libs.resolve("libtoolkit.dylib").toFile().apply { writeText("t") }
        engine.resolve("version.txt").toFile().writeText("1")
        val settings = engine.resolve("settings.json").toFile() // deliberately missing
        val pending = engine.resolve("pending").toFile()
        // A path through ".." must be guarded as its canonical form, the string System.load gets.
        val indirect = File(libs.toFile(), "../Libraries/libtoolkit.dylib")
        val (guards, absent) = ToolkitPreloadManifest.inputsFor(engine, "BOSS", listOf(indirect), settings, pending)
        assertTrue(guards.any { it.path == toolkit.canonicalPath }, guards.toString())
        assertTrue(guards.any { it.path.endsWith("/version.txt") })
        assertTrue(pending.path in absent)
        assertTrue(settings.path in absent)
        assertTrue(absent.any { it.endsWith("BOSS.app/Contents/MacOS/BOSS") })
    }

    @Test
    fun `the hand-off writes once, keeps an unchanged record, and forgets on request`() {
        if (!isMac()) return
        val root = tempDir()
        val engine = root.resolve("engine")
        val lib =
            engine.resolve("libtoolkit.dylib").toFile().apply {
                parentFile.mkdirs()
                writeText("t")
            }
        val manifest = root.resolve("boss-chromium.preload").toFile()
        val handOff =
            handOff("stamp-1", root, manifest)

        handOff.remember(engine, "BOSS", listOf(lib))
        assertTrue(manifest.isFile)
        assertTrue(manifest.readText().lines().contains("load ${lib.canonicalPath}"))

        val firstWrite = Files.getLastModifiedTime(manifest.toPath())
        manifest.setLastModified(firstWrite.toMillis() - 10_000)
        val backdated = manifest.lastModified()
        handOff.remember(engine, "BOSS", listOf(lib))
        assertEquals(backdated, manifest.lastModified(), "an identical record is not rewritten")

        handOff.forget("test")
        assertFalse(manifest.exists())
    }

    @Test
    fun `without a stamp the hand-off touches nothing`() {
        val root = tempDir()
        val manifest = root.resolve("boss-chromium.preload").toFile().apply { writeText("keep") }
        val lib = root.resolve("libtoolkit.dylib").toFile().apply { writeText("t") }
        val handOff = handOff(null, root, manifest)
        handOff.remember(root, "BOSS", listOf(lib))
        handOff.forget("test")
        assertEquals("keep", manifest.readText())
    }

    private companion object {
        const val LIB = "/e/libtoolkit.dylib"
    }

    private fun isMac() =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
}
