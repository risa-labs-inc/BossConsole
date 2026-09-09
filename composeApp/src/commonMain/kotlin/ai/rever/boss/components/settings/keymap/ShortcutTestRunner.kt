package ai.rever.boss.components.settings.keymap

import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.isKnownKeyName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
    val timestamp: Long = System.currentTimeMillis(),
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
    NOT_TESTED,
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
    private val knownHandlers =
        setOf(
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
            "test.external_link",
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
            val result =
                ShortcutTestResult(
                    actionId = binding.actionId,
                    binding = binding,
                    status = TestStatus.SKIPPED,
                    message = "Disabled in settings",
                )
            updateResult(result)
            logger.debug(LogCategory.UI, "Skipped (disabled)", mapOf("description" to binding.description))
            return result
        }

        // Step 2: Check lifecycle conditions
        val lifecycleState = ShortcutLifecycleManager.getState(binding.actionId)
        if (lifecycleState != null && !lifecycleState.enabled) {
            val reason = lifecycleState.reason ?: "Context not available"
            val result =
                ShortcutTestResult(
                    actionId = binding.actionId,
                    binding = binding,
                    status = TestStatus.SKIPPED,
                    message = "Context: $reason",
                )
            updateResult(result)
            logger.debug(LogCategory.UI, "Skipped (lifecycle)", mapOf("description" to binding.description, "reason" to reason))
            return result
        }

        // Step 3: Check if handler exists for this action
        if (!knownHandlers.contains(binding.actionId)) {
            val result =
                ShortcutTestResult(
                    actionId = binding.actionId,
                    binding = binding,
                    status = TestStatus.FAILED,
                    message = "No handler for '${binding.actionId}'",
                )
            updateResult(result)
            logger.warn(LogCategory.UI, "Failed (no handler)", mapOf("description" to binding.description, "actionId" to binding.actionId))
            return result
        }

        // Legacy codes that the matcher can resolve are valid too. Only a code that remains
        // numeric after canonicalisation can be rejected here.
        val keyFailure = invalidKeyReason(binding.key)
        if (keyFailure != null) {
            val result =
                ShortcutTestResult(
                    actionId = binding.actionId,
                    binding = binding,
                    status = TestStatus.FAILED,
                    message = keyFailure,
                )
            updateResult(result)
            logger.warn(
                LogCategory.UI,
                "Failed (invalid key name)",
                mapOf("description" to binding.description, "key" to binding.key),
            )
            return result
        }

        // Step 4b: note an unrecognised key name. Deliberately NOT a failure - see
        // [unrecognisedKeyNote] for why that cannot be decided from the name alone.
        val keyNote = unrecognisedKeyNote(binding.key)
        if (keyNote != null) {
            logger.debug(
                LogCategory.UI,
                "Key name not recognised",
                mapOf("description" to binding.description, "key" to binding.key),
            )
        }

        // Step 5: Context-aware validation for specific action types
        val (status, message) = validateContextRequirements(binding)

        val result =
            ShortcutTestResult(
                actionId = binding.actionId,
                binding = binding,
                status = status,
                message = if (keyNote != null) "$message ($keyNote)" else message,
            )
        updateResult(result)

        when (status) {
            TestStatus.SUCCESS -> logger.debug(LogCategory.UI, "Configuration valid", mapOf("description" to binding.description))
            TestStatus.FAILED -> logger.warn(LogCategory.UI, "Failed", mapOf("description" to binding.description, "message" to message))
            else -> logger.debug(LogCategory.UI, "Warning", mapOf("description" to binding.description, "message" to message))
        }

        return result
    }

    /** Whether a numeric key remains unresolved by the same fold the matchers use. */
    internal fun looksLikePackedKeyCode(keyName: String): Boolean =
        keyName.length >= 2 && keyName.all { it.isDigit() } && canonicalKeyName(keyName) == keyName

    /**
     * A note about [keyName] if it is not one this build recognises, or null if it is.
     *
     * This used to be `validateKeyName`, a hand-written set of "valid" key names and a hard
     * FAILED for anything outside it. It was the fourth copy of the key vocabulary in the
     * codebase and the only one nothing tested, so it had drifted: it listed no function key at
     * all, and neither Home, End, PageUp nor PageDown. A shortcut rebound onto F5 was reported
     * as "Unknown key name 'F5' - won't match user input" while working perfectly, and so were
     * the character spellings ("-", "=") that `canonicalKeyName` has folded onto their word
     * forms for as long as it has existed. Fifteen keys in all, every one of them a false
     * failure on a shortcut that worked.
     *
     * It is a NOTE now rather than a verdict, because the honest answer to "will this match" is
     * not knowable from the name alone: [canonicalKeyName] folds an unknown name onto itself, and
     * the keyboard has keys outside the interceptor's table (F13 and up, the keypad) that AWT
     * still names. Reporting a shortcut as broken when it works is worse than saying nothing, so
     * an unrecognised name says it is unrecognised and the test does not fail on it.
     */
    internal fun unrecognisedKeyNote(keyName: String): String? =
        if (isKnownKeyName(keyName)) {
            null
        } else {
            "unrecognised key name '$keyName'"
        }

    /**
     * Validates context-specific requirements for different action types.
     * Provides more accurate status based on the action's requirements.
     */
    private fun validateContextRequirements(binding: KeyBinding): Pair<TestStatus, String> =
        when {
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
        logger.info(
            LogCategory.UI,
            "Batch test complete",
            mapOf(
                "success" to countByStatus(TestStatus.SUCCESS),
                "failed" to countByStatus(TestStatus.FAILED),
                "skipped" to countByStatus(TestStatus.SKIPPED),
            ),
        )
    }

    /**
     * Tests shortcuts in a specific category.
     *
     * @param settings The keymap settings
     * @param category The category to test
     */
    suspend fun testCategory(
        settings: KeymapSettings,
        category: String,
    ) {
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
    fun countByStatus(status: TestStatus): Int = _testResults.value.values.count { it.status == status }

    /**
     * Gets test results filtered by status.
     */
    fun getResultsByStatus(status: TestStatus): List<ShortcutTestResult> =
        _testResults.value.values
            .filter { it.status == status }
            .sortedBy { it.binding.description }

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
    val total: Int,
) {
    val percentage: Float
        get() = if (total > 0) completed.toFloat() / total.toFloat() else 0f

    val isComplete: Boolean
        get() = completed >= total && total > 0
}

private fun invalidKeyReason(keyName: String): String? =
    when {
        ShortcutTestRunner.looksLikePackedKeyCode(keyName) -> {
            "Stored as a raw key code ('$keyName') - re-record this shortcut"
        }

        keyName.isBlank() && !isKnownKeyName(keyName) -> {
            "Empty key name - re-record this shortcut"
        }

        else -> {
            null
        }
    }
