package ai.rever.boss.components.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlin.test.assertTrue

class PluginRecoveryNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `short recovery card keeps contextual actions and close reachable`() {
        var dismissed = false
        var navigated = false
        rule.setContent {
            Box(Modifier.size(320.dp, 240.dp)) {
                PluginHealthCenterCard(
                    rows = emptyList(),
                    target = PluginRecoveryTarget("missing-tool", "The selected tool needs recovery. ".repeat(8)),
                    onOpenToolbox = {
                        navigated = true
                        false
                    },
                    actionError = null,
                    workingPluginId = null,
                    onDismiss = { dismissed = true },
                    onAction = { _, _ -> },
                )
            }
        }
        rule
            .onNodeWithText("Open Toolbox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(navigated)
        rule
            .onNodeWithText("Close")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `missing target stays truthful and failed toolbox navigation remains actionable`() {
        var dismissed = false
        rule.setContent {
            Column {
                PluginRecoveryNavigation(
                    PluginRecoveryTarget("missing-tool"),
                    false,
                    false,
                    { false },
                    { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("missing-tool").assertIsDisplayed()
        rule
            .onNodeWithText("This plugin is no longer listed in this window. Check the Toolbox for its current status.")
            .assertIsDisplayed()
        rule.onNodeWithText("Open Toolbox").performClick()
        rule.onNodeWithText("The Toolbox is not available in this window yet.").assertIsDisplayed()
        assertFalse(dismissed)
    }

    @Test
    fun `successful navigation dismisses recovery and preserves the contextual reason`() {
        var dismissed = false
        rule.setContent {
            Column {
                PluginRecoveryNavigation(
                    PluginRecoveryTarget("notes", "The selected tool needs recovery."),
                    true,
                    false,
                    { true },
                    { dismissed = true },
                )
            }
        }
        rule.onNodeWithText("The selected tool needs recovery.").assertIsDisplayed()
        rule.onNodeWithText("Open Toolbox").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `access and startup notices never invite lifecycle recovery`() {
        assertFalse(pluginSectionOffersRecovery(PluginSectionAbsence.NO_ACCESS))
        assertFalse(pluginSectionOffersRecovery(PluginSectionAbsence.STARTING))
        assertFalse(pluginSectionOffersRecovery(PluginSectionAbsence.NOT_INSTALLED))
        assertTrue(pluginSectionOffersRecovery(PluginSectionAbsence.DISABLED))
        assertTrue(pluginSectionOffersRecovery(PluginSectionAbsence.FAILED))
        assertTrue(pluginSectionOffersRecovery(PluginSectionAbsence.INCOMPATIBLE))
        assertTrue(pluginSectionOffersRecovery(PluginSectionAbsence.NO_PANEL))
    }

    @Test
    fun `targeting never substitutes a different plugin or alters safe actions`() {
        val row =
            PluginHealthRow(
                "notes",
                "Notes",
                PluginHealthStatus.UNAVAILABLE,
                "Disabled",
                PluginHealthAction.ENABLE,
            )
        assertEquals(listOf(row), healthRowsForTarget(listOf(row), PluginRecoveryTarget("notes")))
        assertEquals(emptyList(), healthRowsForTarget(listOf(row), PluginRecoveryTarget("other")))
        assertEquals(listOf(row), healthRowsForTarget(listOf(row), null))
    }
}
