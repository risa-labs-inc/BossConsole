package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.wizard.CheckboxCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PluginInstallWizardContentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `all failed installs are explained without claiming no selection`() {
        rule.setContent {
            Box(Modifier.size(636.dp, 400.dp).clipToBounds()) {
                CompleteStepContent(0, listOf("terminal" to "Download failed"))
            }
        }
        rule.onNodeWithText("No tools were installed successfully").assertIsDisplayed()
        rule.onNodeWithText("Installation incomplete").assertIsDisplayed()
        rule.onNodeWithText("No tools were selected for installation").assertDoesNotExist()
        rule.onNodeWithText("You can retry installing these tools from the Toolbox").assertIsDisplayed()
    }

    @Test
    fun `long failure list scrolls to the last error and keeps recovery guidance visible`() {
        val failures = (1..30).map { "plugin$it" to "Download failed" }
        rule.setContent {
            Box(Modifier.size(636.dp, 400.dp).clipToBounds()) {
                CompleteStepContent(2, failures)
            }
        }
        rule.onNodeWithText("• plugin30: Download failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("2 tools installed successfully").assertIsDisplayed()
        rule.onNodeWithText("You can retry installing these tools from the Toolbox").assertIsDisplayed()
    }

    @Test
    fun `empty selection has a distinct completion explanation`() {
        rule.setContent { CompleteStepContent(0) }
        rule.onNodeWithText("No tools were selected for installation").assertIsDisplayed()
        rule.onNodeWithText("No tools were installed successfully").assertDoesNotExist()
    }

    @Test
    fun `required label remains readable beside a long plugin name`() {
        val plugin =
            WizardPluginInfo(
                id = "required",
                name = "A very long plugin name ".repeat(8),
                description = "Required tool",
                version = "1.0.0",
                isMandatory = true,
            )
        rule.setContent {
            Box(Modifier.size(400.dp, 400.dp).clipToBounds()) {
                CategoryStepContent(
                    category = PluginCategory.OTHER,
                    plugins = listOf(plugin),
                    isPluginSelected = { true },
                    onTogglePlugin = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }
        rule.onNodeWithText("Required", useUnmergedTree = true).assertIsDisplayed()
        rule
            .onNodeWithText("Required")
            .assertIsEnabled()
            .assertIsOn()
            .assertHasNoClickAction()
    }

    @Test
    fun `optional card still toggles from its label`() {
        var toggles = 0
        val plugin = WizardPluginInfo("optional", "Optional tool", "Useful tool", "1.0.0")
        rule.setContent {
            CategoryStepContent(
                category = PluginCategory.OTHER,
                plugins = listOf(plugin),
                isPluginSelected = { false },
                onTogglePlugin = { toggles++ },
                onSelectAll = {},
                onDeselectAll = {},
            )
        }
        rule.onNodeWithText("Optional").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun `locked disabled cards report disabled semantics without a click action`() {
        rule.setContent {
            CheckboxCard(
                title = "Unavailable tool",
                description = "",
                isChecked = true,
                onCheckedChange = { error("Locked card must not toggle") },
                enabled = false,
                locked = true,
            )
        }
        rule
            .onNodeWithText("Unavailable tool")
            .assertIsNotEnabled()
            .assertIsOn()
            .assertHasNoClickAction()
    }

    @Test
    fun `large text completion scrolls to recovery guidance and back to its heading`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                Box(Modifier.size(636.dp, 400.dp).clipToBounds()) {
                    CompleteStepContent(0, (1..30).map { "plugin$it" to "Download failed" })
                }
            }
        }
        rule
            .onNodeWithText("You can retry installing these tools from the Toolbox")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("Installation incomplete").performScrollTo().assertIsDisplayed()
    }
}
