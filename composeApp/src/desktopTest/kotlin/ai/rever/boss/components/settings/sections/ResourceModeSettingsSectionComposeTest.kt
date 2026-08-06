package ai.rever.boss.components.settings.sections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test that the resource-mode Settings section actually composes.
 *
 * Everything else about this feature is covered by pure unit tests, which cannot see a
 * composition-time failure - a bad `remember` key, a state read outside composition, a null
 * dereference in a `description` string. Those only appear when the UI is built, and the screen
 * they would break is the one a user goes to *because* their plugins are missing, i.e. exactly
 * when they can least afford a second failure.
 *
 * Deliberately asserts on section titles rather than on values: the values depend on the
 * machine's real memory and on which tier this test process resolved to, and pinning those
 * would make the test assert the developer's hardware.
 */
class ResourceModeSettingsSectionComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the section composes and shows the mode picker`() {
        compose.setContent { ResourceModeSettingsSection() }

        compose.onNodeWithText("Resource Mode").assertExists()
        compose.onNodeWithText("Mode").assertExists()
        compose.onNodeWithText("Running as").assertExists()
        compose.onNodeWithText("Detected memory").assertExists()
    }

    @Test
    fun `the automatic-selection controls compose`() {
        compose.setContent { ResourceModeSettingsSection() }

        compose.onNodeWithText("Automatic Selection").assertExists()
        compose.onNodeWithText("Use Lite below").assertExists()
        compose.onNodeWithText("Use Ultra Lite below").assertExists()
        compose.onNodeWithText("React to low memory while running").assertExists()
    }

    /**
     * The whole Performance screen has to survive mounting the new section at its head, since
     * that is where a user lands when looking for why BOSS feels different.
     */
    @Test
    fun `the performance settings screen still composes with the section mounted`() {
        compose.setContent { PerformanceSettings() }

        compose.onNodeWithText("Resource Mode").assertExists()
        compose.onNodeWithText("General").assertExists()
    }
}
