package ai.rever.boss.components.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class MissingDependencyConsentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `every additional plugin can be read without hiding the install action`() {
        val ids = (1..31).map { "ai.rever.boss.plugin.dependency$it" }
        var clicked = false
        rule.setContent {
            Box(Modifier.width(400.dp)) {
                MissingDependencyBody(
                    missing = MissingPluginDependency("parent", "Parent", "root", optional = false),
                    resolvedName = "Root",
                    alsoInstalls = ids,
                    installing = false,
                    error = null,
                    onDismiss = {},
                    onInstall = { clicked = true },
                )
            }
        }
        rule.onNodeWithText(ids.last()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun `a new prompt cannot inherit the previous prompts resolved plan`() {
        val answer = CompletableDeferred<DependencyInstallPlan>()
        val nameAnswer = CompletableDeferred<String>()
        val installer =
            object : MissingDependencyInstaller {
                override fun isInstalled(pluginId: String) = false

                override suspend fun displayNameFor(pluginId: String): String =
                    if (pluginId == "first") "First tool" else nameAnswer.await()

                override suspend fun install(pluginId: String) = Result.success(Unit)

                override suspend fun planFor(pluginId: String): DependencyInstallPlan =
                    if (pluginId == "first") {
                        DependencyInstallPlan(listOf("child", "first"), emptySet(), false, false)
                    } else {
                        answer.await()
                    }
            }
        var prompt by mutableStateOf(
            MissingDependencyPrompt(MissingPluginDependency("parent", "Parent", "first", false), installer),
        )
        rule.setContent {
            val plan by rememberInstallPlan(prompt)
            val name by rememberDependencyName(prompt)
            Text("$name: ${plan.order.joinToString(",")}")
        }
        rule.onNodeWithText("First tool: child,first").assertIsDisplayed()
        rule.runOnIdle {
            prompt = MissingDependencyPrompt(MissingPluginDependency("parent", "Parent", "second", false), installer)
        }
        rule.onNodeWithText("second: second").assertIsDisplayed()
        rule.runOnIdle { answer.complete(DependencyInstallPlan(listOf("other", "second"), emptySet(), false, false)) }
        rule.onNodeWithText("second: other,second").assertIsDisplayed()
        rule.runOnIdle { nameAnswer.complete("Second tool") }
        rule.onNodeWithText("Second tool: other,second").assertIsDisplayed()
    }
}
