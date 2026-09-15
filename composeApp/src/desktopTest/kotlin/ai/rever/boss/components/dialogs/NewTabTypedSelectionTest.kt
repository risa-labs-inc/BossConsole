package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real dialog and plugin factory, not just the event's parameter shape. */
class NewTabTypedSelectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val registry = TabRegistry()
    private val opened = mutableListOf<TabInfo>()
    private val contexts = mutableListOf<NewTabContext>()
    private val previousModal = OverlayConfig.heavyweightModal
    private val previousHeavyweight = OverlayConfig.useHeavyweightPopups

    private inner class Tool(
        override val typeId: TabTypeId,
        override val displayName: String,
        order: Int,
    ) : TabTypeInfo {
        override val icon = Icons.Default.Search
        override val newTabSpec =
            NewTabSpec(
                order = order,
                inputLabel = "$displayName input",
                confirmLabel = "Open $displayName",
            )

        override fun createTabInfo(
            input: String,
            context: NewTabContext,
        ): TabInfo {
            contexts += context
            return object : TabInfo {
                override val id = "created-${this@Tool.typeId.pluginId}"
                override val typeId = this@Tool.typeId
                override val title = input
                override val icon = this@Tool.icon
            }
        }
    }

    private val first = Tool(TabTypeId("query", "plugin.first"), "First", 0)
    private val chosen = Tool(TabTypeId("query", "plugin.chosen"), "Chosen", 1)

    @Before
    fun setUp() {
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = true
        resetOverlayFieldForTest("modalRenderer")
        OverlayConfig.heavyweightModal = { _, _, content -> content() }
        register(first)
        register(chosen)
    }

    @After
    fun tearDown() {
        resetOverlayFieldForTest("modalRenderer")
        OverlayConfig.heavyweightModal = previousModal
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = previousHeavyweight
    }

    private fun register(type: TabTypeInfo) {
        registry.registerTabType(type) { _, _ -> error("The dialog must only build TabInfo") }
    }

    private fun open(
        request: TabTypeId? = chosen.typeId,
        builtin: TabType? = null,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                NewTabDialog(
                    onDismiss = {},
                    onCreateTab = { _, _ -> error("Must not substitute a built-in") },
                    tabRegistry = registry,
                    initialTabType = builtin,
                    initialPluginType = request,
                    onCreateTabInfo = { opened += it },
                    windowId = "source-window",
                    projectPath = "/scratch/project",
                )
            }
        }
    }

    @Test
    fun `nondefault request selects its own focused form even with colliding type strings`() {
        open()
        rule.onNodeWithText("Chosen input").assertExists()
        rule.onNode(hasSetTextAction()).assertIsFocused().performTextInput("  select records  ")
        rule.onNodeWithText("Open Chosen").performClick()
        rule.runOnIdle {
            assertEquals(chosen.typeId, opened.single().typeId)
            assertEquals("select records", opened.single().title)
            assertEquals("source-window", contexts.single().windowId)
            assertEquals("/scratch/project", contexts.single().projectPath)
        }
    }

    @Test
    fun `requested plugin wins over installed builtin default`() {
        register(FluckTabType)
        open()
        rule.onNodeWithText("Chosen input").assertExists()
        rule.onNodeWithText("Enter URL or search term").assertDoesNotExist()
    }

    @Test
    fun `missing request explains unavailability without another input form`() {
        open(TabTypeId("query", "plugin.removed"))
        rule.onNodeWithText("unavailable", substring = true).assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Create Tab").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(opened.isEmpty()) }
        rule
            .onNodeWithText(
                "First",
                substring = false,
            ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("First input").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("recovered")
        rule.onNodeWithText("Open First").performClick()
        rule.runOnIdle { assertEquals(first.typeId, opened.single().typeId) }
    }

    @Test
    fun `registry change must not replace a deliberate choice`() {
        open()
        rule
            .onNodeWithText(
                "First",
                substring = false,
            ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.onNode(hasSetTextAction()).performTextInput("keep this input")
        rule.runOnIdle { register(Tool(TabTypeId("new", "earlier"), "Earlier", -1)) }
        rule.onNodeWithText("First input").assertExists()
        rule.onNodeWithText("Open First").performClick()
        rule.runOnIdle { assertEquals("keep this input", opened.single().title) }
    }

    @Test
    fun `Open File keeps its dedicated initial form`() {
        register(CodeEditorTabType)
        open(request = null, builtin = TabType.FILE)
        // Without an opened project, the existing File form offers its project picker.
        rule.onNodeWithText("Open Project", substring = false).assertExists()
        rule.onNodeWithText("Open", substring = false).assertExists()
        rule.onNodeWithText("Chosen input").assertDoesNotExist()
    }
}
