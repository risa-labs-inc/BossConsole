package ai.rever.boss.mcp

import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalOpenEvent
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            "test-window-window-1"
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
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        createdSplitViewStates.forEach { it.dispose() }
        createdSplitViewStates.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
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

    @Test
    fun `saved workspace startup commands require an explicit terminal invocation`() =
        runBlocking {
            val workspace =
                LayoutWorkspace(
                    id = "hidden-command",
                    name = "Hidden command",
                    description = "test",
                    layout =
                        SplitConfig.SinglePanel(
                            PanelConfig(
                                "shell",
                                listOf(TabConfig(type = "terminal", title = "Shell", initialCommand = "echo hidden")),
                            ),
                        ),
                )
            fileManager.saveWorkspace(workspace)
            val file = File(workspaceDir, WorkspaceFileManagerCommon.fileNameForId(workspace.id))
            val filePath = file.absolutePath.replace('\\', '/')
            val selectors =
                listOf(
                    """{"workspaceId":"hidden-command"}""",
                    """{"workspacePath":"$filePath"}""",
                )
            for (selector in selectors) {
                val result = createTestCore().invoke("open_workspace", selector)
                assertTrue(result.isError)
                assertTrue(result.text.contains("startup commands"), result.text)
            }
        }

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
        assertFalse(empty.hasInitialCommands())
        assertTrue(SplitConfig.VerticalSplit(empty, SplitConfig.HorizontalSplit(empty, commands)).hasInitialCommands())
    }

    @Test
    fun `tools exposes workspace and terminal lifecycle operations and aliases`() {
        val tools = WorkspaceMcpToolProvider.tools().map { it.name }.toSet()
        assertTrue(tools.contains("list_workspaces"))
        assertTrue(tools.contains("workspace_list"))
        assertTrue(tools.contains("open_workspace"))
        assertTrue(tools.contains("workspace_open"))
        assertTrue(tools.contains("create_workspace"))
        assertTrue(tools.contains("workspace_create"))
        assertTrue(tools.contains("open_terminal"))
        assertTrue(tools.contains("terminal_open"))
        assertTrue(tools.contains("close_workspace"))
        assertTrue(tools.contains("workspace_close"))
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
            val tabReg1 = TabRegistry()
            val tabReg2 = TabRegistry()
            val state1 = SplitViewState(tabReg1, "window-multi-1")
            val state2 = SplitViewState(tabReg2, "window-multi-2")
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
            val args = """{"command":"echo hello\nrm -rf /"}"""
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

    @Test
    fun `close_workspace errors when nothing is released and nothing is deleted`() =
        runBlocking {
            val core = createTestCore()
            val result = core.invoke("close_workspace", """{"workspaceId":"no-such-space"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("nothing was closed"), result.text)
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

            // Nothing is released and nothing is deleted, so the tool says so instead of
            // reporting a success that would leave the agent thinking the space is gone.
            val closeResult = core.invoke("close_workspace", """{"workspaceId":"disposable-env"}""")
            assertTrue(closeResult.isError, closeResult.text)
            assertTrue(closeResult.text.contains("nothing was closed"), closeResult.text)

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
