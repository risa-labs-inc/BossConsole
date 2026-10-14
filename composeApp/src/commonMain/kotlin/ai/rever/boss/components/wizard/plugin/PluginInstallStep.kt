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

/** A human-readable starting point for the toolbox recommendation. */
enum class ToolboxProfile(
    val displayName: String,
    val description: String,
    internal val categories: Set<PluginCategory>,
    internal val additionalPluginIds: Set<String> = emptySet(),
) {
    DEVELOPER(
        "Software development",
        "Tools for writing, reviewing, running, and shipping software.",
        setOf(PluginCategory.DEVELOPER),
    ),
    PRODUCT_DESIGN(
        "Product & design",
        "Tools for research, planning, documentation, and product work.",
        setOf(PluginCategory.PRODUCTIVITY),
    ),
    OPERATIONS_AUTOMATION(
        "Operations & automation",
        "Tools for repeatable workflows, administration, and automation.",
        setOf(PluginCategory.AUTOMATION, PluginCategory.ADMIN),
    ),
    GENERAL(
        "General use",
        "A focused setup for browsing, files, downloads, and everyday work.",
        emptySet(),
        setOf(
            "ai.rever.boss.plugin.dynamic.bookmarks",
            "ai.rever.boss.plugin.dynamic.downloads",
        ),
    ),
    EVERYTHING(
        "Everything",
        "Every available tool for development, product, design, operations, automation, and general use.",
        PluginCategory.entries.toSet(),
    ),
}

/**
 * Steps in the plugin installation wizard.
 *
 * The wizard flows through:
 * 1. Welcome - Introduction to the plugin system
 * 2. Profile - A role-based recommendation
 * 3. Review - The exact, editable tool selection
 * 4. Installing - Installation progress
 * 5. Complete - Success summary
 */
sealed class PluginInstallStep(
    override val title: String,
    override val canSkip: Boolean = false,
) : WizardStep {
    /**
     * Welcome step introducing the plugin system.
     */
    data object Welcome : PluginInstallStep(
        title = "Welcome",
        canSkip = false,
    )

    data object Profile : PluginInstallStep(
        title = "Your profile",
    )

    data object Review : PluginInstallStep(
        title = "Review tools",
    )

    /**
     * Installation progress step.
     */
    data object Installing : PluginInstallStep(
        title = "Installing",
        canSkip = false,
    )

    /**
     * Completion step showing summary.
     */
    data object Complete : PluginInstallStep(
        title = "Complete",
        canSkip = false,
    )

    companion object {
        /**
         * All steps in the wizard in order.
         */
        val allSteps: List<PluginInstallStep> =
            listOf(
                Welcome,
                Profile,
                Review,
                Installing,
                Complete,
            )
    }
}
