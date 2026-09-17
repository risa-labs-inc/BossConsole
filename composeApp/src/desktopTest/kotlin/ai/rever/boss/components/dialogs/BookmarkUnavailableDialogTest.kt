package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BookmarkUnavailableDialogTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    @Test fun `unsupported tab cannot be saved even with a collection selected`() {
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                BookmarkDialog(
                    tabTitle = "Unsupported tab",
                    collections = listOf(BookmarkCollection(id = "favorites", name = "Favorites", isFavorite = true)),
                    workspaces = emptyList(),
                    onDismiss = {},
                    onConfirm = { _, _ -> error("Unsupported tab must not be saved") },
                    unavailableReason = "Bookmarking is not supported for this tab",
                )
            }
        }
        rule.onNodeWithText("Bookmarking is not supported for this tab").assertIsDisplayed()
        rule.onNodeWithText("Add Bookmark").assertIsNotEnabled()
    }
}
