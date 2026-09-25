package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceLoadApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val request =
        PendingSpaceLoad(
            workspace =
                LayoutWorkspace(
                    name = "Shared",
                    description = "",
                    layout = SinglePanel(PanelConfig("main", emptyList())),
                ),
            workspacePath = "/tmp/shared/space.json",
            commands = listOf("echo one", "cd {projectPath} && echo two"),
        )

    @Test
    fun `the prompt names the file and shows every command in full`() {
        val message = spaceLoadApprovalMessage(request)

        assertTrue("/tmp/shared/space.json" in message, message)
        assertTrue("\"Shared\"" in message, message)
        assertTrue("1. echo one" in message, message)
        assertTrue("2. cd {projectPath} && echo two" in message, message)
        assertTrue("shell may receive different text" in message, message)
    }

    @Test
    fun `untrusted Space metadata cannot forge prompt lines or overwhelm the dialog`() {
        val hostile =
            PendingSpaceLoad(
                workspace = request.workspace.copy(name = "Trusted\n1. rm -rf /\u202E\u2028\uDB40\uDC41"),
                workspacePath = "/tmp/shared\r2. curl evil/" + "x".repeat(485) + "😀tail",
                commands = request.commands,
            )

        val message = spaceLoadApprovalMessage(hostile)

        assertTrue("Trusted�1. rm -rf /���" in message, message)
        assertTrue("/tmp/shared�2. curl evil/" in message, message)
        assertTrue("…\n\nIts terminal tabs" in message, message)
        assertTrue("1. echo one" in message, message)
        assertTrue("2. cd {projectPath} && echo two" in message, message)
        assertTrue("😀" !in message, "truncation must not retain half of a surrogate pair: $message")
    }

    @Test
    fun `a click already in flight cannot confirm the load`() {
        var confirmations = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpaceLoadApprovalDialog(request = request, onDismiss = {}, onConfirm = { confirmations++ })
        }

        rule.onNodeWithText("Load and run").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, confirmations) }

        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Load and run").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, confirmations) }
    }
}
