package ai.rever.boss.cli

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.startup.CliBootstrap
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkHost
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.PROTOCOL_VERSION
import ai.rever.boss.utils.RESPONSE_ERR_NOT_FOUND
import ai.rever.boss.utils.RESPONSE_OK
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.VERB_WORKSPACE_SWITCH
import ai.rever.boss.utils.deepLinkHostOf
import ai.rever.boss.utils.formatWorkspaceSwitchRequest
import ai.rever.boss.utils.parseRequestLine
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliProtocolExpansionTest {
    private val runtimeDir = Files.createTempDirectory("boss-cli-expansion-test")
    private val originalIn = System.`in`
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = runtimeDir.toFile()
        SingleInstanceManager.workspaceValidatorOverride = null
        SingleInstanceManager.workspaceSwitchHandlerOverride = null
        SingleInstanceManager.mcpInvokeHandlerOverride = null
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        System.setErr(originalErr)
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
        runtimeDir.toFile().deleteRecursively()
    }

    // 1. Wire Protocol Whitespace Preservation Tests
    @Test
    fun `parseRequestLine preserves whitespace in workspace names`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val line = "$PROTOCOL_VERSION $token $VERB_WORKSPACE_SWITCH Data Science Studio"
        val request = parseRequestLine(line)

        assertNotNull(request)
        assertEquals(token, request.token)
        assertEquals(VERB_WORKSPACE_SWITCH, request.verb)
        assertEquals("Data Science Studio", request.workspaceName)
        assertEquals(DeepLinkOrigin.OPERATOR_CLI, request.origin)
    }

    @Test
    fun `parseRequestLine trims extra leading and trailing whitespace in workspace name`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val line = "$PROTOCOL_VERSION $token $VERB_WORKSPACE_SWITCH    Machine Learning Lab   "
        val request = parseRequestLine(line)

        assertNotNull(request)
        assertEquals("Machine Learning Lab", request.workspaceName)
    }

    @Test
    fun `parseRequestLine rejects empty workspace switch request`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val line = "$PROTOCOL_VERSION $token $VERB_WORKSPACE_SWITCH   "
        val request = parseRequestLine(line)

        assertNull(request)
    }

    @Test
    fun `formatWorkspaceSwitchRequest formats full line with name`() {
        val token = "tok123"
        val formatted = formatWorkspaceSwitchRequest(token, "Data Science Studio")
        assertEquals("$PROTOCOL_VERSION tok123 $VERB_WORKSPACE_SWITCH Data Science Studio", formatted)
    }

    // 2. Synchronous Validation Before IPC Response Tests
    @Test
    fun `switchWorkspace fails with NoSuchElementException when validator returns false`() {
        SingleInstanceManager.workspaceValidatorOverride = { false }
        assertTrue(SingleInstanceManager.acquireLock())

        val result = SingleInstanceManager.switchWorkspace("Missing Studio")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
        assertEquals("Workspace 'Missing Studio' not found.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `switchWorkspace succeeds when workspace exists`() {
        var switchedTo: String? = null
        SingleInstanceManager.workspaceValidatorOverride = { name -> name == "Data Science Studio" }
        SingleInstanceManager.workspaceSwitchHandlerOverride = { name ->
            switchedTo = name
            true
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val result = SingleInstanceManager.switchWorkspace("Data Science Studio")
        assertTrue(result.isSuccess)
        assertEquals("Switched to workspace 'Data Science Studio'.", result.getOrNull())
        assertEquals("Data Science Studio", switchedTo)
    }

    // 3. Clikt Subcommand Architecture Tests
    @Test
    fun `workspace switch command fails with exit code 1 when workspace missing`() {
        val errBuffer = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuffer))

        SingleInstanceManager.workspaceValidatorOverride = { false }
        assertTrue(SingleInstanceManager.acquireLock())

        val exit =
            assertFailsWith<ProgramResult> {
                createBossCLI().parse(listOf("workspace", "switch", "NonExistent Workspace"))
            }
        assertEquals(1, exit.statusCode)
        assertTrue(errBuffer.toString().contains("Workspace 'NonExistent Workspace' not found."))
    }

    @Test
    fun `workspace switch command succeeds with exit code 0 when workspace found`() {
        val outBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))

        SingleInstanceManager.workspaceValidatorOverride = { it == "Data Science Studio" }
        SingleInstanceManager.workspaceSwitchHandlerOverride = { true }
        assertTrue(SingleInstanceManager.acquireLock())

        createBossCLI().parse(listOf("workspace", "switch", "Data Science Studio"))
        assertTrue(outBuffer.toString().contains("Switched to workspace 'Data Science Studio'."))
    }

    @Test
    fun `mcp call command invokes tool and writes to stdout`() {
        val outBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))

        var invokedTool: String? = null
        var invokedArgs: String? = null
        SingleInstanceManager.mcpInvokeHandlerOverride = { tool, args ->
            invokedTool = tool
            invokedArgs = args
            McpToolResult(text = "Hello from $tool", isError = false)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        createBossCLI().parse(listOf("mcp", "call", "greet", """{"user":"agent"}"""))
        assertEquals("greet", invokedTool)
        assertEquals("""{"user":"agent"}""", invokedArgs)
        assertTrue(outBuffer.toString().contains("Hello from greet"))
    }

    @Test
    fun `mcp call command supports positional args or option args`() {
        val outBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))

        var invokedArgs: String? = null
        SingleInstanceManager.mcpInvokeHandlerOverride = { _, args ->
            invokedArgs = args
            McpToolResult(text = "ok", isError = false)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        createBossCLI().parse(listOf("mcp", "call", "test_tool", "--args", """{"flag":true}"""))
        assertEquals("""{"flag":true}""", invokedArgs)
    }

    @Test
    fun `mcp call command exits with code 1 on tool error`() {
        val errBuffer = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuffer))

        SingleInstanceManager.mcpInvokeHandlerOverride = { _, _ ->
            McpToolResult(text = "Tool failed execution", isError = true)
        }
        assertTrue(SingleInstanceManager.acquireLock())

        val exit =
            assertFailsWith<ProgramResult> {
                createBossCLI().parse(listOf("mcp", "call", "failing_tool"))
            }
        assertEquals(1, exit.statusCode)
        assertTrue(errBuffer.toString().contains("Tool failed execution"))
    }

    // 4. Eliminate DeepLinkHost MCP and Deep Link Security Boundaries
    @Test
    fun `DeepLinkHost does not contain MCP`() {
        val hosts = DeepLinkHost.entries.map { it.host }
        assertFalse(hosts.contains("mcp"), "DeepLinkHost must NOT contain 'mcp' to prevent RCE drive-bys")
    }

    @Test
    fun `mcp deep link uri is not recognized as a routed host`() {
        val host = deepLinkHostOf("boss://mcp?tool=terminal&cmd=rm")
        assertEquals("mcp", host)
        assertNull(
            ai.rever.boss.utils
                .routedDeepLinkHost("boss://mcp?tool=terminal"),
        )
    }

    @Test
    fun `workspace switch deep link queues SwitchWorkspace command`() {
        var queuedName: String? = null
        val handler = CLICommandHandler.getInstance()
        val originalProcessor = handler.commandProcessor

        try {
            handler.commandProcessor = { cmd ->
                if (cmd is CLICommand.SwitchWorkspace) {
                    queuedName = cmd.workspaceName
                }
            }
            DeepLinkHandler.processDeepLink(
                "boss://workspace?action=switch&name=Data%20Science%20Studio",
                DeepLinkOrigin.EXTERNAL,
            )
            assertEquals("Data Science Studio", queuedName)
        } finally {
            handler.commandProcessor = originalProcessor
        }
    }

    // 5. CliBootstrap Headless Detection Tests
    @Test
    fun `isHeadlessCli detects workspace switch and mcp call commands`() {
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("workspace", "switch", "Studio")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("mcp", "call", "echo")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("mcp", "list")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("status")))

        // Loading workspace file is GUI bootstrap, NOT headless
        assertFalse(CliBootstrap.isHeadlessCli(arrayOf("workspace", "myworkspace.json")))
    }
}
