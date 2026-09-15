package ai.rever.boss.components.wizard.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FailedToolRetryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `failed-only batches preserve selection and successes through a global retry`() {
        val state = state()
        state.completeInstallation(listOf("a"), listOf("b" to "offline", "c" to "offline"))
        state.wizardState.goToStep(PluginInstallStep.allSteps.indexOf(PluginInstallStep.Complete))
        val requests = mutableListOf<List<String>>()
        rule.setContent {
            val step = state.wizardState.currentStep
            RunInstallation(step, state) { batch, _ ->
                requests += batch.map { it.id }
                when (requests.size) {
                    1 -> Result.failure(IllegalStateException("connection lost"))
                    2 -> Result.success(PluginInstallResult(listOf("b"), listOf("c" to "offline")))
                    else -> Result.success(PluginInstallResult(listOf("c"), emptyList()))
                }
            }
            if (step == PluginInstallStep.Complete) {
                CompleteStepContent(
                    state.installedPluginIds.size,
                    state.failedPlugins,
                    onRetryFailed = state::retryFailedPlugins,
                )
            }
        }
        rule.onNodeWithText("Retry failed tools").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(listOf("b", "c")), requests)
            assertEquals(listOf("a"), state.installedPluginIds)
            assertEquals("connection lost", state.installationError)
            state.prepareInstallationRetry()
        }
        rule.onNodeWithText("Retry failed tools").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf(listOf("b", "c"), listOf("b", "c"), listOf("c")), requests)
            assertEquals(listOf("a", "b", "c"), state.installedPluginIds)
            assertEquals(listOf("a", "b", "c"), state.getSelectedPlugins().map { it.id })
            assertEquals(emptyList(), state.failedPlugins)
        }
        rule.onNodeWithText("Retry failed tools").assertDoesNotExist()
    }

    @Test
    fun `terminal setup completion keeps failed retry reachable in a short window`() {
        var retries = 0
        rule.setContent {
            Box(Modifier.size(636.dp, 300.dp)) {
                CompleteStepContent(
                    installedCount = 1,
                    failedPlugins = (1..20).map { "plugin$it" to "offline" },
                    bossTermReady = true,
                    onSetupBossTerm = {},
                    onFinish = {},
                    onRetryFailed = { retries++ },
                )
            }
        }
        rule
            .onNodeWithText("Retry failed tools")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `retry is ignored outside completion and reset removes the batch`() {
        val state = state()
        state.completeInstallation(listOf("a"), listOf("b" to "offline"))
        state.retryFailedPlugins()
        assertEquals(0, state.installationRunId)
        state.wizardState.goToStep(PluginInstallStep.allSteps.indexOf(PluginInstallStep.Complete))
        state.retryFailedPlugins()
        state.retryFailedPlugins()
        assertEquals(1, state.installationRunId)
        assertEquals(listOf("b"), state.getInstallationPlugins().map { it.id })
        state.reset()
        assertEquals(listOf("a", "b", "c"), state.getInstallationPlugins().map { it.id })
        assertEquals(emptyList(), state.installedPluginIds)
        assertFalse(state.installationAttempted)
    }

    private fun state() =
        PluginInstallWizardState(
            listOf("a", "b", "c").map { id ->
                WizardPluginInfo(
                    id = id,
                    name = id,
                    description = id,
                    version = "1",
                    category = PluginCategory.DEVELOPER,
                )
            },
        ).apply { applyProfile(ToolboxProfile.EVERYTHING) }
}
