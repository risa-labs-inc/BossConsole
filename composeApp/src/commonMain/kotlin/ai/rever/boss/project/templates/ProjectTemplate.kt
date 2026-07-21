package ai.rever.boss.project.templates

import ai.rever.boss.icons.LanguageIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Represents a project template that can be used to create new projects.
 * Following IntelliJ IDEA's pattern of static file templates with placeholder substitution.
 *
 * Placeholder tokens supported:
 * - {PROJECT_NAME} - The project name entered by the user
 * - {PACKAGE_NAME} - Package name derived from project name (lowercase, no spaces)
 */
sealed class ProjectTemplate {
    abstract val id: String
    abstract val name: String
    abstract val description: String
    abstract val icon: ImageVector
    abstract val files: List<TemplateFile>

    /**
     * Empty project - just basic files like .gitignore and README.md
     */
    data object Empty : ProjectTemplate() {
        override val id = "empty"
        override val name = "Empty Project"
        override val description = "Start with a blank project"
        override val icon: ImageVector = Icons.Outlined.Folder
        override val files: List<TemplateFile> = TemplateDefinitions.emptyFiles
    }

    /**
     * Kotlin/JVM project with Gradle build system
     */
    data object KotlinJvm : ProjectTemplate() {
        override val id = "kotlin-jvm"
        override val name = "Kotlin/JVM"
        override val description = "Kotlin project with Gradle"
        override val icon: ImageVector = LanguageIcons.kotlin
        override val files: List<TemplateFile> = TemplateDefinitions.kotlinJvmFiles
    }

    /**
     * Node.js project with package.json
     */
    data object NodeJs : ProjectTemplate() {
        override val id = "nodejs"
        override val name = "Node.js"
        override val description = "npm project with package.json"
        override val icon: ImageVector = LanguageIcons.nodejs
        override val files: List<TemplateFile> = TemplateDefinitions.nodeJsFiles
    }

    /**
     * Go module project
     */
    data object Go : ProjectTemplate() {
        override val id = "go"
        override val name = "Go"
        override val description = "Go module project"
        override val icon: ImageVector = LanguageIcons.go
        override val files: List<TemplateFile> = TemplateDefinitions.goFiles
    }

    /**
     * Rust project with Cargo
     */
    data object Rust : ProjectTemplate() {
        override val id = "rust"
        override val name = "Rust"
        override val description = "Cargo-based Rust project"
        override val icon: ImageVector = LanguageIcons.rust
        override val files: List<TemplateFile> = TemplateDefinitions.rustFiles
    }

    /**
     * Python project with pyproject.toml
     */
    data object Python : ProjectTemplate() {
        override val id = "python"
        override val name = "Python"
        override val description = "Python project with virtual environment"
        override val icon: ImageVector = LanguageIcons.python
        override val files: List<TemplateFile> = TemplateDefinitions.pythonFiles
    }

    companion object {
        /**
         * All available project templates
         */
        val all: List<ProjectTemplate> = listOf(
            Empty,
            KotlinJvm,
            NodeJs,
            Go,
            Rust,
            Python
        )

        /**
         * Find a template by its ID
         */
        fun findById(id: String): ProjectTemplate? = all.find { it.id == id }
    }
}

/**
 * Represents a file to be created from a template.
 *
 * @param relativePath The path relative to the project root (e.g., "src/main/kotlin/Main.kt")
 * @param content The file content with optional placeholder tokens
 * @param isExecutable Whether the file should have executable permissions (for scripts)
 */
data class TemplateFile(
    val relativePath: String,
    val content: String,
    val isExecutable: Boolean = false
)
