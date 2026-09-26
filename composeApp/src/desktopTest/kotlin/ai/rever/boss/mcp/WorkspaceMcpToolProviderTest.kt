package ai.rever.boss.mcp

import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalOpenEvent
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions", "LargeClass")
class WorkspaceMcpToolProviderTest {
    private val tempDirs = mutableListOf<File>()
    private val createdSplitViewStates = mutableListOf<SplitViewState>()

    /** Ids registered into the process-wide [workspaceManager] singleton, for tearDown. */
    private val registeredManagerIds = mutableListOf<String>()
    private var windowCreatorCalls = 0
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("workspace-mcp-test").toFile()
        tempDirs.add(dir)
        workspaceDir = dir
        fileManager = WorkspaceFileManager(directoryOverride = dir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        WorkspaceMcpToolProvider.windowCreator = {
            windowCreatorCalls++
            // A real window registers its SplitViewState as it composes; the tools await that.
            "test-window-window-1".also { id ->
                if (!SplitViewStateRegistry.isRegistered(id)) {
                    val state = SplitViewState(stubTabRegistry, id)
                    createdSplitViewStates.add(state)
                    SplitViewStateRegistry.register(id, state)
                }
            }
        }
        windowCreatorCalls = 0
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
        WorkspaceMcpToolProvider.coldStartWindowWaitTimeoutMs = 30_000L
        registeredManagerIds.forEach { unregisterFromManager(it) }
        registeredManagerIds.clear()
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        createdSplitViewStates.forEach { it.dispose() }
        createdSplitViewStates.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /**
     * Drop [workspaceId] from the singleton's picker list, so later test classes in this
     * JVM inherit no fixture they never registered. [WorkspaceManager] has no unregister
     * door: `deleteWorkspaceById` removes a row only when its own file manager deleted the
     * Space's FILE, and a test registers through `registerWorkspace` precisely because the
     * file lives where the singleton's file manager cannot see it. Reach the backing flow
     * directly, the same way other desktop tests reset otherwise-final private state.
     */
    private fun unregisterFromManager(workspaceId: String) {
        val listField = WorkspaceManager::class.java.getDeclaredField("_workspaces")
        listField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val workspaces = listField.get(workspaceManager) as MutableStateFlow<List<LayoutWorkspace>>
        workspaces.value = workspaces.value.filterNot { it.id == workspaceId }
    }

    private fun createTestCore(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    @Test
    fun `workspace IDs cannot select filesystem paths before any window is opened`() =
        runBlocking {
            val core = createTestCore()
            for (id in listOf("workspace-disposable-/../../important.json", "../outside.json", "C:/outside.json")) {
                for (tool in listOf("open_workspace", "close_workspace")) {
                    val result = core.invoke(tool, """{"workspaceId":"$id"}""")
                    assertTrue(result.isError)
                    assertTrue(result.text.contains("Invalid workspaceId"), result.text)
                }
            }
            assertFalse(isSafeWorkspaceId("workspace-disposable-\\..\\outside.json"))
            assertTrue(isSafeWorkspaceId("workspace-disposable-123.json"))
            assertEquals(0, windowCreatorCalls)
        }

    @Test
    fun `explicit terminal cwd must be absolute`() =
        runBlocking {
            val result = createTestCore().invoke("open_terminal", """{"workingDirectory":"."}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("absolute"), result.text)
            assertEquals(0, windowCreatorCalls)
        }

    /** A saved Space whose one terminal tab types [command] on open, saved under [id]. */
    private suspend fun savedSpaceWithCommand(
        id: String,
        command: String,
    ): LayoutWorkspace =
        LayoutWorkspace(
            id = id,
            name = "Hidden command",
            description = "test",
            layout =
                SplitConfig.SinglePanel(
                    PanelConfig(
                        "shell",
                        listOf(TabConfig(type = "terminal", title = "Shell", initialCommand = command)),
                    ),
                ),
        ).also { fileManager.saveWorkspace(it) }

    /**
     * A core whose provider-wide ALLOW would run every workspace call silently, with an operator
     * that answers every prompt with [approve]; the prompts it saw are collected in [seen].
     */
    private class PromptingCore(
        val core: McpToolRegistryCore,
        val bus: McpApprovalBus,
        val seen: MutableList<McpApprovalRequest>,
    )

    private fun CoroutineScope.promptingCore(approve: Boolean): Pair<PromptingCore, Job> {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine, approvalBus = bus)
        core.registerProvider(WorkspaceMcpToolProvider)
        val seen = mutableListOf<McpApprovalRequest>()
        val operator =
            launch {
                while (true) {
                    val req = bus.pendingList.first { it.isNotEmpty() }.first()
                    seen.add(req)
                    if (approve) bus.approve(req.id) else bus.deny(req.id, "no")
                    bus.pendingList.first { list -> list.none { it.id == req.id } }
                }
            }
        return PromptingCore(core, bus, seen) to operator
    }

    @Test
    fun `saved workspace startup commands are shown to the operator and run only when approved`() =
        runBlocking {
            val windowId = "ws-stored-commands-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)
            val workspace = savedSpaceWithCommand("hidden-command", "echo hidden")
            val file = File(workspaceDir, WorkspaceFileManagerCommon.fileNameForId(workspace.id))
            val filePath = file.absolutePath.replace('\\', '/')
            val selectors =
                listOf(
                    """{"workspaceId":"hidden-command","windowId":"$windowId"}""",
                    """{"workspacePath":"$filePath","windowId":"$windowId"}""",
                )

            // Denied: the provider-wide ALLOW did not run it, the operator was asked, and the
            // prompt carried the command the arguments do not.
            val (denying, denier) = promptingCore(approve = false)
            for (selector in selectors) {
                val result = denying.core.invoke("open_workspace", selector)
                assertTrue(result.isError, result.text)
                assertTrue(result.text.contains("rejected by operator"), result.text)
            }
            denier.cancel()
            assertEquals(2, denying.seen.size)
            denying.seen.forEach { assertEquals(listOf("echo hidden"), it.storedCommands) }
            assertEquals(null, state.currentWorkspaceId)

            // Approved: the Space opens with its terminal, command included.
            val (approving, approver) = promptingCore(approve = true)
            val result = approving.core.invoke("open_workspace", selectors.first())
            approver.cancel()
            assertFalse(result.isError, result.text)
            assertEquals("hidden-command", state.currentWorkspaceId)
            val onScreen = extractCurrentWorkspace(state, projectPath = workspaceDir.canonicalPath)
            assertEquals(listOf("echo hidden"), onScreen.layout.initialCommands())
            val record =
                approving.core.ledger.recentOperations.value
                    .single()
            assertEquals("[echo hidden]", record.sanitizedArgs["approvedStartupCommands"])
        }

    @Test
    fun `a forged approval key under a provider-wide ALLOW still prompts`() =
        runBlocking {
            savedSpaceWithCommand("forged-command", "echo forged")
            // No operator at all: under the provider-wide ALLOW a call without stored commands
            // runs silently, so if the forged key were honoured this would open the Space.
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
            val bus = McpApprovalBus(defaultTimeoutMs = 200L)
            val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine, approvalBus = bus)
            core.registerProvider(WorkspaceMcpToolProvider)
            val result =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"forged-command","approvedStartupCommands":["echo forged"]}""",
                )
            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("timed out waiting for operator approval"), result.text)
            assertEquals(0, windowCreatorCalls)
        }

    @Test
    fun `a Space edited between the prompt and the open is refused, not run with the new commands`() =
        runBlocking {
            val windowId = "ws-edited-commands-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)
            savedSpaceWithCommand("edited-command", "echo before")

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
            val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine, approvalBus = bus)
            core.registerProvider(WorkspaceMcpToolProvider)
            val pending =
                async {
                    core.invoke("open_workspace", """{"workspaceId":"edited-command","windowId":"$windowId"}""")
                }
            val req = bus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals(listOf("echo before"), req.storedCommands)
            // The file changes while the dialog is open.
            savedSpaceWithCommand("edited-command", "echo after")
            bus.approve(req.id)
            val result = pending.await()
            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("changed between approval and opening"), result.text)
            assertEquals(null, state.currentWorkspaceId)
        }

    @Test
    fun `a Space whose commands were only reordered mid-prompt is refused`() =
        runBlocking {
            val windowId = "ws-reordered-commands-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            fun twoTabs(
                first: String,
                second: String,
            ) = LayoutWorkspace(
                id = "reordered-command",
                name = "Reordered",
                description = "test",
                layout =
                    SplitConfig.SinglePanel(
                        PanelConfig(
                            "shell",
                            listOf(
                                TabConfig(type = "terminal", title = "A", initialCommand = first),
                                TabConfig(type = "terminal", title = "B", initialCommand = second),
                            ),
                        ),
                    ),
            )
            fileManager.saveWorkspace(twoTabs("echo hi > f", "cat f"))
            val policyEngine = McpPolicyEngine(policyFile = null)
            val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine, approvalBus = bus)
            core.registerProvider(WorkspaceMcpToolProvider)
            val args = """{"workspaceId":"reordered-command","windowId":"$windowId"}"""
            val pending = async { core.invoke("open_workspace", args) }
            val req = bus.pendingList.first { it.isNotEmpty() }.first()
            fileManager.saveWorkspace(twoTabs("cat f", "echo hi > f"))
            bus.approve(req.id)
            val result = pending.await()
            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("changed between approval and opening"), result.text)
        }

    @Test
    fun `a shipped template's own startup commands need no approval`() =
        runBlocking {
            val template = PredefinedWorkspaces.allWorkspaces.first { it.layout.initialCommands().isNotEmpty() }
            val commands =
                WorkspaceMcpToolProvider.storedCommandsFor(
                    "open_workspace",
                    """{"workspaceId":"${template.id}"}""".asArgs(),
                )
            assertTrue(commands.isEmpty(), "$commands")
        }

    @Test
    fun `the stored-command preview never creates a workspace`() =
        runBlocking {
            val commands =
                WorkspaceMcpToolProvider.storedCommandsFor(
                    "open_workspace",
                    """{"workspaceId":"never-made","createIfAbsent":true}""".asArgs(),
                )
            assertTrue(commands.isEmpty())
            assertNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("never-made")))
        }

    private fun String.asArgs() =
        parseMcpToolArgs(
            this,
            ai.rever.boss.utils.logging.BossLogger
                .forComponent("test"),
        )

    @Test
    fun `workspace command detection traverses both split orientations`() {
        val empty = SplitConfig.SinglePanel(PanelConfig("empty", emptyList()))
        val commands =
            SplitConfig.SinglePanel(
                PanelConfig(
                    "commands",
                    listOf(TabConfig(type = "terminal", title = "Shell", initialCommand = "echo hidden")),
                ),
            )
        assertTrue(empty.initialCommands().isEmpty())
        val nested = SplitConfig.VerticalSplit(empty, SplitConfig.HorizontalSplit(empty, commands))
        assertEquals(listOf("echo hidden"), nested.initialCommands())
    }

    @Test
    fun `tools advertises exactly one canonical name per workspace action`() {
        val tools = WorkspaceMcpToolProvider.tools().map { it.name }
        assertEquals(
            listOf(
                "list_workspaces",
                "open_workspace",
                "create_workspace",
                "open_terminal",
                "close_workspace",
            ),
            tools,
        )
        // The reversed legacy spellings stay invocable through the alias map but
        // are never advertised: two names per action doubled every list_tools.
        for ((alias, canonical) in WorkspaceMcpToolProvider.toolAliases) {
            assertFalse(tools.contains(alias), "$alias must not be advertised")
            assertTrue(tools.contains(canonical), "$alias must resolve to advertised $canonical")
        }
    }

    @Test
    fun `list_tools exposes five workspace tools while the alias still resolves on invoke`() =
        runBlocking {
            val core = createTestCore()
            assertEquals(5, core.allTools.value.count { it.providerId == "boss-workspace" })
            assertEquals(5, core.tools.value.count { it.providerId == "boss-workspace" })

            val result = core.invoke("workspace_list", "{}")
            assertFalse(result.isError, "Alias workspace_list must resolve: ${result.text}")
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
        }

    @Test
    fun `registered in McpToolRegistryImpl by default`() {
        val registeredNames =
            McpToolRegistryImpl.allTools.value
                .map { it.definition.name }
                .toSet()
        assertTrue(registeredNames.contains("open_workspace"))
        assertTrue(registeredNames.contains("open_terminal"))
        assertTrue(registeredNames.contains("list_workspaces"))
        assertTrue(registeredNames.contains("create_workspace"))
    }

    @Test
    fun `mutating tool catalog registers all workspace and terminal mutating operations and aliases`() {
        val mutatingTools =
            listOf(
                "open_workspace",
                "workspace_open",
                "create_workspace",
                "workspace_create",
                "open_terminal",
                "terminal_open",
                "close_workspace",
                "workspace_close",
            )
        for (tool in mutatingTools) {
            assertTrue(
                McpMutatingToolCatalog.isMutating(tool),
                "Expected $tool to be registered as mutating",
            )
        }
        assertFalse(
            McpMutatingToolCatalog.isMutating("list_workspaces"),
            "list_workspaces should not be mutating",
        )
        assertFalse(
            McpMutatingToolCatalog.isMutating("workspace_list"),
            "workspace_list should not be mutating",
        )
    }

    @Test
    fun `list_workspaces is a pure read even when no window is open`() =
        runBlocking {
            val core = createTestCore()

            val result = core.invoke("list_workspaces", "{}")
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            // A read-only tool must not create a window just to answer a listing.
            assertEquals(null, json["activeWindowId"])
            assertEquals(0, windowCreatorCalls, "list_workspaces must not create a window")

            val workspaces = json["workspaces"]?.jsonArray
            assertNotNull(workspaces)
            assertTrue(workspaces.isNotEmpty(), "Predefined workspaces must be listed")

            val templateIds = workspaces.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(templateIds.contains(PredefinedWorkspaces.DUAL_TERMINAL_ID))
            assertTrue(templateIds.contains(PredefinedWorkspaces.BROWSER_ONLY_ID))
        }

    @Test
    fun `open_workspace opens predefined workspace from cold start`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}"""
            val result = core.invoke("open_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals(PredefinedWorkspaces.DUAL_TERMINAL_ID, json["workspaceId"]?.jsonPrimitive?.content)
            assertEquals("Dual Terminal", json["workspaceName"]?.jsonPrimitive?.content)
            assertEquals("test-window-window-1", json["windowId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `open_workspace fails with clear error when workspace is not found`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"nonexistent-workspace-id"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("not found"))
        }

    @Test
    fun `open_workspace with createIfAbsent creates new workspace when missing`() =
        runBlocking {
            val core = createTestCore()

            val args = """{"workspaceId":"custom-auto-ws","name":"Auto Created","createIfAbsent":true}"""
            val result = core.invoke("open_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("custom-auto-ws", json["workspaceId"]?.jsonPrimitive?.content)
            assertEquals("Auto Created", json["workspaceName"]?.jsonPrimitive?.content)

            // Saved to disk
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("custom-auto-ws"))
            assertNotNull(loaded)
            assertEquals("Auto Created", loaded.name)
        }

    @Test
    fun `open_workspace is an error, not a silent success, when the window never registers its UI state`() =
        runBlocking {
            // A window creator that returns an id nothing ever registers: the cold-start shape
            // where the window failed to come up within the wait.
            WorkspaceMcpToolProvider.windowCreator = { "never-registers" }
            val core = createTestCore()

            val result = core.invoke("open_workspace", """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}""")
            assertTrue(result.isError, "nothing was applied, so the call must not report success: ${result.text}")
            assertTrue(result.text.contains("did not register its UI state"), result.text)
            assertTrue(result.text.contains(PredefinedWorkspaces.DUAL_TERMINAL_ID), result.text)
            assertFalse(result.text.contains("file was saved"), "nothing was created for a shipped layout")
        }

    @Test
    fun `open_workspace with createIfAbsent says the file was saved when the window never registers`(): Unit =
        runBlocking {
            WorkspaceMcpToolProvider.windowCreator = { "never-registers" }
            val core = createTestCore()

            val result = core.invoke("open_workspace", """{"workspaceId":"saved-not-opened","createIfAbsent":true}""")
            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("The new workspace file was saved"), result.text)
            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("saved-not-opened")))
        }

    @Test
    fun `open_workspace with missing file returns clear error`() =
        runBlocking {
            val core = createTestCore()

            val badPath = File(workspaceDir, "does-not-exist.json").absolutePath
            val args = """{"workspacePath":"${badPath.replace('\\', '/')}"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Workspace file not found"))
        }

    @Test
    fun `open_workspace refuses a workspacePath outside the workspaces directory`() =
        runBlocking {
            val core = createTestCore()
            // A well-formed, readable Space file OUTSIDE the workspace store: had the refusal
            // come after the read, this file would parse cleanly and be applied. Every payload
            // below resolves outside the store and must be refused with the containment
            // message, before the file is probed, read, or parsed.
            val outsideDir = Files.createTempDirectory("ws-path-outside").toFile()
            tempDirs.add(outsideDir)
            val outsideSpaceFile = File(outsideDir, "outside-space.json")
            outsideSpaceFile.writeText(
                WorkspaceSerializer.serialize(savedSpaceFixture("outside-space", "/work/outside")),
            )
            val escapeLink = File(workspaceDir, "escape-link.json")
            Files.createSymbolicLink(escapeLink.toPath(), outsideSpaceFile.toPath())

            val payloads =
                listOf(
                    // Any readable file on disk, by absolute path.
                    "/etc/passwd",
                    // A valid Space file that lives elsewhere.
                    outsideSpaceFile.absolutePath.replace('\\', '/'),
                    // `..` escaping the store from inside it.
                    File(workspaceDir, "../../etc/passwd").absolutePath.replace('\\', '/'),
                    // A symlink inside the store pointing out.
                    escapeLink.absolutePath.replace('\\', '/'),
                    // A missing file outside the store: refused before the existence probe
                    // ("Workspace file not found"), because containment runs first.
                    "/no/such/store/missing-space.json",
                    // The store directory itself is not a Space file inside the store.
                    workspaceDir.absolutePath.replace('\\', '/'),
                )
            for (payload in payloads) {
                val result = core.invoke("open_workspace", """{"workspacePath":"$payload"}""")
                assertTrue(result.isError, "expected a refusal for $payload: ${result.text}")
                assertTrue(result.text.contains("workspaces directory"), result.text)
                // The containment refusal is its own message, not the startup-command
                // refusal (the file was never parsed) and not the not-found probe.
                assertFalse(result.text.contains("startup commands"), result.text)
                assertFalse(result.text.contains("not found"), result.text)
            }
            // Refused before any window was resolved or created.
            assertEquals(0, windowCreatorCalls)
        }

    @Test
    fun `open_workspace applies a Space file inside the workspaces directory`() =
        runBlocking {
            val windowId = "ws-path-in-store-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val project = Files.createTempDirectory("ws-path-in-store-project").toFile()
            tempDirs.add(project)
            val space = savedSpaceFixture("in-store-space", project.canonicalPath)
            fileManager.saveWorkspace(space)
            val onDisk = File(workspaceDir, WorkspaceFileManagerCommon.fileNameForId(space.id))
            assertTrue(onDisk.exists())

            // `..` stays legal while it resolves inside the store; the canonicalised file
            // the gate returned is the file that loads.
            val viaDotDot = File(File(workspaceDir, "nested"), "../${onDisk.name}")
            val pathArg = viaDotDot.absolutePath.replace('\\', '/')
            val args = """{"workspacePath":"$pathArg","windowId":"$windowId"}"""
            val result = createTestCore().invoke("open_workspace", args)
            assertFalse(result.isError, result.text)

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("in-store-space", json["workspaceId"]?.jsonPrimitive?.content)
            assertEquals(project.canonicalPath, json["projectPath"]?.jsonPrimitive?.content)

            // Not merely parsed - applied as the window's live Space.
            val onScreen = extractCurrentWorkspace(state, projectPath = project.canonicalPath)
            assertEquals(1, (onScreen.layout as SplitConfig.SinglePanel).panel.tabs.size)
        }

    @Test
    fun `workspacePath containment decides by canonical location`() =
        runBlocking {
            val store = workspaceDir.absolutePath
            val inStore = File(workspaceDir, "a-space.json")
            val contained = checkWorkspacePathContainment(inStore.absolutePath, store)
            assertNull(contained.error)
            assertEquals(inStore.canonicalPath, contained.canonicalPath)

            // `..` that stays inside the store is contained; `..` that escapes is not.
            val dotted = File(File(workspaceDir, "sub"), "../a-space.json")
            assertEquals(
                inStore.canonicalPath,
                checkWorkspacePathContainment(dotted.absolutePath, store).canonicalPath,
            )
            val escaped =
                checkWorkspacePathContainment(
                    File(workspaceDir, "../../etc/passwd").absolutePath,
                    store,
                )
            assertNull(escaped.canonicalPath)
            assertTrue(escaped.error!!.contains("workspaces directory"))

            // The store directory itself, a sibling named to share its prefix, and a
            // symlink out of the store are all refused: containment is component-wise.
            assertNull(checkWorkspacePathContainment(store, store).canonicalPath)
            val sibling = File(workspaceDir.parentFile, workspaceDir.name + "-evil/space.json")
            assertNull(checkWorkspacePathContainment(sibling.absolutePath, store).canonicalPath)
            val outside = Files.createTempDirectory("ws-containment-outside").toFile()
            tempDirs.add(outside)
            val link = File(workspaceDir, "link.json")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(checkWorkspacePathContainment(link.absolutePath, store).canonicalPath)
        }

    @Test
    fun `create_workspace creates disposable workspace with unique ID`(): Unit =
        runBlocking {
            val core = createTestCore()

            val args = """{"isDisposable":true,"projectPath":"${workspaceDir.absolutePath.replace('\\', '/')}"}"""
            val result = core.invoke("create_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertTrue(json["isDisposable"]?.jsonPrimitive?.booleanOrNull == true)

            val wsId = json["workspaceId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(wsId.startsWith("workspace-disposable-"), "ID must have disposable prefix: $wsId")

            // And file is created
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertNotNull(loaded)
        }

    @Test
    fun `create_workspace persists workspace layout without activating window or applying layout`() =
        runBlocking {
            val core = createTestCore()

            var windowActivated = false
            WorkspaceMcpToolProvider.splitViewStateResolver = {
                windowActivated = true
                null
            }

            val validPath = workspaceDir.absolutePath.replace('\\', '/')
            val args = """{"name":"Decoupled Workspace","projectPath":"$validPath"}"""
            val result = core.invoke("create_workspace", args)
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("Decoupled Workspace", json["workspaceName"]?.jsonPrimitive?.content)
            assertNotNull(json["filePath"]?.jsonPrimitive?.content)

            val wsId = json["workspaceId"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(wsId.isNotBlank())

            // Persisted on disk
            val loaded = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertNotNull(loaded)
            assertEquals("Decoupled Workspace", loaded.name)

            // SplitViewStateResolver was not invoked (no activation)
            assertFalse(windowActivated, "create_workspace must not activate window")
        }

    @Test
    fun `open_terminal rejects invalid working directory`() =
        runBlocking {
            val core = createTestCore()

            val badDir = File(workspaceDir, "non_existent_folder_abc").absolutePath
            val args = """{"workingDirectory":"${badDir.replace('\\', '/')}"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("Path is not an existing directory"))
        }

    @Test
    fun `open_terminal returns the authoritative tab id without re-opening via the event bus`() =
        runBlocking {
            val core = createTestCore()

            var openedCmd: String? = null
            var openedCwd: String? = null
            val expectedTab =
                TerminalTabInfo(
                    id = "terminal-authoritative-999",
                    title = "Terminal",
                    workingDirectory = workspaceDir.absolutePath.replace('\\', '/'),
                    initialCommand = "echo hello",
                )
            WorkspaceMcpToolProvider.terminalTabOpener = { _, cmd, cwd ->
                openedCmd = cmd
                openedCwd = cwd
                expectedTab
            }

            // The provider must not open terminals through the bus door: every window runs a
            // collector on that bus that opens a terminal for each event aimed at it, so an
            // emission here would open a second tab and run the command twice.
            val busEvents = mutableListOf<String?>()
            val collection =
                launch {
                    TerminalEventBus.terminalOpenEvents.collect { busEvents.add(it.sourceWindowId) }
                }

            val validDir = workspaceDir.absolutePath.replace('\\', '/')
            val args = """{"workingDirectory":"$validDir","command":"echo hello"}"""
            val result = core.invoke("open_terminal", args)
            delay(50L)
            collection.cancel()
            assertFalse(result.isError, "Expected success: ${result.text}")

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("test-window-window-1", json["windowId"]?.jsonPrimitive?.content)
            assertEquals("echo hello", json["command"]?.jsonPrimitive?.content)

            val tabId = json["tabId"]?.jsonPrimitive?.content.orEmpty()
            val terminalId = json["terminalId"]?.jsonPrimitive?.content.orEmpty()
            assertEquals("terminal-authoritative-999", tabId)
            assertEquals("authoritative-999", terminalId)

            assertEquals("echo hello", openedCmd)
            assertEquals(workspaceDir.canonicalPath, openedCwd)
            assertTrue(busEvents.isEmpty(), "open_terminal must not emit TerminalOpenEvents: $busEvents")
        }

    @Test
    fun `resolveTargetWindow refuses targeting when multiple windows are open and windowId omitted`() =
        runBlocking {
            // Both windows share the stub registry: what is being measured is which window the
            // workspace lands in, and Dual Terminal only builds at all when "terminal" has a
            // factory - an empty registry now gets the apply refused rather than applied empty.
            val state1 = SplitViewState(stubTabRegistry, "window-multi-1")
            val state2 = SplitViewState(stubTabRegistry, "window-multi-2")
            createdSplitViewStates.add(state1)
            createdSplitViewStates.add(state2)

            SplitViewStateRegistry.register("window-multi-1", state1)
            SplitViewStateRegistry.register("window-multi-2", state2)

            val core = createTestCore()

            // When windowId is omitted with multiple windows open, open_workspace should fail
            val args = """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}"""
            val result = core.invoke("open_workspace", args)
            assertTrue(result.isError, "Should fail when windowId is omitted with multiple windows open")
            assertTrue(
                result.text.contains("Multiple windows are open"),
                "Expected error message regarding multiple active windows: ${result.text}",
            )

            // With explicit valid windowId, it succeeds
            val explicitArgs =
                """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}","windowId":"window-multi-1"}"""
            val explicitResult = core.invoke("open_workspace", explicitArgs)
            assertFalse(explicitResult.isError, "Expected success with explicit windowId: ${explicitResult.text}")
        }

    @Test
    fun `awaitSplitViewState waits for window registration on cold start`(): Unit =
        runBlocking {
            val tabReg = TabRegistry()
            val state = SplitViewState(tabReg, "window-async-ready")
            createdSplitViewStates.add(state)

            // Register asynchronously after 20ms
            launch {
                delay(20L)
                SplitViewStateRegistry.register("window-async-ready", state)
            }

            val resolved = WorkspaceMcpToolProvider.awaitSplitViewState("window-async-ready", timeoutMillis = 500L)
            assertNotNull(resolved, "Should successfully resolve window state once registered")
        }

    @Test
    fun `awaitSplitViewState returns null when registration times out`() =
        runBlocking {
            val resolved = WorkspaceMcpToolProvider.awaitSplitViewState("window-nonexistent", timeoutMillis = 50L)
            assertTrue(resolved == null, "Should return null if window state never registers within timeout")
        }

    @Test
    fun `open_terminal opens in a cold-start window that registers past the short bound`() =
        runBlocking {
            // A window this call mints still has to compose and register. Model a slow
            // register landing well past splitViewWaitTimeoutMs (50ms in setUp) but inside
            // the cold-start bound - the fixed wait used to give up first and report a
            // generic failure while the window was moments from ready.
            val windowId = "mcp-cold-start-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            WorkspaceMcpToolProvider.windowCreator = { windowId }
            WorkspaceMcpToolProvider.coldStartWindowWaitTimeoutMs = 2_000L
            launch {
                delay(300)
                SplitViewStateRegistry.register(windowId, state)
            }

            val result = createTestCore().invoke("open_terminal", "{}")

            assertFalse(result.isError, result.text)
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertTrue(json["success"]?.jsonPrimitive?.booleanOrNull == true, result.text)
            assertEquals(windowId, json["windowId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `open_terminal reports an unready cold-start window as a retryable timeout`() =
        runBlocking {
            // Nothing ever registers for the minted id: the error must say the wait timed
            // out and that a retry is worthwhile, not the generic "Failed to open" that
            // told the agent nothing about what went wrong.
            WorkspaceMcpToolProvider.windowCreator = { "mcp-never-ready-window" }
            WorkspaceMcpToolProvider.coldStartWindowWaitTimeoutMs = 150L

            val result = createTestCore().invoke("open_terminal", "{}")

            assertTrue(result.isError, result.text)
            assertTrue(result.text.contains("Timed out"), result.text)
            assertTrue(result.text.contains("retry"), result.text)
            assertFalse(result.text.contains("Failed to open terminal"), result.text)
        }

    @Test
    fun `close_workspace deletes disposable workspace`() =
        runBlocking {
            val core = createTestCore()

            // Create disposable
            val createResult = core.invoke("create_workspace", """{"isDisposable":true}""")
            val json = Json.parseToJsonElement(createResult.text).jsonObject
            val wsId = json["workspaceId"]!!.jsonPrimitive.content

            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId)))

            // Close disposable
            val closeResult = core.invoke("close_workspace", """{"workspaceId":"$wsId"}""")
            assertFalse(closeResult.isError)

            // Verify file was cleaned up
            val loadedAfterClose = fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId))
            assertTrue(loadedAfterClose == null)
        }

    @Test
    fun `open_terminal rejects command with newlines or control characters`() =
        runBlocking {
            val core = createTestCore()
            // A benign second line: a destructive one (`rm -rf /`) is now stopped earlier, at the
            // approval gate, even under this provider-wide ALLOW (#1577), so it would no longer
            // reach the tool's own newline check that this test is about.
            val args = """{"command":"echo hello\necho world"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("security check failed"))
        }

    @Test
    fun `open_terminal rejects dangerous working directory paths`() =
        runBlocking {
            val core = createTestCore()
            val args = """{"workingDirectory":"/tmp/../etc/passwd"}"""
            val result = core.invoke("open_terminal", args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("security check failed"))
        }

    @Test
    fun `open_workspace rejects dangerous project or workspace paths`() =
        runBlocking {
            val core = createTestCore()
            // projectPath is a destination (a terminal cwd), so it gets the strict gate:
            // shell metacharacters and traversal are both refused.
            // A slash-rooted Unix path is not absolute on Windows; use the native temp root
            // so these assertions exercise the security gate, not the absolute-path gate.
            val projectRoot = workspaceDir.absolutePath.replace('\\', '/')
            val badProjectArgs = """{"workspaceId":"test-ws","projectPath":"$projectRoot;rm -rf /"}"""
            val projectResult = core.invoke("open_workspace", badProjectArgs)
            assertTrue(projectResult.isError)
            assertTrue(projectResult.text.contains("Refusing to open"), projectResult.text)

            val badProjectTraversal = """{"workspaceId":"test-ws","projectPath":"$projectRoot/../etc"}"""
            val traversalResult = core.invoke("open_workspace", badProjectTraversal)
            assertTrue(traversalResult.isError)
            assertTrue(traversalResult.text.contains("Refusing to open"), traversalResult.text)

            // A NUL byte never reaches containment - the path cannot even be canonicalised -
            // and containment separately refuses paths outside the workspaces directory.
            val badFileArgs = """{"workspacePath":"/etc/shadow\u0000.json"}"""
            val fileResult = core.invoke("open_workspace", badFileArgs)
            assertTrue(fileResult.isError)
            assertTrue(fileResult.text.contains("security check failed"))
        }

    @Test
    fun `open_terminal opens exactly one terminal tab in a real window`() =
        runBlocking {
            val windowId = "open-terminal-real-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val busEvents = mutableListOf<String?>()
            val collection =
                launch {
                    TerminalEventBus.terminalOpenEvents.collect { busEvents.add(it.sourceWindowId) }
                }

            val core = createTestCore()
            val project = Files.createTempDirectory("open-terminal-cwd").toFile()
            tempDirs.add(project)
            val projectPath = project.absolutePath.replace('\\', '/')
            val args = """{"windowId":"$windowId","workingDirectory":"$projectPath","command":"echo hello"}"""
            val result = core.invoke("open_terminal", args)
            delay(50L)
            collection.cancel()

            assertFalse(result.isError, result.text)
            val json = Json.parseToJsonElement(result.text).jsonObject
            val tabId = json["tabId"]!!.jsonPrimitive.content

            // One terminal in the live tree, and the id returned is the one that is there.
            val allTabs = state.getAllPanels().flatMap { it.tabsComponent.tabsState.value.tabs }
            val terminalTabs = allTabs.filterIsInstance<TerminalTabInfo>()
            assertEquals(1, terminalTabs.size, "exactly one terminal tab must exist: $allTabs")
            assertEquals(tabId, terminalTabs.first().id)
            // and no bus event aimed at the window, whose collector would open a second one.
            assertTrue(
                busEvents.none { it == windowId },
                "no TerminalOpenEvent aimed at $windowId: $busEvents",
            )
        }

    @Test
    fun `open_workspace with createIfAbsent refuses reserved slot ids`() =
        runBlocking {
            val core = createTestCore()
            val result =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"last-session","createIfAbsent":true,"name":"Hijack"}""",
                )
            assertTrue(result.isError)
            assertTrue(result.text.contains("reserved slot"), result.text)
        }

    @Test
    fun `close_workspace on cold start does not create a window`() =
        runBlocking {
            val core = createTestCore()

            val createResult = core.invoke("create_workspace", """{"isDisposable":true}""")
            val wsId =
                Json
                    .parseToJsonElement(createResult.text)
                    .jsonObject["workspaceId"]!!
                    .jsonPrimitive.content
            // The create call awaited its disk write, so the file must exist now.
            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId)))

            // Zero registered windows: closing must not mint one, but still cleans the file.
            val closeResult = core.invoke("close_workspace", """{"workspaceId":"$wsId"}""")
            assertFalse(closeResult.isError, closeResult.text)
            assertEquals(0, windowCreatorCalls, "closing a workspace must never create a window")
            val json = Json.parseToJsonElement(closeResult.text).jsonObject
            assertTrue(json["fileDeleted"]?.jsonPrimitive?.booleanOrNull == true)
            assertTrue(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(wsId)) == null)
        }

    /**
     * The middle layer on its own: what a path-shaped id would become if it ever reached the name
     * derivation. `isSafeWorkspaceId` refuses such an id at the top of both handlers today, so the
     * invocation below is a regression guard for THAT gate; this assertion is the one that pins
     * this derivation, and it fails if `workspaceFileNameFor` stops sanitizing.
     */
    @Test
    fun `close_workspace derives the file to delete from the id, never from a path in it`() =
        runBlocking {
            val core = createTestCore()
            // A sibling of the workspace directory, which is where ~/.boss/*.json sits relative
            // to ~/Documents/BOSS/workspaces in production. On Windows the joined path
            // dir\workspace-disposable-\..\victim.json resolves lexically, missing component
            // or not; the assertion holds on every platform because the id never becomes a path.
            val victim = File(workspaceDir.parentFile, "victim-${workspaceDir.name}.json")
            victim.writeText("{}")
            try {
                val traversal = "${WorkspaceMcpToolProvider.DISPOSABLE_ID_PREFIX}/../${victim.name}"
                assertEquals(
                    "${WorkspaceMcpToolProvider.DISPOSABLE_ID_PREFIX}_.._${victim.name}",
                    WorkspaceMcpToolProvider.workspaceFileNameFor(traversal),
                )
                // Refused before that derivation is reached, by the id gate on dev.
                val result = core.invoke("close_workspace", """{"workspaceId":"$traversal"}""")
                assertTrue(result.isError, result.text)
                assertTrue(result.text.contains("Invalid workspaceId"), result.text)
                assertTrue(victim.exists(), "a workspace id must never reach a path outside the workspace directory")
            } finally {
                victim.delete()
            }
        }

    @Test
    fun `the file manager refuses a name that is not a bare file name`() =
        runBlocking {
            val victim = File(workspaceDir.parentFile, "victim-${workspaceDir.name}.json")
            victim.writeText(WorkspaceSerializer.serialize(savedSpaceFixture("victim", workspaceDir.absolutePath)))
            try {
                assertFalse(fileManager.deleteWorkspace("../${victim.name}"))
                assertTrue(victim.exists(), "deleteWorkspace must not follow a relative path out of the directory")
                assertNull(fileManager.loadWorkspace("../${victim.name}"))
                assertNull(fileManager.loadDocument("../${victim.name}"))
                assertFalse(fileManager.writeDocumentBlocking("../${victim.name}", "{}"))
                val escaping = savedSpaceFixture("x", workspaceDir.absolutePath)
                assertNull(fileManager.saveWorkspace(escaping, "../escaped.json"))
                assertFalse(File(workspaceDir.parentFile, "escaped.json").exists())
                assertEquals(
                    WorkspaceSerializer.serialize(savedSpaceFixture("victim", workspaceDir.absolutePath)),
                    victim.readText(),
                    "the file outside the directory is untouched",
                )
            } finally {
                victim.delete()
            }
        }

    /**
     * A regression guard for `isSafeWorkspaceId`, which is what refuses this today, kept because
     * the id gate and the name derivation are edited in different files by different changes and
     * removing either must fail something. The layer this PR adds is pinned by
     * `the file manager refuses a name that is not a bare file name`, which drives the manager
     * directly and fails without `isBareFileName`.
     */
    @Test
    fun `open_workspace cannot read a workspace file outside the workspace directory by id`() =
        runBlocking {
            val core = createTestCore()
            val outside = File(workspaceDir.parentFile, "outside-${workspaceDir.name}.json")
            outside.writeText(WorkspaceSerializer.serialize(savedSpaceFixture("Outside", workspaceDir.absolutePath)))
            try {
                val result = core.invoke("open_workspace", """{"workspaceId":"../${outside.name}"}""")
                assertTrue(result.isError, result.text)
                assertTrue(result.text.contains("Invalid workspaceId"), result.text)
                assertFalse(result.text.contains("\"workspaceName\":\"Outside\""), result.text)
            } finally {
                outside.delete()
            }
        }

    @Test
    fun `a workspace created by an id ending in json can be reopened by that id`() =
        runBlocking {
            val core = createTestCore()
            val created =
                core.invoke("open_workspace", """{"workspaceId":"round-trip.json","createIfAbsent":true}""")
            assertFalse(created.isError, created.text)
            val createdId =
                Json
                    .parseToJsonElement(created.text)
                    .jsonObject["workspaceId"]
                    ?.jsonPrimitive
                    ?.content
            // One file, named as the read path derives it: not `round-trip.json.json`.
            assertTrue(File(workspaceDir, "round-trip.json").isFile, workspaceDir.list()?.joinToString().orEmpty())
            assertFalse(File(workspaceDir, "round-trip.json.json").exists())

            // Reopening by either spelling finds the Space that was created, not a second one.
            for (id in listOf("round-trip.json", "round-trip")) {
                val reopened = core.invoke("open_workspace", """{"workspaceId":"$id"}""")
                assertFalse(reopened.isError, reopened.text)
                assertEquals(
                    createdId,
                    Json
                        .parseToJsonElement(reopened.text)
                        .jsonObject["workspaceId"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
            assertEquals(1, workspaceDir.list()?.count { it.startsWith("round-trip") })
        }

    @Test
    fun `the bare-name rule refuses the names Windows would resolve elsewhere`() {
        for (name in listOf("space.json", "my..space.json", "workspace-disposable-1.json", "..hidden")) {
            assertTrue(WorkspaceFileManagerCommon.isBareFileName(name), name)
        }
        for (
        name in
        listOf(
            "",
            ".",
            "..",
            "...",
            ".. ",
            ".  ",
            "../x.json",
            "..\\x.json",
            "dir/x.json",
            "C:x.json",
            "x.json:stream",
            "x\u0000.json",
            "x\u0001.json",
        )
        ) {
            assertFalse(WorkspaceFileManagerCommon.isBareFileName(name), "must refuse: '$name'")
        }
    }

    @Test
    fun `an empty name addresses the workspace directory and is refused`() =
        runBlocking {
            // Paths.get(dir, "") is dir itself, so before the bare-name rule these aimed at the
            // workspace directory rather than at a file in it.
            assertFalse(fileManager.deleteWorkspace(""))
            assertNull(fileManager.loadWorkspace(""))
            assertNull(fileManager.loadDocument(""))
            assertFalse(fileManager.writeDocumentBlocking("", "{}"))
            assertTrue(workspaceDir.isDirectory, "the workspace directory itself must survive")
        }

    @Test
    fun `close_workspace errors when nothing is released and nothing is deleted`() =
        runBlocking {
            val core = createTestCore()
            val result = core.invoke("close_workspace", """{"workspaceId":"no-such-space"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("nothing was closed"), result.text)
            // Zero registered windows is said plainly so the agent knows there is no
            // windowId it could pass - "(none)" alone read like a missing target.
            assertTrue(result.text.contains("none are open"), result.text)
        }

    // ------------------------------------------------------------------
    // open_workspace path mode (bootstrap consolidated from #799)
    // ------------------------------------------------------------------

    /** Minimal stand-in; the applier only builds TabInfo, it never renders the component. */
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val stubTabRegistry =
        TabRegistry().apply {
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
        }

    @Test
    fun `open_workspace path mode opens a project directory and returns usable ids`(): Unit =
        runBlocking {
            val windowId = "ws-path-mode-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val project = Files.createTempDirectory("ws-path-mode-project").toFile()
            tempDirs.add(project)

            val core = createTestCore()
            val result =
                core.invoke(
                    "open_workspace",
                    """{"path":"${project.absolutePath.replace('\\', '/')}","windowId":"$windowId"}""",
                )
            assertFalse(result.isError, result.text)

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("opened", json["status"]?.jsonPrimitive?.content)
            assertEquals(windowId, json["windowId"]?.jsonPrimitive?.content)

            // The panel id must address a LIVE panel: applyWorkspace throws the saved layout's
            // panel ids away and builds into the panel at "main", so the saved id
            // (BOOTSTRAP_PANEL_ID) does not exist on screen.
            val wsId = json["workspaceId"]!!.jsonPrimitive.content
            val panelId = json["panelId"]!!.jsonPrimitive.content
            assertEquals(state.activePanelIdForWorkspace(wsId), panelId)
            val liveTabs = state.getPanelTabsComponent(panelId)
            assertNotNull(liveTabs, "panelId must resolve in the live tree")
            val tabs = liveTabs.tabsState.value.tabs
            val liveTerminalTabs = tabs.filterIsInstance<TerminalTabInfo>()
            assertEquals(1, liveTerminalTabs.size, "the bootstrap Space has one terminal tab")
            assertEquals(project.canonicalPath, json["projectPath"]?.jsonPrimitive?.content)
            assertNotNull(json["workspaceId"]?.jsonPrimitive?.content)
            assertNotNull(json["workspaceName"]?.jsonPrimitive?.content)
        }

    @Test
    fun `open_workspace path mode re-enters the running Space instead of duplicating it`() =
        runBlocking {
            val windowId = "ws-path-reenter-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val project = Files.createTempDirectory("ws-path-reenter-project").toFile()
            tempDirs.add(project)
            val projectPath = project.absolutePath.replace('\\', '/')

            val core = createTestCore()
            val first = core.invoke("open_workspace", """{"path":"$projectPath"}""")
            assertFalse(first.isError, first.text)
            val firstId =
                Json
                    .parseToJsonElement(first.text)
                    .jsonObject["workspaceId"]!!
                    .jsonPrimitive.content

            val second = core.invoke("open_workspace", """{"path":"$projectPath"}""")
            assertFalse(second.isError, second.text)
            val payload = Json.parseToJsonElement(second.text).jsonObject
            assertEquals("reused", payload["status"]?.jsonPrimitive?.content)
            assertEquals(firstId, payload["workspaceId"]?.jsonPrimitive?.content)

            // and the panel did not grow a second terminal for the same project
            val onScreen = extractCurrentWorkspace(state, projectPath = project.canonicalPath)
            assertEquals(1, (onScreen.layout as SplitConfig.SinglePanel).panel.tabs.size)
        }

    @Test
    fun `open_workspace path mode refuses a saved Space carrying terminal startup commands`() =
        runBlocking {
            val windowId = "ws-path-startup-commands-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val project = Files.createTempDirectory("ws-path-startup-commands-project").toFile()
            tempDirs.add(project)
            val projectPath = project.canonicalPath.replace('\\', '/')
            val marker = File(project, "pwned-marker")

            // The issue's hostile shape: a saved Space for this project whose terminal types a
            // command that writes a marker file. Registered in the manager, because that list
            // is what path mode matches against, and saved to the file manager so the id mode
            // resolves it too. The id is unique and the project path is a per-test temp
            // directory, so the entry cannot collide with any later test in this JVM.
            val hostile =
                LayoutWorkspace(
                    id = "workspace-path-marker",
                    name = "Marker command",
                    description = "test",
                    layout =
                        SplitConfig.SinglePanel(
                            PanelConfig(
                                "shell",
                                listOf(
                                    TabConfig(
                                        type = "terminal",
                                        title = "Shell",
                                        initialCommand = "touch ${marker.absolutePath.replace('\\', '/')}",
                                    ),
                                ),
                            ),
                        ),
                    projectPath = projectPath,
                )
            // The manager singleton loads its list asynchronously once per process, and that
            // first load REPLACES the whole list - a registration landing before it completes
            // is swapped out (the Windows CI flake: matchExistingSpace then saw nothing for
            // the project path and minted a fresh Space). Waiting for a non-empty list is not
            // enough: the create_workspace tests in this class register into the same
            // singleton with no wait and JUnit method order is not fixed, so a pre-load
            // registration can satisfy that wait while the load is still pending. Only the
            // merged post-load list holds every shipped layout, so wait for all of them
            // before registering.
            withTimeout(5_000L) {
                workspaceManager.workspaces.first { list ->
                    PredefinedWorkspaces.allIds.all { id -> list.any { it.id == id } }
                }
            }
            workspaceManager.registerWorkspace(hostile)
            registeredManagerIds.add(hostile.id)
            fileManager.saveWorkspace(hostile)

            val core = createTestCore()
            val byPath =
                core.invoke(
                    "open_workspace",
                    """{"path":"$projectPath","windowId":"$windowId"}""",
                )
            assertTrue(byPath.isError, byPath.text)
            assertTrue(byPath.text.startsWith("Workspace contains terminal startup commands"), byPath.text)

            // The id mode reaches the same Space through McpStoredCommandSource instead: it asks
            // the operator with the commands listed. This core has no operator, so the prompt goes
            // unanswered and nothing runs - asked, not refused, and not run either way.
            val byId =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"workspace-path-marker","windowId":"$windowId"}""",
                )
            assertTrue(byId.isError, byId.text)
            assertEquals("MCP tool timed out waiting for operator approval", byId.text)

            // The gate fired before the Space was entered: nothing was loaded into the
            // window, so no terminal carrying the command was applied and no shell ran it.
            assertEquals(null, state.currentWorkspaceId)
            assertFalse(marker.exists(), "the startup command must not have been typed")
        }

    @Test
    fun `open_workspace path mode still re-enters a saved Space without startup commands`() =
        runBlocking {
            val windowId = "ws-path-benign-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val project = Files.createTempDirectory("ws-path-benign-project").toFile()
            tempDirs.add(project)
            val projectPath = project.canonicalPath.replace('\\', '/')

            // A Space the operator saved for this project, terminal and all, but with no
            // startup command: the restore path the gate must leave alone.
            val benign = savedSpaceFixture("workspace-path-benign", projectPath)
            // Same one-shot load race as the refusal test above: the first load replaces the
            // whole list, so register only after the merged post-load list has landed (every
            // shipped layout present, which a pre-load registration alone cannot satisfy).
            withTimeout(5_000L) {
                workspaceManager.workspaces.first { list ->
                    PredefinedWorkspaces.allIds.all { id -> list.any { it.id == id } }
                }
            }
            workspaceManager.registerWorkspace(benign)
            registeredManagerIds.add(benign.id)

            val result =
                createTestCore().invoke(
                    "open_workspace",
                    """{"path":"$projectPath","windowId":"$windowId"}""",
                )
            assertFalse(result.isError, result.text)

            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("reused", json["status"]?.jsonPrimitive?.content)
            assertEquals("workspace-path-benign", json["workspaceId"]?.jsonPrimitive?.content)

            // Its terminal really was applied, so the refusal above is a gate and not a
            // blanket "path mode never re-enters saved Spaces".
            val onScreen = extractCurrentWorkspace(state, projectPath = project.canonicalPath)
            assertEquals(1, (onScreen.layout as SplitConfig.SinglePanel).panel.tabs.size)
        }

    @Test
    fun `open_workspace path mode refuses a relative path`() =
        runBlocking {
            val core = createTestCore()
            val result = core.invoke("open_workspace", """{"path":"some/relative/dir"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("absolute"), result.text)
        }

    @Test
    fun `open_workspace path mode is a clear error for a missing directory`() =
        runBlocking {
            val core = createTestCore()
            val missing = File(workspaceDir, "no-such-directory").absolutePath.replace('\\', '/')
            val result = core.invoke("open_workspace", """{"path":"$missing"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("not an existing directory"), result.text)
        }

    @Test
    fun `open_workspace path mode rejects a file as a project directory`() =
        runBlocking {
            val core = createTestCore()
            val file = File(workspaceDir, "plain-file.txt").apply { writeText("content") }
            val result = core.invoke("open_workspace", """{"path":"${file.absolutePath.replace('\\', '/')}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("not an existing directory"), result.text)
        }

    @Test
    fun `open_workspace path mode rejects shell-shaped and traversal paths`() =
        runBlocking {
            val core = createTestCore()

            // A real directory whose name contains a shell metacharacter is still refused.
            val semicolonDir = File(workspaceDir, "proj;ect").apply { mkdirs() }
            val result = core.invoke("open_workspace", """{"path":"${semicolonDir.absolutePath.replace('\\', '/')}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("Refusing to open"), result.text)

            // A traversal path that resolves to an existing directory is refused the same way.
            File(workspaceDir, "traversal-parent").mkdirs()
            val child = File(workspaceDir, "traversal-parent/traversal-child").apply { mkdirs() }
            val traversal = "${child.absolutePath}/../traversal-child".replace('\\', '/')
            val traversalResult = core.invoke("open_workspace", """{"path":"$traversal"}""")
            assertTrue(traversalResult.isError)
            assertTrue(traversalResult.text.contains("Refusing to open"), traversalResult.text)
        }

    @Test
    fun `open_workspace path mode lists open windows for an unknown window id`() =
        runBlocking {
            val state = SplitViewState(stubTabRegistry, "ws-unknown-window")
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register("ws-unknown-window", state)

            val project = Files.createTempDirectory("ws-path-unknown-window").toFile()
            tempDirs.add(project)

            val core = createTestCore()
            val result =
                core.invoke(
                    "open_workspace",
                    """{"path":"${project.absolutePath.replace('\\', '/')}","windowId":"no-such-window"}""",
                )
            assertTrue(result.isError)
            assertTrue(result.text.contains("not registered or has been closed"), result.text)
            assertTrue(result.text.contains("ws-unknown-window"), result.text)
        }

    @Test
    fun `open_workspace path mode errors when no window is available`() =
        runBlocking {
            WorkspaceMcpToolProvider.windowCreator = null

            val project = Files.createTempDirectory("ws-path-nowindow").toFile()
            tempDirs.add(project)

            val core = createTestCore()
            val result = core.invoke("open_workspace", """{"path":"${project.absolutePath.replace('\\', '/')}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("No active windows exist"), result.text)
        }

    @Test
    fun `open_workspace refuses path together with a workspace selector`() =
        runBlocking {
            val core = createTestCore()
            val project = Files.createTempDirectory("ws-path-exclusive").toFile()
            tempDirs.add(project)
            val mutualExclusionArgs =
                """{"path":"${project.absolutePath.replace('\\', '/')}",""" +
                    """"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}"""
            val result = core.invoke("open_workspace", mutualExclusionArgs)
            assertTrue(result.isError)
            assertTrue(result.text.contains("not both"), result.text)
        }

    @Test
    fun `tilde expands to the home directory only at the start of a path`() {
        assertEquals("/home/boss/projects", expandTilde("~/projects", home = "/home/boss"))
        assertEquals("/home/boss", expandTilde("~", home = "/home/boss"))
        assertEquals("/opt/~literal/projects", expandTilde("/opt/~literal/projects", home = "/home/boss"))
        assertEquals("~/unchanged", expandTilde("~/unchanged", home = null))
    }

    @Test
    fun `bootstrap space is one terminal panel named for the project`() {
        val space = buildBootstrapSpace("/work/some-project")

        assertEquals("some-project", space.name)
        assertEquals("/work/some-project", space.projectPath)
        val panel = (space.layout as SplitConfig.SinglePanel).panel
        assertEquals(WorkspaceMcpToolProvider.BOOTSTRAP_PANEL_ID, panel.id)
        val tab = panel.tabs.single()
        assertEquals("terminal", tab.type)
        assertEquals("/work/some-project", tab.workingDirectory)
    }

    @Test
    fun `matchExistingSpace prefers a running space over a non-running one`() {
        val running = savedSpaceFixture("workspace-running", "/work/p")
        val shelved = savedSpaceFixture("workspace-shelved", "/work/p")

        val match =
            matchExistingSpace(
                remembered = null,
                savedSpaces = listOf(shelved, running),
                runningIdsInWindow = setOf("workspace-running"),
                projectPath = "/work/p",
            )

        assertEquals("workspace-running", match?.id)
    }

    @Test
    fun `matchExistingSpace never matches spaces for other projects`() {
        val other = savedSpaceFixture("workspace-other", "/work/other")

        assertNull(
            matchExistingSpace(
                remembered = null,
                savedSpaces = listOf(other),
                runningIdsInWindow = setOf("workspace-other"),
                projectPath = "/work/p",
            ),
        )
    }

    @Test
    fun `matchExistingSpace reuses a remembered space even when it is not running`() {
        val remembered = buildBootstrapSpace("/work/p")

        val match =
            matchExistingSpace(
                remembered = remembered,
                savedSpaces = emptyList(),
                runningIdsInWindow = emptySet(),
                projectPath = "/work/p",
            )

        assertEquals(remembered.id, match?.id)
    }

    @Test
    fun `matchExistingSpace matches a saved Space whose project path is spelled differently`() {
        val trailingSeparator = savedSpaceFixture("workspace-trailing", "/work/p/")

        val match =
            matchExistingSpace(
                remembered = null,
                savedSpaces = listOf(trailingSeparator),
                runningIdsInWindow = emptySet(),
                projectPath = "/work/p",
            )

        assertEquals("workspace-trailing", match?.id)
    }

    @Test
    fun `close_workspace releases a running workspace from the target window`() =
        runBlocking {
            val windowId = "ws-close-release-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val core = createTestCore()
            val openResult =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}","windowId":"$windowId"}""",
                )
            assertFalse(openResult.isError, openResult.text)

            val closeResult =
                core.invoke(
                    "close_workspace",
                    """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}","windowId":"$windowId"}""",
                )
            assertFalse(closeResult.isError, closeResult.text)
            val json = Json.parseToJsonElement(closeResult.text).jsonObject
            assertTrue(json["releasedHere"]?.jsonPrimitive?.booleanOrNull == true, closeResult.text)
        }

    @Test
    fun `close_workspace without windowId closes in the single open window`() =
        runBlocking {
            val windowId = "ws-close-sole-window"
            val state = SplitViewState(stubTabRegistry, windowId)
            createdSplitViewStates.add(state)
            SplitViewStateRegistry.register(windowId, state)

            val core = createTestCore()
            val openResult =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}","windowId":"$windowId"}""",
                )
            assertFalse(openResult.isError, openResult.text)

            // One window is the only possible target, so no windowId is needed - the call
            // used to work here and must keep working.
            val closeResult =
                core.invoke(
                    "close_workspace",
                    """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}""",
                )
            assertFalse(closeResult.isError, closeResult.text)
            val json = Json.parseToJsonElement(closeResult.text).jsonObject
            assertTrue(json["releasedHere"]?.jsonPrimitive?.booleanOrNull == true, closeResult.text)
        }

    @Test
    fun `close_workspace without windowId names the open windows when it cannot pick one`() =
        runBlocking {
            val first = SplitViewState(stubTabRegistry, "ws-close-window-a")
            val second = SplitViewState(stubTabRegistry, "ws-close-window-b")
            createdSplitViewStates.add(first)
            createdSplitViewStates.add(second)
            SplitViewStateRegistry.register("ws-close-window-a", first)
            SplitViewStateRegistry.register("ws-close-window-b", second)

            val core = createTestCore()
            val closeResult =
                core.invoke(
                    "close_workspace",
                    """{"workspaceId":"${PredefinedWorkspaces.DUAL_TERMINAL_ID}"}""",
                )

            // Ambiguous targeting used to surface as a generic "nothing was closed" - an
            // agent cannot act on that. The error must name the candidates to retry with.
            assertTrue(closeResult.isError, closeResult.text)
            assertTrue(closeResult.text.contains("ws-close-window-a"), closeResult.text)
            assertTrue(closeResult.text.contains("ws-close-window-b"), closeResult.text)
            assertTrue(closeResult.text.contains("windowId"), closeResult.text)
        }

    @Test
    fun `close_workspace ambiguity leaves a disposable file untouched`(): Unit =
        runBlocking {
            val first = SplitViewState(stubTabRegistry, "ws-close-disposable-a")
            val second = SplitViewState(stubTabRegistry, "ws-close-disposable-b")
            createdSplitViewStates.add(first)
            createdSplitViewStates.add(second)
            SplitViewStateRegistry.register("ws-close-disposable-a", first)
            SplitViewStateRegistry.register("ws-close-disposable-b", second)

            val core = createTestCore()
            val created = core.invoke("create_workspace", """{"isDisposable":true}""")
            assertFalse(created.isError, created.text)
            val workspaceId =
                Json
                    .parseToJsonElement(created.text)
                    .jsonObject["workspaceId"]!!
                    .jsonPrimitive.content
            val fileName = WorkspaceFileManagerCommon.fileNameForId(workspaceId)
            assertNotNull(fileManager.loadWorkspace(fileName))

            val refused = core.invoke("close_workspace", """{"workspaceId":"$workspaceId"}""")
            assertTrue(refused.isError, refused.text)
            assertTrue(refused.text.contains("windowId"), refused.text)
            assertNotNull(fileManager.loadWorkspace(fileName), "an ambiguous close must not delete the file")
        }

    @Test
    fun `close_workspace does not delete a saved workspace whose id contains disposable`(): Unit =
        runBlocking {
            val core = createTestCore()
            val createResult =
                core.invoke(
                    "open_workspace",
                    """{"workspaceId":"disposable-env","name":"Env","createIfAbsent":true}""",
                )
            assertFalse(createResult.isError, createResult.text)
            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("disposable-env")))

            // The open above applied the Space to the window, so closing releases it there; the
            // reply says exactly that and that no file was deleted.
            val closeResult = core.invoke("close_workspace", """{"workspaceId":"disposable-env"}""")
            assertFalse(closeResult.isError, closeResult.text)
            val closeJson = Json.parseToJsonElement(closeResult.text).jsonObject
            assertTrue(closeJson["releasedHere"]?.jsonPrimitive?.booleanOrNull == true, closeResult.text)
            assertTrue(closeJson["fileDeleted"]?.jsonPrimitive?.booleanOrNull == false, closeResult.text)

            // A user's saved Space whose id merely contains "disposable" survives.
            assertNotNull(fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("disposable-env")))
        }

    private fun savedSpaceFixture(
        id: String,
        projectPath: String,
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(id = "panel-$id", tabs = listOf(TabConfig(type = "terminal", title = "Terminal"))),
            ),
        projectPath = projectPath,
    )
}
