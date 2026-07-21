package ai.rever.boss.components.wizard

/**
 * Base interface for wizard steps.
 *
 * Implement this interface to define custom wizard steps with
 * visibility control and skip behavior.
 */
interface WizardStep {
    /**
     * The title displayed for this step in the wizard.
     */
    val title: String

    /**
     * Whether this step should be visible in the wizard.
     * Hidden steps are skipped during navigation.
     */
    val isVisible: Boolean get() = true

    /**
     * Whether the user can skip this step.
     * When true, a "Skip" button may be shown.
     */
    val canSkip: Boolean get() = false
}
