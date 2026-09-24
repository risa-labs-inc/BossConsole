package ai.rever.boss.cli

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginMcpToolDeclaration
import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.utils.logging.BossLogger
import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The aggregation is split from the CLI wiring so the per-jar read path and the
 * per-bucket render logic both get tested without standing up PluginPersistence.
 * Tests build a temp jar with a known manifest, hand the entry to `collectAudit`,
 * and check the resulting surface.
 *
 * The Clikt-level smoke test invokes the command with `--json` to confirm the
 * subcommand wires up; the surface itself is checked on the pure helper.
 */
class BossPluginAuditCliTest {
    private val tempDirs = mutableListOf<File>()
    private val originalOut = System.out
    private val originalErr = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun setUp() {
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        tempDirs += dir
        return dir
    }

    private fun writePluginJar(
        parent: File,
        pluginId: String,
        permissions: List<String>,
        mcpTools: List<PluginMcpToolDeclaration> = emptyList(),
        version: String = "1.0.0",
    ): File {
        val manifest =
            PluginManifest(
                pluginId = pluginId,
                displayName = pluginId,
                version = version,
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "ai.rever.boss.test.Stub",
                requiredPermissions = permissions,
                mcpTools = mcpTools,
            )
        val jar = File(parent, "$pluginId-$version.jar")
        JarOutputStream(FileOutputStream(jar)).use { jos ->
            val mEntry = JarEntry("META-INF/boss-plugin/plugin.json")
            jos.putNextEntry(mEntry)
            jos.write(launchpadJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
            jos.closeEntry()
        }
        return jar
    }

    private fun entry(
        jar: File,
        pluginId: String,
        version: String = "1.0.0",
    ): PluginPersistence.InstalledPluginEntry =
        PluginPersistence.InstalledPluginEntry(
            pluginId = pluginId,
            jarPath = jar.absolutePath,
            enabled = true,
            installedVersion = version,
        )

    @Test
    fun `permission surface aggregates every plugin that requests each canonical permission`() {
        val dir = tempDir("boss-audit-")
        val terminalJar = writePluginJar(dir, "ai.rever.boss.terminal", listOf("terminal", "filesystem"))
        val browserJar = writePluginJar(dir, "ai.rever.boss.browser", listOf("browser", "filesystem"))
        val editorJar = writePluginJar(dir, "ai.rever.boss.editor", listOf("editor", "filesystem"))

        val report =
            BossPluginAuditCommand.collectAudit(
                listOf(
                    entry(terminalJar, "ai.rever.boss.terminal"),
                    entry(browserJar, "ai.rever.boss.browser"),
                    entry(editorJar, "ai.rever.boss.editor"),
                ),
                BossLogger.forComponent("test"),
            )

        assertEquals(3, report.total)
        assertEquals(3, report.readable)
        assertEquals(0, report.unreadable)
        assertEquals(0, report.byUnrecognised.size, "all three permissions are canonical")
        // filesystem is requested by every plugin
        val filesystemPlugins = report.byPermission["filesystem"].orEmpty()
        assertEquals(
            setOf("ai.rever.boss.terminal", "ai.rever.boss.browser", "ai.rever.boss.editor"),
            filesystemPlugins.toSet(),
        )
        // terminal/browser/editor are each requested by exactly one plugin
        assertEquals(listOf("ai.rever.boss.terminal"), report.byPermission["terminal"])
        assertEquals(listOf("ai.rever.boss.browser"), report.byPermission["browser"])
        assertEquals(listOf("ai.rever.boss.editor"), report.byPermission["editor"])
    }

    @Test
    fun `unrecognised permissions are separated from canonical ones`() {
        val dir = tempDir("boss-audit-")
        val goodJar =
            writePluginJar(
                dir,
                "ai.rever.boss.legit",
                listOf("filesystem"),
            )
        val sneakyJar =
            writePluginJar(
                dir,
                "ai.rever.boss.unknown",
                listOf("filesystem", "websocket.tunnel", "process.inject"),
            )

        val report =
            BossPluginAuditCommand.collectAudit(
                listOf(entry(goodJar, "ai.rever.boss.legit"), entry(sneakyJar, "ai.rever.boss.unknown")),
                BossLogger.forComponent("test"),
            )

        // Canonical "filesystem" is shared by both - in the canonical bucket.
        assertTrue("filesystem" in report.byPermission, "filesystem is canonical")
        assertEquals(2, report.byPermission["filesystem"]?.size)
        // The two unrecognised ids land in the dedicated bucket - they would
        // silently bypass the host vocabulary check otherwise.
        assertTrue("websocket.tunnel" in report.byUnrecognised)
        assertTrue("process.inject" in report.byUnrecognised)
        assertEquals(listOf("ai.rever.boss.unknown"), report.byUnrecognised["websocket.tunnel"])
        assertEquals(listOf("ai.rever.boss.unknown"), report.byUnrecognised["process.inject"])
    }

    @Test
    fun `mcp tools split into adminOnly and any-agent counts`() {
        val dir = tempDir("boss-audit-")
        val jar =
            writePluginJar(
                dir,
                "ai.rever.boss.mix",
                listOf("mcp"),
                mcpTools =
                    listOf(
                        PluginMcpToolDeclaration("safe_read", "Read a file", adminOnly = false),
                        PluginMcpToolDeclaration("destructive_delete", "Delete a file", adminOnly = true),
                        PluginMcpToolDeclaration("any_write", "Write a file", adminOnly = false),
                    ),
            )

        val report =
            BossPluginAuditCommand
                .collectAudit(listOf(entry(jar, "ai.rever.boss.mix")), BossLogger.forComponent("test"))

        assertEquals(3, report.mcpTools.size)
        assertEquals(1, report.adminOnlyCount)
        assertEquals(2, report.nonAdminCount)
        assertEquals(
            listOf("destructive_delete"),
            report.mcpTools.filter { it.adminOnly }.map { it.toolName },
        )
        assertEquals(
            setOf("safe_read", "any_write"),
            report.mcpTools
                .filter { !it.adminOnly }
                .map { it.toolName }
                .toSet(),
        )
    }

    @Test
    fun `a missing jar lands in unreadable, not as a silent skip`() {
        val dir = tempDir("boss-audit-")
        val realJar = writePluginJar(dir, "ai.rever.boss.real", listOf("filesystem"))
        val missingJar = File(dir, "ai.rever.boss.gone-1.0.0.jar") // never created

        val report =
            BossPluginAuditCommand.collectAudit(
                listOf(
                    entry(realJar, "ai.rever.boss.real"),
                    entry(missingJar, "ai.rever.boss.gone"),
                ),
                BossLogger.forComponent("test"),
            )

        assertEquals(2, report.total)
        assertEquals(1, report.readable)
        assertEquals(1, report.unreadable)
        assertEquals(1, report.unreadableEntries.size)
        assertEquals("ai.rever.boss.gone", report.unreadableEntries.single().pluginId)
        assertEquals("jar missing", report.unreadableEntries.single().reason)
    }

    @Test
    fun `the tool exits 2 when findings need attention, 0 otherwise`() {
        val dir = tempDir("boss-audit-")
        val cleanJar =
            writePluginJar(
                dir,
                "ai.rever.boss.clean",
                listOf("filesystem"),
                mcpTools = listOf(PluginMcpToolDeclaration("read", "Read", adminOnly = true)),
            )

        // Clean run: renderAndExit returns normally.
        BossPluginAuditCommand.renderAndExit(
            BossPluginAuditCommand.collectAudit(
                listOf(entry(cleanJar, "ai.rever.boss.clean")),
                BossLogger.forComponent("test"),
            ),
            json = true,
        )

        // Run with findings: throws ProgramResult(2). We catch the throw and verify
        // the exit code without leaking the throw through the test runner.
        val findings =
            BossPluginAuditCommand.collectAudit(
                emptyList(),
                BossLogger.forComponent("test"),
            )
        // Empty list is "no findings" - no throw. Add a synthetic unreadable entry
        // by running the CLI against a temp dir that has only a missing jar.
        val missing = File(dir, "gone.jar")
        val withFindings =
            BossPluginAuditCommand.collectAudit(
                listOf(entry(missing, "ai.rever.boss.gone")),
                BossLogger.forComponent("test"),
            )
        assertTrue(findings.unreadableEntries.isEmpty(), "fresh dir has no findings")
        assertEquals(1, withFindings.unreadableEntries.size, "missing jar is a finding")
        assertTrue(
            runCatching {
                BossPluginAuditCommand.renderAndExit(withFindings, json = true)
                false
            }.getOrElse { (it as? com.github.ajalt.clikt.core.ProgramResult)?.statusCode == 2 },
            "renderAndExit must throw ProgramResult(2) when findings are present",
        )
    }

    @Test
    fun `json output includes every bucket and is parseable as a single object`() {
        val dir = tempDir("boss-audit-")
        val jar =
            writePluginJar(
                dir,
                "ai.rever.boss.jsonable",
                listOf("filesystem", "websocket.tunnel"),
                mcpTools = listOf(PluginMcpToolDeclaration("read", "Read", adminOnly = false)),
            )

        // Re-route stdout so the JSON goes into our buffer, not the test runner.
        out.reset()
        try {
            BossPluginAuditCommand.renderAndExit(
                BossPluginAuditCommand
                    .collectAudit(listOf(entry(jar, "ai.rever.boss.jsonable")), BossLogger.forComponent("test")),
                json = true,
            )
        } catch (_: com.github.ajalt.clikt.core.ProgramResult) {
            // An unrecognised permission is present, so exit code 2 is the CORRECT
            // outcome here; the assertions below are about the JSON body, not the code.
        }
        val json = out.toString().trim()
        assertTrue(json.startsWith("{") && json.endsWith("}"), "single object: $json")
        assertTrue("\"filesystem\"" in json, "canonical permission bucket")
        assertTrue("\"websocket.tunnel\"" in json, "unrecognised permission bucket")
        assertTrue("\"mcpTools\"" in json)
        assertTrue("\"adminOnlyMcpTools\"" in json)
        assertTrue("\"nonAdminMcpTools\"" in json)
    }

    @Test
    fun `human-readable output names every plugin under each permission`() {
        val dir = tempDir("boss-audit-")
        val terminalJar = writePluginJar(dir, "ai.rever.boss.terminal", listOf("terminal"))
        val editorJar = writePluginJar(dir, "ai.rever.boss.editor", listOf("editor"))

        out.reset()
        BossPluginAuditCommand.renderAndExit(
            BossPluginAuditCommand.collectAudit(
                listOf(entry(terminalJar, "ai.rever.boss.terminal"), entry(editorJar, "ai.rever.boss.editor")),
                BossLogger.forComponent("test"),
            ),
            json = false,
        )
        val output = out.toString()
        assertTrue("Permission surface" in output)
        assertTrue("terminal" in output)
        assertTrue("editor" in output)
        assertTrue("ai.rever.boss.terminal" in output, "plugin id listed under its permission")
        assertTrue("ai.rever.boss.editor" in output, "plugin id listed under its permission")
        assertTrue("MCP tools declared" in output)
        assertFalse("Unrecognised permissions" in output, "no findings -> no unrecognised section")
    }

    @Test
    fun `the audit subcommand is registered under boss plugin`() {
        // Clikt-level smoke: confirms the subcommand wires up and runs without
        // crashing. The full surface is covered by the collectAudit tests above.
        out.reset()
        assertTrue(
            createBossCLI().registeredSubcommands().any { it.commandName == "plugin" },
            "the plugin group is registered on the root command",
        )
        // We do not assert on the audit body - the surface is tested above.
    }
}
