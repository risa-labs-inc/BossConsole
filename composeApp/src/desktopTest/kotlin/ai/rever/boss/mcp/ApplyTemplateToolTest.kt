package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * apply_template: the agent-side half of the Space picker's Templates section.
 * Until this tool an agent could see a template (list_workspaces isTemplate=true)
 * but could not materialise one - it had to tell the human to go click the picker.
 *
 * Standalone harness mirroring WorkspaceMcpToolProviderTest's setup: the provider's
 * test hooks give it a temp workspace dir, a window creator, and a fast timeout.
 */
class ApplyTemplateToolTest {
    private val tempDirs = mutableListOf<File>()
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager
    private var originalTimeoutMs: Long = 5000L

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("apply-template-test").toFile()
        tempDirs.add(dir)
        workspaceDir = dir
        fileManager = WorkspaceFileManager(directoryOverride = dir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        WorkspaceMcpToolProvider.windowCreator = { "apply-template-window-1" }
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        originalTimeoutMs = WorkspaceMcpToolProvider.splitViewWaitTimeoutMs
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = originalTimeoutMs
        SplitViewStateRegistry.getAllStates().keys.toList().forEach {
            SplitViewStateRegistry.unregister(it)
        }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun projectDir(): File {
        val dir = File(workspaceDir, "my-project")
        dir.mkdirs()
        return dir
    }

    private fun argsFor(
        templateId: String,
        project: File,
    ): String = """{"templateId":"$templateId","projectPath":"${project.absolutePath.replace('\\', '/')}"}"""

    private fun createTestCore(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    private suspend fun invoke(args: String): McpToolResult {
        val core = createTestCore()
        return core.invoke("apply_template", args)
    }

    @Test
    fun `the tool is registered under both names`() =
        runBlocking {
            val tools = WorkspaceMcpToolProvider.tools().map { it.name }.toSet()
            assertTrue(tools.contains("apply_template"), "primary name")
            assertTrue(tools.contains("workspace_apply_template"), "namespaced alias")
        }

    @Test
    fun `an unknown template is refused with the discovery pointer`() =
        runBlocking {
            val result = invoke(argsFor("workspace-no-such-template", projectDir()))
            assertTrue(result.isError)
            assertTrue(
                result.text.contains("Unknown templateId"),
                "names the problem: ${result.text}",
            )
            assertTrue(
                result.text.contains("list_workspaces"),
                "points the agent at discovery: ${result.text}",
            )
        }

    @Test
    fun `a template with no placeholders is refused with the direct-open pointer`() =
        runBlocking {
            val result = invoke(argsFor(PredefinedWorkspaces.BROWSER_ONLY_ID, projectDir()))
            assertTrue(result.isError)
            assertTrue(result.text.contains("open_workspace"), "routes to the right tool: ${result.text}")
            assertTrue(result.text.contains(PredefinedWorkspaces.BROWSER_ONLY_ID))
        }

    @Test
    fun `a relative project path is refused`() =
        runBlocking {
            val result =
                invoke(
                    """{"templateId":"${PredefinedWorkspaces.CLAUDE_CODE_ID}","projectPath":"relative/path"}""",
                )
            assertTrue(result.isError)
        }

    @Test
    fun `a missing project path argument is refused`() =
        runBlocking {
            val result = invoke("""{"templateId":"${PredefinedWorkspaces.CLAUDE_CODE_ID}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("projectPath is required"))
        }

    @Test
    fun `materialise a template against a real project directory`() =
        runBlocking {
            val project = projectDir()
            val tabRegistry = TabRegistry()
            val state = SplitViewState(tabRegistry, "apply-template-window-1")
            SplitViewStateRegistry.register("apply-template-window-1", state)
            try {
                val result = invoke(argsFor(PredefinedWorkspaces.DUAL_TERMINAL_ID, project))
                assertFalse(result.isError, "Expected success: ${result.text}")

                // The materialised Space is named for template+project (the picker's rule) and saved.
                assertTrue(
                    result.text.contains("Dual Terminal (my-project)"),
                    "picker's naming rule: ${result.text}",
                )
                assertTrue(result.text.contains("saved"))

                // The persistence half of the picker's contract: the materialised Space is
                // SAVED under its own id. Parse the id out of the tool's response and re-load
                // the Space from the file manager this test wired in.
                val idMatch = Regex("id: ([a-z-]+\\d+)").find(result.text)
                assertTrue(idMatch != null, "response carries the new Space's id: ${result.text}")
                val materialisedId = idMatch!!.groupValues[1]
                val saved =
                    WorkspaceFileManagerCommon.fileNameForId(materialisedId)
                val reloaded = fileManager.loadWorkspace(saved)
                assertTrue(reloaded != null, "the Space is persisted for re-entry")
                assertTrue(
                    reloaded!!.name == "Dual Terminal (my-project)",
                    "the persisted Space keeps the materialised name",
                )

                // The picker-list half: the manager's workspaces StateFlow holds the materialised
                // Space keyed by its id. Without this, the file write alone would leave the Space
                // invisible in the picker until the next launch (a plain saveWorkspaceBlocking
                // call only touches the file, not `_workspaces`). The response says "saved and
                // re-enterable" - re-enterable means the picker lists it now, not on relaunch.
                val inList =
                    workspaceManager.workspaces.value.any { it.id == materialisedId }
                assertTrue(
                    inList,
                    "the materialised Space is in the manager's workspaces list: " +
                        "${workspaceManager.workspaces.value.map { it.id }}",
                )
            } finally {
                state.dispose()
            }
        }
}
