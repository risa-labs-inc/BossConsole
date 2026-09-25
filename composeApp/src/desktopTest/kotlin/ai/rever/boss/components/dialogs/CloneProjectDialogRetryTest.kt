package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.git.GitOperationResult
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloneProjectDialogRetryTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `retry retains edited draft and revalidates a partial destination`() {
        val parent = Files.createTempDirectory("clone-retry").toFile()
        val partial = parent.resolve("custom-name")
        val calls = mutableListOf<Pair<String, String>>()
        try {
            rule.setContent {
                CloneProjectDialog({}, {}, { url, path, _ ->
                    calls += url to path
                    partial.mkdirs()
                    partial.resolve("keep.txt").writeText("partial clone")
                    GitOperationResult.Error("Connection interrupted")
                })
            }
            fields()[0].performTextReplacement("https://example.test/team/repo.git")
            rule.waitForIdle()
            fields()[1].performTextReplacement("custom-name")
            fields()[2].performTextReplacement(parent.path)
            rule.onNodeWithText("Clone").performClick()
            rule.onNodeWithText("Try Again").performClick()
            fields()[0].assertTextEquals("https://example.test/team/repo.git")
            fields()[1].assertTextEquals("custom-name")
            fields()[2].assertTextEquals(parent.path)
            rule.onNodeWithText("Clone").assertIsNotEnabled()
            assertTrue(partial.resolve("keep.txt").exists())
            fields()[0].performTextReplacement("invalid")
            fields()[1].performTextReplacement("corrected-name")
            rule.onNodeWithText("Clone").assertIsNotEnabled()
            fields()[0].performTextReplacement("https://example.test/team/corrected.git")
            rule.waitForIdle()
            fields()[1].assertTextEquals("corrected-name")
            rule.onNodeWithText("Clone").assertIsEnabled().performClick()
            rule.onNodeWithText("Try Again").performClick()
            rule.runOnIdle {
                assertEquals(
                    listOf(
                        "https://example.test/team/repo.git" to partial.path,
                        "https://example.test/team/corrected.git" to parent.resolve("corrected-name").path,
                    ),
                    calls,
                )
            }
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `automatic directory name still follows a corrected URL after retry`() {
        val parent = Files.createTempDirectory("clone-retry-auto").toFile()
        try {
            rule.setContent {
                CloneProjectDialog({}, {}, { _, _, _ -> GitOperationResult.Error("Unavailable") })
            }
            fields()[0].performTextReplacement("https://example.test/team/repo.git")
            fields()[2].performTextReplacement(parent.path)
            rule.onNodeWithText("Clone").performClick()
            rule.onNodeWithText("Try Again").performClick()
            fields()[1].assertTextEquals("repo")
            fields()[0].performTextReplacement("https://example.test/team/other.git")
            rule.waitForIdle()
            fields()[1].assertTextEquals("other")
            rule.onNodeWithText("Clone").assertIsEnabled()
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun fields() = rule.onAllNodes(hasSetTextAction())
}
