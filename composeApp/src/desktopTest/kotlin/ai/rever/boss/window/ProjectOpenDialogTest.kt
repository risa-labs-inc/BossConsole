package ai.rever.boss.window

import ai.rever.boss.components.dialogs.rememberProjectOpener
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectOpenDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `destination buttons route to only the selected callback`() =
        withProjects { a, b ->
            val current = mutableListOf<Project>()
            val new = mutableListOf<Project>()
            lateinit var opener: ProjectOpenCoordinator
            rule.setContent { opener = rememberProjectOpener(a, { current += it }, { new += it }) }
            rule.runOnIdle { opener.request(b) }
            rule.onNodeWithText("Current Window").performClick()
            rule.runOnIdle {
                assertEquals(listOf(b), current)
                assertTrue(new.isEmpty())
                opener.request(b)
            }
            rule.onNodeWithText("New Window").performClick()
            rule.runOnIdle {
                assertEquals(listOf(b), current)
                assertEquals(listOf(b), new)
            }
        }

    @Test
    fun `confirmation error survives the mode dialogs post action dismissal`() =
        withProjects { a, b ->
            lateinit var opener: ProjectOpenCoordinator
            rule.setContent {
                opener = rememberProjectOpener(a, { error("Unexpected switch") }, { error("Unexpected window") })
            }
            rule.runOnIdle { opener.request(b) }
            rule.onNodeWithText("Current Window").assertIsDisplayed()
            rule.runOnIdle { assertTrue(java.io.File(b.path).delete()) }
            rule.onNodeWithText("Current Window").performClick()
            rule.onNodeWithText("Project Not Found").assertIsDisplayed()
            rule.onNodeWithText("OK").performClick()
            rule.onNodeWithText("Project Not Found").assertDoesNotExist()
        }

    private fun withProjects(block: (Project, Project) -> Unit) {
        val root = Files.createTempDirectory("project-open-dialog").toFile()
        try {
            val a = root.resolve("a").apply { mkdir() }
            val b = root.resolve("b").apply { mkdir() }
            block(Project("A", a.path), Project("B", b.path))
        } finally {
            root.deleteRecursively()
        }
    }
}
