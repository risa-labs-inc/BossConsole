package ai.rever.boss.components.dialogs

import ai.rever.boss.project.templates.ProjectTemplate
import ai.rever.boss.window.Project
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewProjectWizardRetryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `retry keeps template and draft and revalidates partial destination before corrected success`() {
        val parent = Files.createTempDirectory("new-project-retry").toFile()
        val calls = mutableListOf<Triple<String, String, ProjectTemplate>>()
        var opened: Project? = null
        try {
            rule.setContent {
                NewProjectWizardDialog({}, { opened = it }, { name, path, template, _ ->
                    calls += Triple(name, path, template)
                    if (calls.size == 1) {
                        parent.resolve(name).mkdirs()
                        parent.resolve("original/keep.txt").writeText("keep")
                        Result.failure(IllegalStateException("Creation interrupted"))
                    } else {
                        Result.success(Project(name, parent.resolve(name).path, 0L))
                    }
                })
            }
            rule.onNodeWithText("Kotlin/JVM").performClick()
            rule.onNodeWithText("Next").performClick()
            fields()[0].performTextReplacement("original")
            fields()[1].performTextReplacement(parent.path)
            awaitValidation()
            rule.onNodeWithText("Create Project").assertIsEnabled().performClick()
            rule.onNodeWithText("Try Again").performClick()
            rule.onNodeWithText("New Kotlin/JVM Project").assertExists()
            fields()[0].assertTextEquals("original")
            fields()[1].assertTextEquals(parent.path)
            rule.onNodeWithText("Create Project").assertIsNotEnabled()
            rule.waitUntil(5_000) {
                rule
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("A project with this name already exists at this location"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.onNodeWithText("Create Project").assertIsNotEnabled()
            assertTrue(parent.resolve("original/keep.txt").exists())
            fields()[0].performTextReplacement("corrected")
            awaitValidation()
            rule.onNodeWithText("Create Project").assertIsEnabled().performClick()
            rule.onNodeWithText("Open Project").performClick()
            rule.runOnIdle {
                assertEquals(listOf("original", "corrected"), calls.map { it.first })
                assertTrue(calls.all { it.second == parent.path && it.third == ProjectTemplate.KotlinJvm })
                assertEquals("corrected", opened?.name)
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `retry permits same destination after transient failure and back preserves selection and edits`() {
        val parent = Files.createTempDirectory("new-project-transient").toFile()
        var calls = 0
        try {
            rule.setContent {
                NewProjectWizardDialog({}, {}, { _, _, _, _ ->
                    calls++
                    Result.failure(IllegalStateException("Unavailable"))
                })
            }
            rule.onNodeWithText("Node.js").performClick()
            rule.onNodeWithText("Next").performClick()
            fields()[0].performTextReplacement("my-node")
            fields()[1].performTextReplacement(parent.path)
            awaitValidation()
            rule.onNodeWithText("Create Project").performClick()
            rule.onNodeWithText("Try Again").performClick()
            awaitValidation()
            rule.onNodeWithText("Create Project").assertIsEnabled()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Next").assertIsEnabled().performClick()
            fields()[0].assertTextEquals("my-node")
            fields()[1].assertTextEquals(parent.path)
            rule.onNodeWithText("New Node.js Project").assertExists()
            awaitValidation()
            rule.onNodeWithText("Create Project").performClick()
            rule.onNodeWithText("Try Again").performClick()
            rule.runOnIdle { assertEquals(2, calls) }
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun fields() = rule.onAllNodes(hasSetTextAction())

    private fun awaitValidation() {
        rule.waitUntil(5_000) {
            !rule
                .onNodeWithText("Create Project")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Disabled)
        }
    }
}
