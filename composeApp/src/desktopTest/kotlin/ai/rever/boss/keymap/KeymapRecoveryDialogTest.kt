package ai.rever.boss.keymap

import ai.rever.boss.utils.revealInFileManagerLabel
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeymapRecoveryDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    @After
    fun clearNotice() {
        KeymapSettingsManager.ensureLoaded()
        val owner = KeymapRecoveryNotices.pending.value?.owner ?: Any()
        KeymapRecoveryNotices.claim(owner)
        KeymapRecoveryNotices.acknowledge(owner)
    }

    @Test
    fun `closing the owning window hands the notice to an already open window`() {
        val showFirst = mutableStateOf(true)
        val showSecond = mutableStateOf(false)
        KeymapRecoveryNotices.publish("saved.json")
        rule.setContent {
            if (showFirst.value) key("first") { KeymapRecoveryDialog() }
            if (showSecond.value) key("second") { KeymapRecoveryDialog() }
        }
        rule.onNodeWithText("Keyboard shortcuts reset").assertIsDisplayed()
        val firstClaim = rule.runOnIdle { assertNotNull(KeymapRecoveryNotices.pending.value) }

        rule.runOnIdle { showSecond.value = true }
        rule.onAllNodesWithText("Keyboard shortcuts reset").assertCountEquals(1)
        rule.runOnIdle { showFirst.value = false }

        rule.onNodeWithText("Keyboard shortcuts reset").assertIsDisplayed()
        rule.runOnIdle {
            val secondClaim = assertNotNull(KeymapRecoveryNotices.pending.value)
            assertSame(firstClaim.notice, secondClaim.notice)
            assertNotNull(secondClaim.owner)
            assertNotSame(firstClaim.owner, secondClaim.owner)
        }
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Keyboard shortcuts reset").assertDoesNotExist()
        rule.runOnIdle { assertNull(KeymapRecoveryNotices.pending.value) }
    }

    @Test
    fun `a recovery published after composition still appears`() {
        val preservedFile = File("keymap-settings.json.corrupt-1700000000000-1").absolutePath
        rule.setContent { KeymapRecoveryDialog() }
        rule.onNodeWithText("Keyboard shortcuts reset").assertDoesNotExist()

        rule.runOnIdle { KeymapRecoveryNotices.publish(preservedFile) }

        rule.onNodeWithText("Keyboard shortcuts reset").assertIsDisplayed()
        rule.onNodeWithText(preservedFile, substring = true).assertIsDisplayed()
        rule.onNodeWithText(revealInFileManagerLabel()).assertIsDisplayed()
    }

    @Test
    fun `failed preservation explains the failure and offers only close`() {
        KeymapRecoveryNotices.publish(null)
        rule.setContent { KeymapRecoveryDialog() }

        rule.onNodeWithText("A copy of the invalid file could not be saved.", substring = true).assertIsDisplayed()
        rule.onNodeWithText(revealInFileManagerLabel()).assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()

        rule.onNodeWithText("Keyboard shortcuts reset").assertDoesNotExist()
        rule.runOnIdle { assertNull(KeymapRecoveryNotices.pending.value) }
    }
}
