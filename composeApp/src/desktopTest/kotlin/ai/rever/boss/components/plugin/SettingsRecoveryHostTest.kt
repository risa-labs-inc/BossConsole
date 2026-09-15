package ai.rever.boss.components.plugin

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SettingsRecoveryHostTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `provider arrival does not dispose the recovery dialog owned by settings`() {
        val unavailable = mutableStateOf(true)
        rule.setContent {
            SettingsRecoveryHost {
                if (unavailable.value) {
                    val request = LocalSettingsRecoveryRequest.current
                    Button(onClick = { request?.invoke(PluginRecoveryTarget("notes")) }) {
                        Text("Recover selected tool")
                    }
                } else {
                    Text("Provider settings ready")
                }
            }
        }
        rule.onNodeWithText("Recover selected tool").performClick()
        rule.onNodeWithText("Plugin management is not available in this window yet.").assertIsDisplayed()
        // The section removes its unavailable notice as soon as the provider registers.
        rule.runOnIdle { unavailable.value = false }
        rule.onNodeWithText("Plugin management is not available in this window yet.").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Provider settings ready").assertIsDisplayed()
    }
}
