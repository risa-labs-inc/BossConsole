package ai.rever.boss.keymap.lifecycle.conditions

import ai.rever.boss.keymap.lifecycle.AlwaysEnabledCondition
import ai.rever.boss.keymap.lifecycle.AndCondition
import ai.rever.boss.keymap.lifecycle.NeverEnabledCondition
import ai.rever.boss.keymap.lifecycle.OrCondition
import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleCondition

/**
 * Common lifecycle conditions and fluent builder API for keyboard shortcuts.
 *
 * Usage examples:
 * ```kotlin
 * // Using fluent builder
 * val condition = ConditionBuilder()
 *     .whenTrue { tabCount > 0 }
 *     .withReason("No tabs open")
 *     .describedAs("When tabs are open")
 *     .build()
 *
 * // Using factory functions
 * val hasContent = Conditions.hasClipboardContent { clipboard.hasText() }
 * val hasSelection = Conditions.hasSelection { editor.hasSelection() }
 *
 * // Using DSL
 * val combined = condition {
 *     whenTrue { tabs.isNotEmpty() }
 *     withReason("No tabs open")
 * } and condition {
 *     whenTrue { isEditable }
 *     withReason("Content is read-only")
 * }
 * ```
 */

// ============================================================================
// Fluent Builder API
// ============================================================================

/**
 * Fluent builder for creating custom lifecycle conditions.
 * Provides a readable way to define conditions with reasons and descriptions.
 */
class ConditionBuilder {
    private var enabledCheck: (suspend () -> Boolean)? = null
    private var reason: String = "Condition not met"
    private var description: String = "Custom condition"

    /**
     * Sets the condition check function.
     * The condition is enabled when this function returns true.
     */
    fun whenTrue(check: suspend () -> Boolean): ConditionBuilder {
        enabledCheck = check
        return this
    }

    /**
     * Sets the condition check function using a synchronous lambda.
     * The condition is enabled when this function returns true.
     */
    fun whenTrueSync(check: () -> Boolean): ConditionBuilder {
        enabledCheck = { check() }
        return this
    }

    /**
     * Sets the reason displayed when the condition is disabled.
     */
    fun withReason(reason: String): ConditionBuilder {
        this.reason = reason
        return this
    }

    /**
     * Sets the description for when the condition is enabled.
     */
    fun describedAs(description: String): ConditionBuilder {
        this.description = description
        return this
    }

    /**
     * Builds the condition.
     * @throws IllegalStateException if no check function was provided
     */
    fun build(): ShortcutLifecycleCondition {
        val check =
            enabledCheck
                ?: throw IllegalStateException("Condition check not set. Call whenTrue() or whenTrueSync() first.")

        return LambdaCondition(
            check = check,
            disabledReasonText = reason,
            enabledDescriptionText = description,
        )
    }
}

/**
 * Internal condition class that wraps a lambda function.
 */
internal class LambdaCondition(
    private val check: suspend () -> Boolean,
    private val disabledReasonText: String,
    private val enabledDescriptionText: String,
) : ShortcutLifecycleCondition {
    override suspend fun isEnabled(): Boolean = check()

    override val disabledReason: String get() = disabledReasonText
    override val enabledDescription: String get() = enabledDescriptionText
}

// ============================================================================
// DSL Functions
// ============================================================================

/**
 * DSL function for building conditions.
 * ```kotlin
 * val cond = condition {
 *     whenTrue { tabs.isNotEmpty() }
 *     withReason("No tabs open")
 * }
 * ```
 */
fun condition(block: ConditionBuilder.() -> Unit): ShortcutLifecycleCondition = ConditionBuilder().apply(block).build()

/**
 * Creates a condition that is enabled when the lambda returns true.
 * Shorthand for building a simple condition.
 */
fun enabledWhen(
    reason: String = "Condition not met",
    description: String = "Custom condition",
    check: suspend () -> Boolean,
): ShortcutLifecycleCondition =
    LambdaCondition(
        check = check,
        disabledReasonText = reason,
        enabledDescriptionText = description,
    )

/**
 * Creates a condition that is enabled when the synchronous lambda returns true.
 */
fun enabledWhenSync(
    reason: String = "Condition not met",
    description: String = "Custom condition",
    check: () -> Boolean,
): ShortcutLifecycleCondition =
    LambdaCondition(
        check = { check() },
        disabledReasonText = reason,
        enabledDescriptionText = description,
    )

// ============================================================================
// Condition Combinators (Extension Functions)
// ============================================================================

/**
 * Combines two conditions with AND logic.
 * Both conditions must be enabled for the result to be enabled.
 */
infix fun ShortcutLifecycleCondition.and(other: ShortcutLifecycleCondition): ShortcutLifecycleCondition = AndCondition(listOf(this, other))

/**
 * Combines two conditions with OR logic.
 * At least one condition must be enabled for the result to be enabled.
 */
infix fun ShortcutLifecycleCondition.or(other: ShortcutLifecycleCondition): ShortcutLifecycleCondition = OrCondition(listOf(this, other))

/**
 * Inverts a condition.
 * The result is enabled when the original is disabled, and vice versa.
 */
fun ShortcutLifecycleCondition.not(
    invertedReason: String = "Condition is met (should not be)",
    invertedDescription: String = "When condition is not met",
): ShortcutLifecycleCondition {
    val original = this
    return object : ShortcutLifecycleCondition {
        override suspend fun isEnabled(): Boolean = !original.isEnabled()

        override val disabledReason: String get() = invertedReason
        override val enabledDescription: String get() = invertedDescription
    }
}

// ============================================================================
// Pre-built Common Conditions (Factory Object)
// ============================================================================

/**
 * Factory object with pre-built conditions for common scenarios.
 */
object Conditions {
    // ---- Tab-related conditions ----

    /**
     * Condition that is enabled when there are tabs open.
     */
    fun hasTabs(getTabCount: () -> Int): ShortcutLifecycleCondition = TabCountCondition(getTabCount, minTabCount = 1)

    /**
     * Condition that is enabled when there are at least N tabs.
     */
    fun hasMinTabs(
        minCount: Int,
        getTabCount: () -> Int,
    ): ShortcutLifecycleCondition = TabCountCondition(getTabCount, minTabCount = minCount)

    /**
     * Condition that is enabled when there are multiple tabs for navigation.
     */
    fun hasMultipleTabs(getTabCount: () -> Int): ShortcutLifecycleCondition = TabNavigationCondition(getTabCount)

    /**
     * Condition that is enabled when there is an active tab.
     */
    fun hasActiveTab(check: () -> Boolean): ShortcutLifecycleCondition = ActiveTabCondition(check)

    // ---- Panel/Split-related conditions ----

    /**
     * Condition that is enabled when there are active panels.
     */
    fun hasPanels(check: () -> Boolean): ShortcutLifecycleCondition = PanelCondition(check)

    /**
     * Condition that is enabled when split navigation is possible (2+ panels).
     */
    fun canNavigateSplits(getSplitCount: () -> Int): ShortcutLifecycleCondition = SplitNavigationCondition(getSplitCount)

    /**
     * Condition that is enabled when more splits can be created.
     */
    fun canCreateSplit(
        getSplitCount: () -> Int,
        maxSplits: Int = 4,
    ): ShortcutLifecycleCondition = CanCreateSplitCondition(getSplitCount, maxSplits)

    // ---- Clipboard-related conditions ----

    /**
     * Condition that is enabled when the clipboard has content.
     */
    fun hasClipboardContent(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Clipboard is empty",
            description = "When clipboard has content",
            check = check,
        )

    /**
     * Condition that is enabled when the clipboard has text.
     */
    fun hasClipboardText(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No text in clipboard",
            description = "When clipboard has text",
            check = check,
        )

    // ---- Selection-related conditions ----

    /**
     * Condition that is enabled when there is a selection.
     */
    fun hasSelection(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No selection",
            description = "When content is selected",
            check = check,
        )

    /**
     * Condition that is enabled when there is a text selection.
     */
    fun hasTextSelection(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No text selected",
            description = "When text is selected",
            check = check,
        )

    // ---- Edit-related conditions ----

    /**
     * Condition that is enabled when content is editable.
     */
    fun isEditable(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Content is read-only",
            description = "When content is editable",
            check = check,
        )

    /**
     * Condition that is enabled when there are unsaved changes.
     */
    fun hasUnsavedChanges(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No unsaved changes",
            description = "When there are unsaved changes",
            check = check,
        )

    /**
     * Condition that is enabled when undo is available.
     */
    fun canUndo(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Nothing to undo",
            description = "When undo is available",
            check = check,
        )

    /**
     * Condition that is enabled when redo is available.
     */
    fun canRedo(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Nothing to redo",
            description = "When redo is available",
            check = check,
        )

    // ---- Context-specific conditions ----

    /**
     * Condition that is enabled when a browser tab is active.
     */
    fun isBrowserActive(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No browser tab active",
            description = "When browser is active",
            check = check,
        )

    /**
     * Condition that is enabled when an editor tab is active.
     */
    fun isEditorActive(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No editor tab active",
            description = "When editor is active",
            check = check,
        )

    /**
     * Condition that is enabled when a terminal is active.
     */
    fun isTerminalActive(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No terminal active",
            description = "When terminal is active",
            check = check,
        )

    // ---- Project-related conditions ----

    /**
     * Condition that is enabled when a project is open.
     */
    fun hasProjectOpen(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No project open",
            description = "When a project is open",
            check = check,
        )

    /**
     * Condition that is enabled when files are open in the project.
     */
    fun hasFilesOpen(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No files open",
            description = "When files are open",
            check = check,
        )

    // ---- Navigation-related conditions ----

    /**
     * Condition that is enabled when there is navigation history to go back.
     */
    fun canGoBack(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No previous location",
            description = "When back navigation is available",
            check = check,
        )

    /**
     * Condition that is enabled when there is navigation history to go forward.
     */
    fun canGoForward(check: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "No next location",
            description = "When forward navigation is available",
            check = check,
        )

    // ---- Special conditions ----

    /**
     * Condition that is always enabled.
     */
    fun always(): ShortcutLifecycleCondition = AlwaysEnabledCondition

    /**
     * Condition that is never enabled.
     */
    fun never(reason: String = "Shortcut disabled"): ShortcutLifecycleCondition = NeverEnabledCondition(reason)

    /**
     * Condition that is enabled only in debug/development mode.
     */
    fun debugOnly(isDebugMode: () -> Boolean): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Only available in debug mode",
            description = "Debug mode only",
            check = isDebugMode,
        )

    /**
     * Condition that is enabled based on a feature flag.
     */
    fun featureFlag(
        flagName: String,
        isEnabled: () -> Boolean,
    ): ShortcutLifecycleCondition =
        enabledWhenSync(
            reason = "Feature '$flagName' is disabled",
            description = "When '$flagName' feature is enabled",
            check = isEnabled,
        )
}

// ============================================================================
// Extension functions for ShortcutLifecycleManager registration
// ============================================================================

/**
 * Registers a condition using the fluent builder.
 * ```kotlin
 * ShortcutLifecycleManager.register(KeymapActions.TAB_CLOSE) {
 *     whenTrue { tabCount > 0 }
 *     withReason("No tabs open")
 * }
 * ```
 */
fun ai.rever.boss.keymap.lifecycle.ShortcutLifecycleManager.register(
    actionId: String,
    block: ConditionBuilder.() -> Unit,
) {
    registerCondition(actionId, condition(block))
}
