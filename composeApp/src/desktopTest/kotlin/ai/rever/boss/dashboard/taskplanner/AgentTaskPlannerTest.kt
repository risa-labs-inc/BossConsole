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
    fun `task planner displays title completion count progress and tasks`() {
        rule.setContent {
            AgentTaskPlanner(
                tasks =
                    listOf(
                        AgentTask(
                            title = "Understand requirements",
                            status = AgentTaskStatus.COMPLETED,
                        ),
                        AgentTask(
                            title = "Create project structure",
                            status = AgentTaskStatus.COMPLETED,
                        ),
                        AgentTask(
                            title = "Implement feature",
                            status = AgentTaskStatus.IN_PROGRESS,
                        ),
                        AgentTask(
                            title = "Run tests",
                            status = AgentTaskStatus.TODO,
                        ),
                        AgentTask(
                            title = "Review results",
                            status = AgentTaskStatus.TODO,
                        ),
                    ),
            )
        }

        rule.onNodeWithText("AI Task Plan").assertIsDisplayed()
        rule.onNodeWithText("2/5 completed").assertIsDisplayed()
        rule.onNodeWithText("Progress: 40%").assertIsDisplayed()

        rule.onNodeWithText("Understand requirements").assertIsDisplayed()
        rule.onNodeWithText("Create project structure").assertIsDisplayed()
        rule.onNodeWithText("Implement feature").assertIsDisplayed()
        rule.onNodeWithText("Run tests").assertIsDisplayed()
        rule.onNodeWithText("Review results").assertIsDisplayed()
    }

    @Test
    fun `empty task planner displays zero progress`() {
        rule.setContent {
            AgentTaskPlanner(tasks = emptyList())
        }

        rule.onNodeWithText("AI Task Plan").assertIsDisplayed()
        rule.onNodeWithText("0/0 completed").assertIsDisplayed()
        rule.onNodeWithText("Progress: 0%").assertIsDisplayed()
    }
}
