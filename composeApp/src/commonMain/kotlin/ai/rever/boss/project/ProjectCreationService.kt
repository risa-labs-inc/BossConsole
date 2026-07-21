package ai.rever.boss.project

import ai.rever.boss.window.Project
import ai.rever.boss.project.templates.ProjectTemplate

/**
 * Service for creating new projects from templates.
 * Following IntelliJ IDEA's pattern: static file templates with placeholder substitution.
 */
expect object ProjectCreationService {
    /**
     * Creates a new project with the given template.
     *
     * @param name The project name (used for directory name and placeholders)
     * @param parentDirectory The parent directory where the project will be created
     * @param template The template to use for project creation
     * @param onProgress Progress callback with (progress 0.0-1.0, message)
     * @return Result<Project> containing the created project or an error
     */
    suspend fun createProject(
        name: String,
        parentDirectory: String,
        template: ProjectTemplate,
        onProgress: (Float, String) -> Unit
    ): Result<Project>

    /**
     * Validates the project location before creation.
     *
     * @param parentDirectory The parent directory where the project will be created
     * @param projectName The name of the project (will be the directory name)
     * @return ValidationResult indicating if the location is valid or the reason it's not
     */
    fun validateProjectLocation(parentDirectory: String, projectName: String): ValidationResult

    /**
     * Gets the default projects directory.
     * Returns ~/BossProjects/ on all platforms.
     */
    fun getDefaultProjectsDirectory(): String

    /**
     * Derives a valid package name from a project name.
     * Converts to lowercase, removes spaces and special characters.
     *
     * @param projectName The original project name
     * @return A valid package name
     */
    fun derivePackageName(projectName: String): String
}

/**
 * Result of validating a project location.
 */
sealed class ValidationResult {
    /**
     * The location is valid for project creation.
     */
    data object Valid : ValidationResult()

    /**
     * The location is invalid with a reason.
     */
    data class Invalid(val reason: String) : ValidationResult()
}
