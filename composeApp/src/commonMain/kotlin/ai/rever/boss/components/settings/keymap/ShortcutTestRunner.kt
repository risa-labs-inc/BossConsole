package ai.rever.boss.components.settings.keymap

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of a keyboard shortcut test.
 */
data class ShortcutTestResult(
    val actionId: String,
    val binding: KeyBinding,
    val status: TestStatus,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Status of a shortcut test.
 */
enum class TestStatus {
    /** Test is currently running */
    TESTING,

    /** Shortcut is enabled and handler exists */
    SUCCESS,

    /** Shortcut failed - not enabled or no handler */
    FAILED,

    /** Shortcut skipped - lifecycle conditions not met */
    SKIPPED,

    /** Test not yet run */
    NOT_TESTED
}

/**
 * Core testing logic for keyboard shortcuts.
 * Provides non-destructive testing by checking lifecycle states and handler existence.
 */
object ShortcutTestRunner {
    private val logger = BossLogger.forComponent("ShortcutTestRunner")
    private val _testResults = MutableStateFlow<Map<String, ShortcutTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ShortcutTestResult>> = _testResults.asStateFlow()

    private val _currentTesting = MutableStateFlow<String?>(null)
    val currentTesting: StateFlow<String?> = _currentTesting.asStateFlow()

    private val _testProgress = MutableStateFlow(TestProgress(0, 0))
    val testProgress: StateFlow<TestProgress> = _testProgress.asStateFlow()

    /**
     * Known action handlers that are implemented in BossApp.kt.
     * This list should match the action IDs in KeymapActions.
     */
    private val knownHandlers = setOf(
        "window.new",
        "window.close",
        "tab.new",
        "tab.close",
        "browser.reload",
        "browser.zoom_reset",
        "browser.zoom_in",
        "browser.zoom_out",
        "panel.navigate_left",
        "panel.navigate_right",
        "panel.navigate_up",
        "panel.navigate_down",
        "quick_switcher.open",
        "workspace.save",
        "codebase.open",
        "test.external_link"
    )

    /**
     * Tests a single keyboard shortcut.
     * Performs configuration checks and provides detailed status.
     * Note: This is a non-destructive configuration check, not a full execution test.
     *
     * @param binding The key binding to test
     * @return The test result
     */
    suspend fun testShortcut(binding: KeyBinding): ShortcutTestResult {
        _currentTesting.value = binding.actionId
        logger.debug(LogCategory.UI, "Testing shortcut", mapOf("description" to binding.description, "keys" to binding.displayString()))

        // Step 1: Check if the binding is enabled
        if (!binding.enabled) {
            val result = ShortcutTestResult(
                actionId = binding.actionId,
                binding = binding,
                status = TestStatus.SKIPPED,
                message = "Disabled in settings"
            )
            updateResult(result)
            logger.debug(LogCategory.UI, "Skipped (disabled)", mapOf("description" to binding.description))
            return result
        }

        // Step 2: Check lifecycle conditions
        val lifecycleState = ShortcutLifecycleManager.getState(binding.actionId)
        if (lifecycleState != null && !lifecycleState.enabled) {
            val reason = lifecycleState.reason ?: "Context not available"
            val result = ShortcutTestResult(
                actionId = binding.actionId,
                binding = binding,
                status = TestStatus.SKIPPED,
                message = "Context: $reason"
            )
            updateResult(result)
            logger.debug(LogCategory.UI, "Skipped (lifecycle)", mapOf("description" to binding.description, "reason" to reason))
            return result
        }

        // Step 3: Check if handler exists for this action
        if (!knownHandlers.contains(binding.actionId)) {
            val result = ShortcutTestResult(
                actionId = binding.actionId,
                binding = binding,
                status = TestStatus.FAILED,
                message = "No handler for '${binding.actionId}'"
            )
            updateResult(result)
            logger.warn(LogCategory.UI, "Failed (no handler)", mapOf("description" to binding.description, "actionId" to binding.actionId))
            return result
        }

        // Step 4: Validate key name - ensure it will match when user presses it
        val keyValidation = validateKeyName(binding.key)
        if (!keyValidation.first) {
            val result = ShortcutTestResult(
                actionId = binding.actionId,
                binding = binding,
                status = TestStatus.FAILED,
                message = keyValidation.second
            )
            updateResult(result)
            logger.warn(LogCategory.UI, "Failed (invalid key)", mapOf("description" to binding.description, "error" to keyValidation.second))
            return result
        }

        // Step 5: Context-aware validation for specific action types
        val (status, message) = validateContextRequirements(binding)

        val result = ShortcutTestResult(
            actionId = binding.actionId,
            binding = binding,
            status = status,
            message = message
        )
        updateResult(result)

        when (status) {
            TestStatus.SUCCESS -> logger.debug(LogCategory.UI, "Configuration valid", mapOf("description" to binding.description))
            TestStatus.FAILED -> logger.warn(LogCategory.UI, "Failed", mapOf("description" to binding.description, "message" to message))
            else -> logger.debug(LogCategory.UI, "Warning", mapOf("description" to binding.description, "message" to message))
        }

        return result
    }

    /**
     * Validates that a key name is recognized and will match when the user presses it.
     * Checks against known key names that are handled by normalizeKeyName in KeymapMatcher.
     *
     * NOTE: This is a static validation that checks if the key name is in the correct format.
     * It does NOT actually simulate key presses, because creating synthetic KeyEvent objects
     * in Compose is complex. However, it catches the most common errors:
     * - Using character forms ("-", "=", "0") instead of word forms ("Minus", "Equals", "Zero")
     * - Using arrow characters ("←") instead of word forms ("Left")
     * - Using unknown key names
     *
     * @return Pair<Boolean, String> - (isValid, errorMessage)
     */
    private fun validateKeyName(keyName: String): Pair<Boolean, String> {
        // List of valid key names (word forms that normalizeKeyName handles)
        val validWordKeyNames = setOf(
            // Numbers
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            // Symbols
            "Minus", "Equals", "Plus",
            "OpenBracket", "CloseBracket", "Slash", "Backslash",
            "Semicolon", "Apostrophe", "Comma", "Period", "Grave",
            // Directions
            "Left", "Right", "Up", "Down",
            "DirectionLeft", "DirectionRight", "DirectionUp", "DirectionDown",
            // Special keys
            "Space", "Spacebar", "Enter", "Return", "Escape", "Esc", "Tab",
            "Backspace", "Delete"
        )

        // Check if it's a valid word name (case-insensitive)
        if (validWordKeyNames.any { it.equals(keyName, ignoreCase = true) }) {
            return Pair(true, "")
        }

        // Check if it's a single letter (A-Z)
        if (keyName.length == 1 && keyName[0].isLetter()) {
            return Pair(true, "")
        }

        // Check if it's a single character that should be a word name
        val characterToWordMap = mapOf(
            // Symbols
            "-" to "Minus",
            "=" to "Equals",
            "+" to "Plus",
            // Numbers
            "0" to "Zero",
            "1" to "One",
            "2" to "Two",
            "3" to "Three",
            "4" to "Four",
            "5" to "Five",
            "6" to "Six",
            "7" to "Seven",
            "8" to "Eight",
            "9" to "Nine",
            // Arrow characters
            "←" to "Left",
            "→" to "Right",
            "↑" to "Up",
            "↓" to "Down"
        )

        if (characterToWordMap.containsKey(keyName)) {
            val correctName = characterToWordMap[keyName]
            return Pair(false, "Key name should be '$correctName' not '$keyName'")
        }

        // Unknown key name
        return Pair(false, "Unknown key name '$keyName' - won't match user input")
    }

    /**
     * Validates context-specific requirements for different action types.
     * Provides more accurate status based on the action's requirements.
     */
    private fun validateContextRequirements(binding: KeyBinding): Pair<TestStatus, String> {
        return when {
            // Browser-specific actions
            binding.actionId.startsWith("browser.") -> {
                // Browser actions require FluckTabComponent to be active
                Pair(TestStatus.SUCCESS, "Handler exists (requires browser tab)")
            }

            // Panel navigation
            binding.actionId in listOf("panel.navigate_left", "panel.navigate_right", "panel.navigate_up", "panel.navigate_down") -> {
                // Panel navigation requires multiple panels
                Pair(TestStatus.SUCCESS, "Handler exists (requires 2+ panels)")
            }

            // All other actions
            else -> {
                Pair(TestStatus.SUCCESS, "Handler exists and enabled")
            }
        }
    }

    /**
     * Tests all shortcuts in the keymap settings.
     *
     * @param settings The keymap settings containing all shortcuts
     */
    suspend fun testAllShortcuts(settings: KeymapSettings) {
        logger.info(LogCategory.UI, "Starting batch test", mapOf("count" to settings.shortcuts.size))

        // Clear previous results
        _testResults.value = emptyMap()

        val shortcuts = settings.shortcuts.values.toList()
        val total = shortcuts.size
        var completed = 0

        for (binding in shortcuts) {
            completed++
            _testProgress.value = TestProgress(completed, total)
            testShortcut(binding)
        }

        _currentTesting.value = null
        logger.info(LogCategory.UI, "Batch test complete", mapOf("success" to countByStatus(TestStatus.SUCCESS), "failed" to countByStatus(TestStatus.FAILED), "skipped" to countByStatus(TestStatus.SKIPPED)))
    }

    /**
     * Tests shortcuts in a specific category.
     *
     * @param settings The keymap settings
     * @param category The category to test
     */
    suspend fun testCategory(settings: KeymapSettings, category: String) {
        val shortcuts = settings.shortcuts.values.filter { it.category == category }
        logger.info(LogCategory.UI, "Testing category", mapOf("category" to category, "count" to shortcuts.size))

        for (binding in shortcuts) {
            testShortcut(binding)
        }

        _currentTesting.value = null
    }

    /**
     * Clears all test results.
     */
    fun clearResults() {
        _testResults.value = emptyMap()
        _currentTesting.value = null
        _testProgress.value = TestProgress(0, 0)
    }

    /**
     * Gets the count of results by status.
     */
    fun countByStatus(status: TestStatus): Int {
        return _testResults.value.values.count { it.status == status }
    }

    /**
     * Gets test results filtered by status.
     */
    fun getResultsByStatus(status: TestStatus): List<ShortcutTestResult> {
        return _testResults.value.values.filter { it.status == status }.sortedBy { it.binding.description }
    }

    /**
     * Updates a test result in the state flow.
     */
    private fun updateResult(result: ShortcutTestResult) {
        _testResults.value = _testResults.value + (result.actionId to result)
    }
}

/**
 * Progress information for batch testing.
 */
data class TestProgress(
    val completed: Int,
    val total: Int
) {
    val percentage: Float
        get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f

    val isComplete: Boolean
        get() = completed >= total && total > 0
}
