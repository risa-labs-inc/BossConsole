package ai.rever.boss.keymap

import ai.rever.boss.keymap.lifecycle.AlwaysEnabledCondition
import ai.rever.boss.keymap.lifecycle.NeverEnabledCondition
import ai.rever.boss.keymap.lifecycle.conditions.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Unit tests for CommonConditions fluent API and pre-built conditions.
 */
class CommonConditionsTest {

    // ============================================================================
    // ConditionBuilder Tests
    // ============================================================================

    @Test
    fun `ConditionBuilder creates enabled condition when check returns true`() = runBlocking {
        val condition = ConditionBuilder()
            .whenTrueSync { true }
            .withReason("Test reason")
            .describedAs("Test description")
            .build()

        assertTrue(condition.isEnabled())
        assertEquals("Test reason", condition.disabledReason)
        assertEquals("Test description", condition.enabledDescription)
    }

    @Test
    fun `ConditionBuilder creates disabled condition when check returns false`() = runBlocking {
        val condition = ConditionBuilder()
            .whenTrueSync { false }
            .withReason("Custom disabled reason")
            .build()

        assertFalse(condition.isEnabled())
        assertEquals("Custom disabled reason", condition.disabledReason)
    }

    @Test
    fun `ConditionBuilder throws when no check provided`() {
        val builder = ConditionBuilder()
            .withReason("Test")

        assertFailsWith<IllegalStateException> {
            builder.build()
        }
    }

    @Test
    fun `ConditionBuilder with async check works`() = runBlocking {
        var called = false
        val condition = ConditionBuilder()
            .whenTrue {
                called = true
                true
            }
            .build()

        assertTrue(condition.isEnabled())
        assertTrue(called)
    }

    // ============================================================================
    // DSL Function Tests
    // ============================================================================

    @Test
    fun `condition DSL creates working condition`() = runBlocking {
        val flag = true
        val cond = condition {
            whenTrueSync { flag }
            withReason("Flag is false")
            describedAs("When flag is true")
        }

        assertTrue(cond.isEnabled())
    }

    @Test
    fun `enabledWhen creates working condition`() = runBlocking {
        val cond = enabledWhen(
            reason = "Not ready",
            description = "When ready"
        ) { true }

        assertTrue(cond.isEnabled())
        assertEquals("Not ready", cond.disabledReason)
        assertEquals("When ready", cond.enabledDescription)
    }

    @Test
    fun `enabledWhenSync creates working condition`() = runBlocking {
        var counter = 0
        val cond = enabledWhenSync(
            reason = "Counter is zero",
            description = "When counter is positive"
        ) {
            counter++
            counter > 0
        }

        assertTrue(cond.isEnabled())
        assertEquals(1, counter)
    }

    // ============================================================================
    // Combinator Tests
    // ============================================================================

    @Test
    fun `and combinator requires both conditions`() = runBlocking {
        val trueCondition = enabledWhenSync { true }
        val falseCondition = enabledWhenSync { false }

        assertTrue((trueCondition and trueCondition).isEnabled())
        assertFalse((trueCondition and falseCondition).isEnabled())
        assertFalse((falseCondition and trueCondition).isEnabled())
        assertFalse((falseCondition and falseCondition).isEnabled())
    }

    @Test
    fun `or combinator requires at least one condition`() = runBlocking {
        val trueCondition = enabledWhenSync { true }
        val falseCondition = enabledWhenSync { false }

        assertTrue((trueCondition or trueCondition).isEnabled())
        assertTrue((trueCondition or falseCondition).isEnabled())
        assertTrue((falseCondition or trueCondition).isEnabled())
        assertFalse((falseCondition or falseCondition).isEnabled())
    }

    @Test
    fun `not combinator inverts condition`() = runBlocking {
        val trueCondition = enabledWhenSync { true }
        val falseCondition = enabledWhenSync { false }

        assertFalse(trueCondition.not().isEnabled())
        assertTrue(falseCondition.not().isEnabled())
    }

    @Test
    fun `not combinator uses custom reason and description`() = runBlocking {
        val cond = enabledWhenSync { true }.not(
            invertedReason = "Custom inverted reason",
            invertedDescription = "Custom inverted description"
        )

        assertEquals("Custom inverted reason", cond.disabledReason)
        assertEquals("Custom inverted description", cond.enabledDescription)
    }

    @Test
    fun `complex combinator chain works`() = runBlocking {
        val a = enabledWhenSync { true }
        val b = enabledWhenSync { false }
        val c = enabledWhenSync { true }

        // (true AND false) OR true = true
        val combined = (a and b) or c
        assertTrue(combined.isEnabled())

        // (true OR false) AND false = false
        val combined2 = (a or b) and b
        assertFalse(combined2.isEnabled())
    }

    // ============================================================================
    // Conditions Factory Tests
    // ============================================================================

    @Test
    fun `Conditions hasTabs works correctly`() = runBlocking {
        var tabCount = 0
        val cond = Conditions.hasTabs { tabCount }

        assertFalse(cond.isEnabled())

        tabCount = 1
        assertTrue(cond.isEnabled())

        tabCount = 5
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions hasMinTabs works correctly`() = runBlocking {
        var tabCount = 0
        val cond = Conditions.hasMinTabs(3) { tabCount }

        assertFalse(cond.isEnabled())

        tabCount = 2
        assertFalse(cond.isEnabled())

        tabCount = 3
        assertTrue(cond.isEnabled())

        tabCount = 5
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions hasMultipleTabs works correctly`() = runBlocking {
        var tabCount = 0
        val cond = Conditions.hasMultipleTabs { tabCount }

        assertFalse(cond.isEnabled())

        tabCount = 1
        assertFalse(cond.isEnabled())

        tabCount = 2
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions hasActiveTab works correctly`() = runBlocking {
        var hasActive = false
        val cond = Conditions.hasActiveTab { hasActive }

        assertFalse(cond.isEnabled())

        hasActive = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions canNavigateSplits works correctly`() = runBlocking {
        var splitCount = 1
        val cond = Conditions.canNavigateSplits { splitCount }

        assertFalse(cond.isEnabled())

        splitCount = 2
        assertTrue(cond.isEnabled())

        splitCount = 4
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions canCreateSplit works correctly`() = runBlocking {
        var splitCount = 1
        val cond = Conditions.canCreateSplit({ splitCount }, maxSplits = 4)

        assertTrue(cond.isEnabled())

        splitCount = 3
        assertTrue(cond.isEnabled())

        splitCount = 4
        assertFalse(cond.isEnabled())

        splitCount = 5
        assertFalse(cond.isEnabled())
    }

    @Test
    fun `Conditions hasClipboardContent works correctly`() = runBlocking {
        var hasContent = false
        val cond = Conditions.hasClipboardContent { hasContent }

        assertFalse(cond.isEnabled())
        assertEquals("Clipboard is empty", cond.disabledReason)

        hasContent = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions hasSelection works correctly`() = runBlocking {
        var hasSelection = false
        val cond = Conditions.hasSelection { hasSelection }

        assertFalse(cond.isEnabled())
        assertEquals("No selection", cond.disabledReason)

        hasSelection = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions isEditable works correctly`() = runBlocking {
        var editable = false
        val cond = Conditions.isEditable { editable }

        assertFalse(cond.isEnabled())
        assertEquals("Content is read-only", cond.disabledReason)

        editable = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions canUndo and canRedo work correctly`() = runBlocking {
        var canUndoState = false
        var canRedoState = false

        val undoCond = Conditions.canUndo { canUndoState }
        val redoCond = Conditions.canRedo { canRedoState }

        assertFalse(undoCond.isEnabled())
        assertFalse(redoCond.isEnabled())

        canUndoState = true
        canRedoState = true

        assertTrue(undoCond.isEnabled())
        assertTrue(redoCond.isEnabled())
    }

    @Test
    fun `Conditions always and never work correctly`() = runBlocking {
        val always = Conditions.always()
        val never = Conditions.never("Custom reason")

        assertTrue(always.isEnabled())
        assertFalse(never.isEnabled())
        assertEquals("Custom reason", never.disabledReason)
    }

    @Test
    fun `Conditions debugOnly works correctly`() = runBlocking {
        var isDebug = false
        val cond = Conditions.debugOnly { isDebug }

        assertFalse(cond.isEnabled())
        assertEquals("Only available in debug mode", cond.disabledReason)

        isDebug = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions featureFlag works correctly`() = runBlocking {
        var flagEnabled = false
        val cond = Conditions.featureFlag("dark_mode") { flagEnabled }

        assertFalse(cond.isEnabled())
        assertEquals("Feature 'dark_mode' is disabled", cond.disabledReason)
        assertEquals("When 'dark_mode' feature is enabled", cond.enabledDescription)

        flagEnabled = true
        assertTrue(cond.isEnabled())
    }

    @Test
    fun `Conditions context checks work correctly`() = runBlocking {
        var browserActive = false
        var editorActive = false
        var terminalActive = false

        val browserCond = Conditions.isBrowserActive { browserActive }
        val editorCond = Conditions.isEditorActive { editorActive }
        val terminalCond = Conditions.isTerminalActive { terminalActive }

        assertFalse(browserCond.isEnabled())
        assertFalse(editorCond.isEnabled())
        assertFalse(terminalCond.isEnabled())

        browserActive = true
        editorActive = true
        terminalActive = true

        assertTrue(browserCond.isEnabled())
        assertTrue(editorCond.isEnabled())
        assertTrue(terminalCond.isEnabled())
    }

    @Test
    fun `Conditions navigation checks work correctly`() = runBlocking {
        var canGoBackState = false
        var canGoForwardState = false

        val backCond = Conditions.canGoBack { canGoBackState }
        val forwardCond = Conditions.canGoForward { canGoForwardState }

        assertFalse(backCond.isEnabled())
        assertFalse(forwardCond.isEnabled())

        canGoBackState = true
        canGoForwardState = true

        assertTrue(backCond.isEnabled())
        assertTrue(forwardCond.isEnabled())
    }

    // ============================================================================
    // Integration Tests
    // ============================================================================

    @Test
    fun `Real-world scenario - paste enabled when clipboard has content and editor is active`() = runBlocking {
        var hasClipboard = false
        var isEditorActive = false

        val pasteCondition = Conditions.hasClipboardContent { hasClipboard } and
            Conditions.isEditorActive { isEditorActive }

        // Neither condition met
        assertFalse(pasteCondition.isEnabled())

        // Only clipboard has content
        hasClipboard = true
        assertFalse(pasteCondition.isEnabled())

        // Only editor active
        hasClipboard = false
        isEditorActive = true
        assertFalse(pasteCondition.isEnabled())

        // Both conditions met
        hasClipboard = true
        assertTrue(pasteCondition.isEnabled())
    }

    @Test
    fun `Real-world scenario - close tab enabled with tabs OR can create split`() = runBlocking {
        var tabCount = 0
        var splitCount = 4

        // Close enabled if tabs exist OR we can create a split (weird but tests OR)
        val condition = Conditions.hasTabs { tabCount } or
            Conditions.canCreateSplit({ splitCount }, maxSplits = 4)

        // Neither condition met
        assertFalse(condition.isEnabled())

        // Has tabs
        tabCount = 1
        assertTrue(condition.isEnabled())

        // No tabs but can create split
        tabCount = 0
        splitCount = 2
        assertTrue(condition.isEnabled())

        // Both conditions met
        tabCount = 1
        assertTrue(condition.isEnabled())
    }
}
