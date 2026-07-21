package ai.rever.boss.project

import ai.rever.boss.window.Project
import ai.rever.boss.platform.FileSystemUtils
import ai.rever.boss.project.templates.ProjectTemplate
import ai.rever.boss.project.templates.TemplateFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop implementation of ProjectCreationService.
 * Creates projects by writing static template files with placeholder substitution.
 */
actual object ProjectCreationService {

    // Windows reserved filenames (case-insensitive)
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    // Maximum filename length for most filesystems
    private const val MAX_NAME_LENGTH = 255

    actual suspend fun createProject(
        name: String,
        parentDirectory: String,
        template: ProjectTemplate,
        onProgress: (Float, String) -> Unit
    ): Result<Project> = withContext(Dispatchers.IO) {
        var projectDir: File? = null
        try {
            val trimmedName = name.trim()
            projectDir = File(parentDirectory, trimmedName)
            val packageName = derivePackageName(trimmedName)

            // Step 1: Validate location
            onProgress(0.05f, "Validating project location...")
            val validation = validateProjectLocation(parentDirectory, trimmedName)
            if (validation is ValidationResult.Invalid) {
                return@withContext Result.failure(IllegalArgumentException(validation.reason))
            }

            // Step 2: Create project directory (with race condition check)
            onProgress(0.1f, "Creating project directory...")
            if (projectDir.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Project directory was created by another process")
                )
            }
            if (!projectDir.mkdirs()) {
                return@withContext Result.failure(
                    IllegalStateException("Failed to create project directory: ${projectDir.absolutePath}")
                )
            }

            // Step 3: Write template files
            val totalFiles = template.files.size
            if (totalFiles > 0) {
                template.files.forEachIndexed { index, templateFile ->
                    val progress = 0.1f + (0.8f * (index + 1) / totalFiles)
                    onProgress(progress, "Creating ${templateFile.relativePath}...")

                    writeTemplateFile(projectDir, templateFile, trimmedName, packageName)
                }
            }

            // Step 4: Complete
            onProgress(1.0f, "Project created successfully!")

            Result.success(
                Project(
                    name = trimmedName,
                    path = projectDir.absolutePath,
                    lastOpened = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            // Cleanup partial project on failure
            projectDir?.let { dir ->
                if (dir.exists()) {
                    try {
                        dir.deleteRecursively()
                    } catch (_: Exception) {
                        // Best effort cleanup
                    }
                }
            }
            Result.failure(e)
        }
    }

    actual fun validateProjectLocation(parentDirectory: String, projectName: String): ValidationResult {
        val trimmedName = projectName.trim()

        // Check project name is not empty
        if (trimmedName.isBlank()) {
            return ValidationResult.Invalid("Project name cannot be empty")
        }

        // Check maximum length
        if (trimmedName.length > MAX_NAME_LENGTH) {
            return ValidationResult.Invalid("Project name exceeds maximum length of $MAX_NAME_LENGTH characters")
        }

        // Check for leading dots (creates hidden directories on Unix)
        if (trimmedName.startsWith(".")) {
            return ValidationResult.Invalid("Project name cannot start with a dot")
        }

        // Check for trailing dots or spaces (Windows compatibility)
        if (trimmedName.endsWith(".") || trimmedName.endsWith(" ")) {
            return ValidationResult.Invalid("Project name cannot end with a dot or space")
        }

        // Check for Windows reserved names
        val nameWithoutExtension = trimmedName.substringBefore(".").uppercase()
        if (nameWithoutExtension in WINDOWS_RESERVED_NAMES) {
            return ValidationResult.Invalid("'$trimmedName' is a reserved name on Windows")
        }

        // Check for invalid characters in project name
        val invalidChars = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val foundInvalidChar = invalidChars.find { trimmedName.contains(it) }
        if (foundInvalidChar != null) {
            return ValidationResult.Invalid("Project name contains invalid character: '$foundInvalidChar'")
        }

        // Check parent directory exists
        val parentDir = File(parentDirectory)
        if (!parentDir.exists()) {
            // Try to create the default directory if it doesn't exist
            if (parentDirectory == getDefaultProjectsDirectory()) {
                try {
                    if (!parentDir.mkdirs()) {
                        return ValidationResult.Invalid("Cannot create projects directory: $parentDirectory")
                    }
                } catch (e: Exception) {
                    return ValidationResult.Invalid("Cannot create projects directory: ${e.message}")
                }
            } else {
                return ValidationResult.Invalid("Parent directory does not exist: $parentDirectory")
            }
        }

        // Check parent directory is writable
        if (!FileSystemUtils.isDirectoryWritable(parentDirectory)) {
            return ValidationResult.Invalid("Cannot write to directory: $parentDirectory")
        }

        // Check project directory doesn't already exist
        val projectDir = File(parentDirectory, trimmedName)
        if (projectDir.exists()) {
            return ValidationResult.Invalid("A project with this name already exists at this location")
        }

        return ValidationResult.Valid
    }

    actual fun getDefaultProjectsDirectory(): String {
        val userHome = System.getProperty("user.home")
        return "$userHome/BossProjects"
    }

    actual fun derivePackageName(projectName: String): String {
        // Convert to lowercase and replace common separators with dots for better structure
        val processed = projectName
            .lowercase()
            .replace(Regex("[-_ ]+"), ".")  // Convert separators to dots
            .replace(Regex("[^a-z0-9.]"), "") // Remove invalid chars
            .replace(Regex("\\.+"), ".")     // Collapse multiple dots
            .trim('.')                        // Remove leading/trailing dots

        // Ensure it doesn't start with a number (invalid in most languages)
        val result = if (processed.isNotEmpty() && processed[0].isDigit()) {
            "p$processed"
        } else {
            processed
        }

        return result.ifEmpty { "project" }
    }

    /**
     * Writes a template file to the project directory, replacing placeholders.
     * @throws SecurityException if the template path escapes the project directory
     * @throws java.io.IOException if file writing fails
     */
    private fun writeTemplateFile(
        projectDir: File,
        templateFile: TemplateFile,
        projectName: String,
        packageName: String
    ) {
        val file = File(projectDir, templateFile.relativePath)

        // SECURITY: Ensure file is within project directory (prevent path traversal)
        if (!file.canonicalPath.startsWith(projectDir.canonicalPath)) {
            throw SecurityException("Template file path escapes project directory: ${templateFile.relativePath}")
        }

        // Ensure parent directories exist
        file.parentFile?.mkdirs()

        // Replace placeholders in content
        val content = templateFile.content
            .replace("{PROJECT_NAME}", projectName)
            .replace("{PACKAGE_NAME}", packageName)

        // Write the file with error handling
        try {
            file.writeText(content)
        } catch (e: Exception) {
            throw java.io.IOException("Failed to write file ${templateFile.relativePath}: ${e.message}", e)
        }

        // Set executable permission if needed
        if (templateFile.isExecutable) {
            file.setExecutable(true)
        }
    }
}
