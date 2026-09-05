package ai.rever.boss.cli

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossMcpCliTest {

    private val originalIn = System.`in`
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        SingleInstanceManager.statusProviderOverride = null
        SingleInstanceManager.mcpListProviderOverride = null
        SingleInstanceManager.mcpInvokeHandlerOverride = null
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        System.setErr(originalErr)
        SingleInstanceManager.release()
    }

    @Test
    fun `createBossCLI registers status, mcp, and completion subcommands`() {
        val cli = createBossCLI()
        val subcommands = cli.registeredSubcommands().map { it.commandName }.toSet()
        assertTrue(subcommands.contains("status"), "status command must be registered")
        assertTrue(subcommands.contains("mcp"), "mcp command must be registered")
        assertTrue(subcommands.contains("completion"), "completion command must be registered")
    }

    @Test
    fun `status command outputs json when requested`() {
        val fakeStatus = """{"running":true,"version":"9.5.7","os":"Windows","arch":"amd64"}"""
        SingleInstanceManager.statusProviderOverride = { fakeStatus }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("status", "--json"))
        val output = outContent.toString().trim()
        assertEquals(fakeStatus, output)
    }

    @Test
    fun `mcp list command outputs json array`() {
        val fakeTools = """[{"name":"mcp__boss__read_file","description":"Read file","pluginId":"editor-tab"}]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "list", "--json"))
        val output = outContent.toString().trim()
        assertEquals(fakeTools, output)
    }

    @Test
    fun `mcp invoke executes tool and prints output`() {
        SingleInstanceManager.mcpInvokeHandlerOverride = { tool, args ->
            McpToolResult(text = "Tool $tool received $args", isError = false)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "invoke", "mcp__boss__read_file", "-a", """{"path":"test.kt"}"""))
        val output = outContent.toString().trim()
        assertTrue(output.contains("Tool mcp__boss__read_file received {\"path\":\"test.kt\"}"))
    }

    @Test
    fun `mcp invoke with error exit code throws ProgramResult 1`() {
        SingleInstanceManager.mcpInvokeHandlerOverride = { tool, _ ->
            McpToolResult(text = "Tool $tool failed deliberately", isError = true)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke", "failing_tool"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("failing_tool failed deliberately"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean on error")
    }

    @Test
    fun `mcp invoke with raw flag emits only content`() {
        SingleInstanceManager.mcpInvokeHandlerOverride = { tool, _ ->
            McpToolResult(text = "clean raw result", isError = false)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "invoke", "mcp__boss__git_status", "-r"))
        assertEquals("clean raw result", outContent.toString().trim())
    }

    @Test
    fun `mcp list with filter filters tools`() {
        val fakeTools = """[
            {"name":"mcp__boss__read_file","description":"Read file","pluginId":"editor-tab"},
            {"name":"mcp__boss__browser_nav","description":"Navigate URL","pluginId":"fluck-browser"}
        ]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "list", "--filter", "browser"))
        val output = outContent.toString().trim()
        assertTrue(output.contains("mcp__boss__browser_nav"))
        assertFalse(output.contains("mcp__boss__read_file"))
        assertTrue(output.contains("Matching tools: 1 (of 2 total)"))
    }

    @Test
    fun `mcp list with filter in json mode returns filtered json array`() {
        val fakeTools = """[
            {"name":"mcp__boss__read_file","description":"Read file","pluginId":"editor-tab"},
            {"name":"mcp__boss__browser_nav","description":"Navigate URL","pluginId":"fluck-browser"}
        ]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "list", "-f", "browser", "--json"))
        val output = outContent.toString().trim()
        assertTrue(output.contains("mcp__boss__browser_nav"))
        assertFalse(output.contains("mcp__boss__read_file"))
    }

    @Test
    fun `mcp describe outputs tool detail`() {
        val fakeTools = """[
            {"name":"mcp__boss__browser_nav","description":"Navigate to URL","pluginId":"fluck-browser","requiresAdmin":true,"requiredPermissions":["browser.nav"]}
        ]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "describe", "mcp__boss__browser_nav"))
        val output = outContent.toString().trim()
        assertTrue(output.contains("MCP Tool: mcp__boss__browser_nav"))
        assertTrue(output.contains("Plugin:   fluck-browser"))
        assertTrue(output.contains("Access:   Requires Administrator"))
        assertTrue(output.contains("Navigate to URL"))
    }

    @Test
    fun `mcp describe in json mode outputs single tool json`() {
        val fakeTools = """[
            {"name":"mcp__boss__browser_nav","description":"Navigate to URL","pluginId":"fluck-browser"}
        ]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "describe", "mcp__boss__browser_nav", "--json"))
        val output = outContent.toString().trim()
        assertTrue(output.startsWith("{") && output.endsWith("}"))
        assertTrue(output.contains("mcp__boss__browser_nav"))
    }

    @Test
    fun `mcp describe nonexistent tool rejects with exit code 1 to stderr`() {
        val fakeTools = """[]"""
        SingleInstanceManager.mcpListProviderOverride = { fakeTools }
        assertTrue(SingleInstanceManager.acquireLock())

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "describe", "nonexistent_tool"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("nonexistent_tool' not found"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `mcp command rejects unknown action with exit code 1`() {
        val cli = createBossCLI()
        val errContent = ByteArrayOutputStream()
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "destroy"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("Unknown mcp action: 'destroy'"))
    }

    @Test
    fun `mcp invoke without tool name rejects with exit code 1`() {
        val cli = createBossCLI()
        val errContent = ByteArrayOutputStream()
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("Missing tool name"))
    }

    @Test
    fun `mcp invoke reads arguments from standard input when stdin flag is set`() {
        SingleInstanceManager.mcpInvokeHandlerOverride = { tool, args ->
            McpToolResult(text = "Tool $tool received from stdin: $args", isError = false)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val inputJson = """{"query":"select * from users"}"""
        System.setIn(java.io.ByteArrayInputStream(inputJson.toByteArray()))

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))

        cli.parse(listOf("mcp", "invoke", "mcp__boss__sql_query", "--stdin"))
        val output = outContent.toString().trim()
        assertTrue(output.contains("Tool mcp__boss__sql_query received from stdin: $inputJson"))
    }

    @Test
    fun `mcp invoke without running BOSS instance exits with code 1 and error message`() {
        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke", "any_tool"))
        }
        assertEquals(1, exit.statusCode)
        val err = errContent.toString().trim()
        assertTrue(err.startsWith("Error: BOSS is not running"))
        assertFalse(err.contains("Exception"), "Must not dump Java stack traces")
        assertFalse(err.contains("\tat "), "Must not dump stack frames")
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `offline status query prints clean error without stack traces`() {
        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("status"))
        }
        assertEquals(1, exit.statusCode)
        val err = errContent.toString().trim()
        assertTrue(err.startsWith("Error: BOSS is not running"))
        assertFalse(err.contains("Exception"), "Must not dump Java stack traces")
        assertFalse(err.contains("\tat "), "Must not dump stack frames")
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `mcp invoke with malformed json argument rejects with exit code 1 to stderr`() {
        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke", "any_tool", "-a", "{invalid json"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("Malformed JSON arguments"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `mcp invoke with non-object json argument rejects with exit code 1 to stderr`() {
        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke", "any_tool", "-a", "[\"not\",\"an\",\"object\"]"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("must be a JSON object"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `mcp invoke with oversized stdin input rejects with exit code 1 to stderr`() {
        val oversizedData = ByteArray(SingleInstanceManager.MAX_REQUEST_BYTES + 4096) { 'a'.code.toByte() }
        System.setIn(java.io.ByteArrayInputStream(oversizedData))

        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("mcp", "invoke", "any_tool", "--stdin"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("exceeded maximum size"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }

    @Test
    fun `completion command outputs completion script for bash, zsh, and fish`() {
        val cli = createBossCLI()
        for (shell in listOf("bash", "zsh", "fish")) {
            val outContent = ByteArrayOutputStream()
            System.setOut(PrintStream(outContent))
            cli.parse(listOf("completion", shell))
            val output = outContent.toString().trim()
            assertTrue(output.isNotEmpty(), "completion output for $shell must not be empty")
            assertTrue(output.contains("boss"), "completion output for $shell must reference boss command")
        }
    }

    @Test
    fun `completion command rejects unsupported shell with clean error to stderr`() {
        val cli = createBossCLI()
        val outContent = ByteArrayOutputStream()
        val errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        System.setErr(PrintStream(errContent))

        val exit = assertFailsWith<ProgramResult> {
            cli.parse(listOf("completion", "powershell"))
        }
        assertEquals(1, exit.statusCode)
        assertTrue(errContent.toString().contains("Error: Unsupported shell 'powershell'"))
        assertEquals("", outContent.toString().trim(), "stdout must remain clean")
    }
}
