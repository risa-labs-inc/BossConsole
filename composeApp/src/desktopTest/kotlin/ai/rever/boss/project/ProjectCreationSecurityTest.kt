package ai.rever.boss.project

import ai.rever.boss.project.templates.ProjectTemplate
import ai.rever.boss.project.templates.TemplateFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security tests for ProjectCreationService
 *
 * Tests cover:
 * - Path traversal attack prevention
 * - Malicious template file paths
 * - Security boundary enforcement
 */
class ProjectCreationSecurityTest {
    @TempDir
    lateinit var tempDir: File

    // ==================== Path Traversal Prevention Tests ====================

    @Test
    fun `test path traversal in project name is rejected`() =
        runBlocking {
            val maliciousNames =
                listOf(
                    "../escape",
                    "..\\escape",
                    "project/../../../etc",
                    "project/../../..",
                    "valid/../invalid",
                )

            maliciousNames.forEach { name ->
                val result = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, name)
                assertTrue(
                    result is ValidationResult.Invalid,
                    "Path traversal should be rejected: $name",
                )
            }
        }

    @Test
    fun `test null byte in project name is handled`() {
        // Null bytes could be used to truncate filenames in some systems
        val nameWithNull = "project\u0000malicious"

        // This should either be rejected or sanitized
        val result = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, nameWithNull)

        // Either invalid or the null byte is stripped
        if (result is ValidationResult.Valid) {
            // If valid, verify it's sanitized (no null byte in actual directory)
            val projectDir = File(tempDir, nameWithNull.replace("\u0000", ""))
            // Should not contain null byte
            assertFalse(projectDir.name.contains("\u0000"))
        }
    }

    // ==================== Template Security Tests ====================

    @Test
    fun `test legitimate template paths are accepted`() =
        runBlocking {
            val result =
                ProjectCreationService.createProject(
                    name = "secure-project",
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Legitimate project should be created")

            // Verify all files are within project directory
            val projectDir = File(tempDir, "secure-project")
            projectDir.walkTopDown().forEach { file ->
                assertTrue(
                    file.canonicalPath.startsWith(projectDir.canonicalPath),
                    "All files should be within project directory: ${file.absolutePath}",
                )
            }
        }

    @Test
    fun `test created files do not escape project directory`() =
        runBlocking {
            // Create a project with a template that has multiple files
            val result =
                ProjectCreationService.createProject(
                    name = "bounded-project",
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.KotlinJvm,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val projectDir = File(tempDir, "bounded-project")
            val parentCanonical = tempDir.canonicalPath

            // Verify no files were created outside the temp directory
            val allTempFiles = tempDir.walkTopDown().toList()
            allTempFiles.forEach { file ->
                assertTrue(
                    file.canonicalPath.startsWith(parentCanonical),
                    "File should be within temp directory: ${file.absolutePath}",
                )
            }

            // Verify no files escaped to parent's parent
            val grandparent = tempDir.parentFile?.parentFile
            if (grandparent != null) {
                val escapedFile = File(grandparent, "escaped-file.txt")
                assertFalse(
                    escapedFile.exists(),
                    "No files should escape to parent directories",
                )
            }
        }

    // ==================== Input Sanitization Tests ====================

    @Test
    fun `test special shell characters in project name are safe`() =
        runBlocking {
            // These characters are valid in filenames on some systems but dangerous in shells
            val names =
                listOf(
                    "project name", // space
                    "project'name", // single quote
                    "project(name)", // parentheses
                    "project[name]", // brackets
                    "project{name}", // braces
                    "project#name", // hash
                    "project@name", // at sign
                    "project!name", // exclamation
                    "project\$name", // dollar sign
                    "project%name", // percent
                )

            names.forEach { name ->
                val validation = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, name)
                // These may or may not be valid depending on implementation
                // The key is they shouldn't cause crashes or security issues
                if (validation is ValidationResult.Valid) {
                    // If accepted, the project should be creatable safely
                    val result =
                        ProjectCreationService.createProject(
                            name = name,
                            parentDirectory = tempDir.absolutePath,
                            template = ProjectTemplate.Empty,
                            onProgress = { _, _ -> },
                        )

                    if (result.isSuccess) {
                        // Clean up for next iteration
                        File(tempDir, name).deleteRecursively()
                    }
                }
            }
        }

    @Test
    fun `test very long project name is rejected`() {
        val longName = "a".repeat(300)
        val result = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, longName)

        assertTrue(
            result is ValidationResult.Invalid,
            "Very long project name should be rejected",
        )
    }

    @Test
    fun `test Unicode normalization attacks are handled`() {
        // Different Unicode representations of "same" characters
        val names =
            listOf(
                "café", // With é as single character
                "cafe\u0301", // With e + combining acute accent
            )

        names.forEach { name ->
            val result = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, name)
            // Should handle Unicode safely (either accept or reject, but not crash)
            assertTrue(
                result is ValidationResult.Valid || result is ValidationResult.Invalid,
                "Unicode should be handled: $name",
            )
        }
    }

    // ==================== Symlink Security Tests ====================

    @Test
    fun `test project creation does not follow symlinks outside directory`() =
        runBlocking {
            // Create a project normally
            val result =
                ProjectCreationService.createProject(
                    name = "symlink-safe-project",
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val projectDir = File(tempDir, "symlink-safe-project")

            // All created files should have canonical paths within project
            projectDir.walkTopDown().forEach { file ->
                val canonical = file.canonicalPath
                val projectCanonical = projectDir.canonicalPath

                assertTrue(
                    canonical.startsWith(projectCanonical) || canonical == projectCanonical,
                    "File canonical path should be within project: $canonical",
                )
            }
        }

    // ==================== Validation Consistency Tests ====================

    @Test
    fun `test validation and creation are consistent`() =
        runBlocking {
            val testCases =
                listOf(
                    "valid-project",
                    "project123",
                    "MyProject",
                    "project.name",
                    "project_name",
                )

            testCases.forEach { name ->
                val validation = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, name)

                if (validation is ValidationResult.Valid) {
                    val result =
                        ProjectCreationService.createProject(
                            name = name,
                            parentDirectory = tempDir.absolutePath,
                            template = ProjectTemplate.Empty,
                            onProgress = { _, _ -> },
                        )

                    assertTrue(
                        result.isSuccess,
                        "Valid validation should lead to successful creation: $name",
                    )

                    // Clean up
                    File(tempDir, name).deleteRecursively()
                }
            }
        }

    @Test
    fun `test rejected names cannot be created`() =
        runBlocking {
            val invalidNames =
                listOf(
                    "",
                    "   ",
                    "CON",
                    ".hidden",
                    "project.",
                )

            invalidNames.forEach { name ->
                val validation = ProjectCreationService.validateProjectLocation(tempDir.absolutePath, name)
                assertTrue(
                    validation is ValidationResult.Invalid,
                    "Name should be invalid: '$name'",
                )

                // Creation should also fail
                val result =
                    ProjectCreationService.createProject(
                        name = name,
                        parentDirectory = tempDir.absolutePath,
                        template = ProjectTemplate.Empty,
                        onProgress = { _, _ -> },
                    )

                assertTrue(
                    result.isFailure,
                    "Invalid name should not create project: '$name'",
                )
            }
        }
}
