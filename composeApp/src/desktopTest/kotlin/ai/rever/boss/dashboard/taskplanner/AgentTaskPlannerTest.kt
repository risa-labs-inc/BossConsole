package ai.rever.boss.components.dashboard.taskplanner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AgentTaskPlannerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `run activity displays completion count progress and runs`() {
        rule.setContent {
            AgentTaskPlanner(
                tasks =
                    listOf(
                        AgentTask(
                            title = "Build project",
                            status = AgentTaskStatus.COMPLETED,
                        ),
                        AgentTask(
                            title = "Run tests",
                            status = AgentTaskStatus.COMPLETED,
                        ),
                        AgentTask(
                            title = "Start application",
                            status = AgentTaskStatus.IN_PROGRESS,
                        ),
                        AgentTask(
                            title = "Deploy configuration",
                            status = AgentTaskStatus.TODO,
                        ),
                        AgentTask(
                            title = "Verify result",
                            status = AgentTaskStatus.TODO,
                        ),
                    ),
            )
        }

        rule.onNodeWithText("Run Activity").assertIsDisplayed()
        rule.onNodeWithText("2/5 completed").assertIsDisplayed()
        rule.onNodeWithText("Progress: 40%").assertIsDisplayed()

        rule.onNodeWithText("Build project").assertIsDisplayed()
        rule.onNodeWithText("Run tests").assertIsDisplayed()
        rule.onNodeWithText("Start application").assertIsDisplayed()
        rule.onNodeWithText("Deploy configuration").assertIsDisplayed()
        rule.onNodeWithText("Verify result").assertIsDisplayed()
    }

    @Test
    fun `empty run activity displays empty state`() {
        rule.setContent {
            AgentTaskPlanner(tasks = emptyList())
        }

        rule.onNodeWithText("Run Activity").assertIsDisplayed()
        rule.onNodeWithText("No active runs.").assertIsDisplayed()
        rule.onNodeWithText("Run a configuration to see its activity here.")
            .assertIsDisplayed()
    }
}