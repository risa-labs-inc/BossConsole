package ai.rever.boss.components.wizard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * State management for multi-step wizards.
 *
 * Handles navigation between visible steps, skipping hidden steps automatically.
 * Supports going forward, backward, and jumping to specific steps.
 *
 * @param S The type of wizard step, must implement [WizardStep]
 * @param steps The list of all steps in the wizard
 * @param initialStepIndex The starting step index (default 0)
 */
class WizardState<S : WizardStep>(
    private val steps: List<S>,
    private val initialStepIndex: Int = 0
) {
    private val _currentStepIndex = mutableStateOf(initialStepIndex)

    /**
     * The current step index as observable state.
     */
    val currentStepIndex: State<Int> = _currentStepIndex

    /**
     * The current step being displayed.
     */
    val currentStep: S
        get() = steps[_currentStepIndex.value]

    /**
     * Whether the current step is the first visible step.
     */
    val isFirstStep: Boolean
        get() = findPreviousVisibleStepIndex(_currentStepIndex.value) == null

    /**
     * Whether the current step is the last visible step.
     */
    val isLastStep: Boolean
        get() = findNextVisibleStepIndex(_currentStepIndex.value) == null

    /**
     * List of all visible steps for step indicators.
     */
    val visibleSteps: List<S>
        get() = steps.filter { it.isVisible }

    /**
     * The index of the current step within visible steps only.
     * Useful for step indicators.
     */
    val currentVisibleStepIndex: Int
        get() {
            val visibleList = visibleSteps
            return visibleList.indexOf(currentStep).takeIf { it >= 0 } ?: 0
        }

    /**
     * Total number of visible steps.
     */
    val totalVisibleSteps: Int
        get() = visibleSteps.size

    /**
     * Navigate to the next visible step.
     * If already at the last step, does nothing.
     */
    fun goToNextStep() {
        val nextIndex = findNextVisibleStepIndex(_currentStepIndex.value)
        if (nextIndex != null) {
            _currentStepIndex.value = nextIndex
        }
    }

    /**
     * Navigate to the previous visible step.
     * If already at the first step, does nothing.
     */
    fun goToPreviousStep() {
        val prevIndex = findPreviousVisibleStepIndex(_currentStepIndex.value)
        if (prevIndex != null) {
            _currentStepIndex.value = prevIndex
        }
    }

    /**
     * Navigate to a specific step by its index in the full steps list.
     * If the step is not visible, finds the nearest visible step.
     *
     * @param index The target step index
     */
    fun goToStep(index: Int) {
        if (index < 0 || index >= steps.size) return

        if (steps[index].isVisible) {
            _currentStepIndex.value = index
        } else {
            // Find nearest visible step
            val nextVisible = findNextVisibleStepIndex(index - 1)
            val prevVisible = findPreviousVisibleStepIndex(index + 1)

            when {
                nextVisible != null -> _currentStepIndex.value = nextVisible
                prevVisible != null -> _currentStepIndex.value = prevVisible
            }
        }
    }

    /**
     * Reset the wizard to the initial step.
     */
    fun reset() {
        _currentStepIndex.value = initialStepIndex
    }

    private fun findNextVisibleStepIndex(fromIndex: Int): Int? {
        for (i in (fromIndex + 1) until steps.size) {
            if (steps[i].isVisible) return i
        }
        return null
    }

    private fun findPreviousVisibleStepIndex(fromIndex: Int): Int? {
        for (i in (fromIndex - 1) downTo 0) {
            if (steps[i].isVisible) return i
        }
        return null
    }
}

/**
 * Create a wizard state with the given steps.
 *
 * @param steps The list of wizard steps
 * @param initialStepIndex The starting step index (default 0)
 * @return A new [WizardState] instance
 */
fun <S : WizardStep> wizardStateOf(
    steps: List<S>,
    initialStepIndex: Int = 0
): WizardState<S> = WizardState(steps, initialStepIndex)
