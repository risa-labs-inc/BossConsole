package ai.rever.boss.components.plugin

import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PluginHealthOperationStateTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `busy and failure feedback survive lifecycle recomposition`() {
        val revision = mutableStateOf(0)
        lateinit var current: PluginHealthOperationState
        rule.setContent {
            current = rememberHealthOperation()
            Text("Revision ${revision.value}, busy ${current.workingPluginId}, error ${current.actionError}")
        }
        rule.waitForIdle()
        val original = current
        rule.runOnIdle {
            current.workingPluginId = "notes"
            revision.value++
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(original, current)
            assertEquals("notes", current.workingPluginId)
            current.workingPluginId = null
            current.actionError = "Could not enable this plugin."
            revision.value++
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertSame(original, current)
            assertEquals("Could not enable this plugin.", current.actionError)
        }
    }
}
