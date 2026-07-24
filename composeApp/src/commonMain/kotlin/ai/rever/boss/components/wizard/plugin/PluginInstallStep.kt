package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.wizard.WizardStep

/**
 * Categories for organizing plugins in the installation wizard.
 */
enum class PluginCategory(
    val displayName: String,
    val description: String,
) {
    ESSENTIAL("Essential", "Core tools for basic functionality"),
    DEVELOPER("Developer Tools", "Tools for code development and version control"),
    PRODUCTIVITY("Productivity", "Tools to enhance your workflow"),
    AUTOMATION("Automation", "Automate repetitive tasks"),
    ADMIN("Admin Tools", "Administrative and management tools"),
    OTHER("Other", "Additional tools"),
}

/**
 * Steps in the plugin installation wizard.
 *
 * The wizard flows through:
 * 1. Welcome - Introduction to the plugin system
 * 2. Essential Plugins - Critical plugins (pre-selected)
 * 3. Developer Plugins - Code and git tools
 * 4. Productivity Plugins - Workflow enhancement
 * 5. Automation Plugins - Task automation
 * 6. Admin Plugins - Administration tools
 * 7. Installing - Installation progress
 * 8. Complete - Success summary
 */
sealed class PluginInstallStep(
    override val title: String,
    val category: PluginCategory? = null,
    override val canSkip: Boolean = false,
) : WizardStep {
    /**
     * Welcome step introducing the plugin system.
     */
    data object Welcome : PluginInstallStep(
        title = "Welcome",
        category = null,
        canSkip = false,
    )

    /**
     * Essential plugins step (Terminal, Console).
     * These are pre-selected by default.
     */
    data object EssentialPlugins : PluginInstallStep(
        title = "Essential Tools",
        category = PluginCategory.ESSENTIAL,
        canSkip = true,
    )

    /**
     * Developer tools step (Codebase, Git Status, Git Log).
     */
    data object DeveloperPlugins : PluginInstallStep(
        title = "Developer Tools",
        category = PluginCategory.DEVELOPER,
        canSkip = true,
    )

    /**
     * Productivity plugins step (Bookmarks, TopOfMind, Downloads).
     */
    data object ProductivityPlugins : PluginInstallStep(
        title = "Productivity",
        category = PluginCategory.PRODUCTIVITY,
        canSkip = true,
    )

    /**
     * Automation plugins step (LLM RPA, RPA Recorder, RPA Engine).
     */
    data object AutomationPlugins : PluginInstallStep(
        title = "Automation",
        category = PluginCategory.AUTOMATION,
        canSkip = true,
    )

    /**
     * Admin plugins step (Role Management, Role Creation, Secret Manager).
     */
    data object AdminPlugins : PluginInstallStep(
        title = "Admin Tools",
        category = PluginCategory.ADMIN,
        canSkip = true,
    )

    /**
     * Other plugins step (additional plugins from the store).
     */
    data object OtherPlugins : PluginInstallStep(
        title = "Other Tools",
        category = PluginCategory.OTHER,
        canSkip = true,
    )

    /**
     * Installation progress step.
     */
    data object Installing : PluginInstallStep(
        title = "Installing",
        category = null,
        canSkip = false,
    )

    /**
     * Completion step showing summary.
     */
    data object Complete : PluginInstallStep(
        title = "Complete",
        category = null,
        canSkip = false,
    )

    companion object {
        /**
         * All steps in the wizard in order.
         */
        val allSteps: List<PluginInstallStep> =
            listOf(
                Welcome,
                EssentialPlugins,
                DeveloperPlugins,
                ProductivityPlugins,
                AutomationPlugins,
                AdminPlugins,
                OtherPlugins,
                Installing,
                Complete,
            )

        /**
         * Category selection steps only.
         */
        val categorySteps: List<PluginInstallStep> = allSteps.filter { it.category != null }
    }
}
