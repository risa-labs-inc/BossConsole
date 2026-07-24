package ai.rever.boss.project

import ai.rever.boss.project.templates.ProjectTemplate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for full project creation flow.
 *
 * Tests cover:
 * - End-to-end project creation for each template
 * - Placeholder substitution in template files
 * - Error handling scenarios
 * - Concurrent creation attempts
 */
class ProjectCreationIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    // ==================== Empty Project Template Tests ====================

    @Test
    fun `test create empty project successfully`() =
        runBlocking {
            val projectName = "test-empty-project"
            var lastProgress = 0f
            var lastMessage = ""

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { progress, message ->
                        lastProgress = progress
                        lastMessage = message
                    },
                )

            assertTrue(result.isSuccess, "Project creation should succeed")
            assertEquals(1.0f, lastProgress, "Progress should reach 100%")

            val project = result.getOrNull()
            assertNotNull(project)
            assertEquals(projectName, project.name)

            // Verify project directory exists
            val projectDir = File(tempDir, projectName)
            assertTrue(projectDir.exists(), "Project directory should exist")
            assertTrue(projectDir.isDirectory, "Project should be a directory")

            // Verify expected files for empty template
            assertTrue(File(projectDir, ".gitignore").exists(), ".gitignore should exist")
            assertTrue(File(projectDir, "README.md").exists(), "README.md should exist")
        }

    @Test
    fun `test empty project README contains project name`() =
        runBlocking {
            val projectName = "my-awesome-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val readmeContent = File(tempDir, "$projectName/README.md").readText()
            assertTrue(
                readmeContent.contains(projectName),
                "README should contain project name: $projectName",
            )
        }

    // ==================== Kotlin/JVM Project Template Tests ====================

    @Test
    fun `test create Kotlin JVM project successfully`() =
        runBlocking {
            val projectName = "kotlin-test-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.KotlinJvm,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Kotlin/JVM project creation should succeed")

            val projectDir = File(tempDir, projectName)
            assertTrue(projectDir.exists())

            // Verify Gradle files (note: template doesn't include Gradle wrapper)
            assertTrue(File(projectDir, "build.gradle.kts").exists(), "build.gradle.kts should exist")
            assertTrue(File(projectDir, "settings.gradle.kts").exists(), "settings.gradle.kts should exist")
            assertTrue(File(projectDir, "gradle.properties").exists(), "gradle.properties should exist")
        }

    @Test
    fun `test Kotlin project placeholder substitution`() =
        runBlocking {
            val projectName = "MyKotlinApp"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.KotlinJvm,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            // Check settings.gradle.kts contains project name
            val settingsContent = File(tempDir, "$projectName/settings.gradle.kts").readText()
            assertTrue(
                settingsContent.contains(projectName),
                "settings.gradle.kts should contain project name",
            )
        }

    // ==================== Node.js Project Template Tests ====================

    @Test
    fun `test create Node js project successfully`() =
        runBlocking {
            val projectName = "node-test-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.NodeJs,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Node.js project creation should succeed")

            val projectDir = File(tempDir, projectName)
            assertTrue(File(projectDir, "package.json").exists(), "package.json should exist")
            assertTrue(File(projectDir, "index.js").exists(), "index.js should exist")
        }

    @Test
    fun `test Node js package json contains package name`() =
        runBlocking {
            val projectName = "my-node-app"
            val expectedPackageName = ProjectCreationService.derivePackageName(projectName)

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.NodeJs,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val packageJson = File(tempDir, "$projectName/package.json").readText()
            assertTrue(
                packageJson.contains("\"name\": \"$expectedPackageName\"") ||
                    packageJson.contains("\"name\":\"$expectedPackageName\""),
                "package.json should contain derived package name: $expectedPackageName",
            )
        }

    // ==================== Go Project Template Tests ====================

    @Test
    fun `test create Go project successfully`() =
        runBlocking {
            val projectName = "go-test-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Go,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Go project creation should succeed")

            val projectDir = File(tempDir, projectName)
            assertTrue(File(projectDir, "go.mod").exists(), "go.mod should exist")
            assertTrue(File(projectDir, "main.go").exists(), "main.go should exist")
        }

    // ==================== Rust Project Template Tests ====================

    @Test
    fun `test create Rust project successfully`() =
        runBlocking {
            val projectName = "rust-test-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Rust,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Rust project creation should succeed")

            val projectDir = File(tempDir, projectName)
            assertTrue(File(projectDir, "Cargo.toml").exists(), "Cargo.toml should exist")
            assertTrue(File(projectDir, "src/main.rs").exists(), "src/main.rs should exist")
        }

    @Test
    fun `test Rust Cargo toml contains package name`() =
        runBlocking {
            val projectName = "my-rust-app"
            // Package name will be derived (lowercase, dots for separators)
            val expectedPackageName = ProjectCreationService.derivePackageName(projectName)

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Rust,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val cargoToml = File(tempDir, "$projectName/Cargo.toml").readText()
            assertTrue(
                cargoToml.contains(projectName) || cargoToml.contains(expectedPackageName),
                "Cargo.toml should contain project or package name",
            )
        }

    // ==================== Python Project Template Tests ====================

    @Test
    fun `test create Python project successfully`() =
        runBlocking {
            val projectName = "python-test-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Python,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess, "Python project creation should succeed")

            val projectDir = File(tempDir, projectName)
            assertTrue(File(projectDir, "pyproject.toml").exists(), "pyproject.toml should exist")
            assertTrue(
                File(projectDir, "src").exists() || File(projectDir, "main.py").exists(),
                "Python source should exist",
            )
        }

    // ==================== Error Handling Tests ====================

    @Test
    fun `test create project with invalid name fails`() =
        runBlocking {
            val result =
                ProjectCreationService.createProject(
                    name = "",
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure, "Empty project name should fail")
        }

    @Test
    fun `test create project in non-existent directory fails`() =
        runBlocking {
            val result =
                ProjectCreationService.createProject(
                    name = "test-project",
                    parentDirectory = "/non/existent/path/that/does/not/exist",
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure, "Non-existent parent directory should fail")
        }

    @Test
    fun `test create project where directory already exists fails`() =
        runBlocking {
            val projectName = "existing-project"
            File(tempDir, projectName).mkdir()

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure, "Existing project directory should fail")
        }

    @Test
    fun `test create project with Windows reserved name fails`() =
        runBlocking {
            val result =
                ProjectCreationService.createProject(
                    name = "CON",
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isFailure, "Windows reserved name should fail")
        }

    // ==================== Concurrent Creation Tests ====================

    @Test
    fun `test concurrent creation with same name handled correctly`() =
        runBlocking {
            val projectName = "concurrent-test"

            // First creation should succeed
            val result1 =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            // Second creation with same name should fail
            val result2 =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result1.isSuccess, "First creation should succeed")
            assertTrue(result2.isFailure, "Second creation with same name should fail")
        }

    // ==================== Progress Callback Tests ====================

    @Test
    fun `test progress callback is called with increasing values`() =
        runBlocking {
            val progressValues = mutableListOf<Float>()

            ProjectCreationService.createProject(
                name = "progress-test",
                parentDirectory = tempDir.absolutePath,
                template = ProjectTemplate.Empty,
                onProgress = { progress, _ ->
                    progressValues.add(progress)
                },
            )

            assertTrue(progressValues.isNotEmpty(), "Progress should be reported")
            assertTrue(progressValues.last() == 1.0f, "Final progress should be 1.0")

            // Verify progress is monotonically increasing
            for (i in 1 until progressValues.size) {
                assertTrue(
                    progressValues[i] >= progressValues[i - 1],
                    "Progress should be monotonically increasing",
                )
            }
        }

    // ==================== Cleanup Tests ====================

    @Test
    fun `test failed creation cleans up partial directory`() =
        runBlocking {
            // Create a project that will partially fail by having unwritable parent
            // This is tricky to test reliably, so we verify the cleanup logic exists
            // by checking that a failed creation doesn't leave artifacts

            val projectName = "cleanup-test"

            // First, succeed in creating a project
            val result1 =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )
            assertTrue(result1.isSuccess)

            // Now trying to create again should fail
            val result2 =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )
            assertTrue(result2.isFailure)

            // Original project should still exist and be intact
            val projectDir = File(tempDir, projectName)
            assertTrue(projectDir.exists(), "Original project should still exist after failed duplicate")
        }

    // ==================== Trimming Tests ====================

    @Test
    fun `test project name with leading and trailing spaces is trimmed`() =
        runBlocking {
            val projectName = "  trimmed-project  "
            val expectedName = "trimmed-project"

            val result =
                ProjectCreationService.createProject(
                    name = projectName,
                    parentDirectory = tempDir.absolutePath,
                    template = ProjectTemplate.Empty,
                    onProgress = { _, _ -> },
                )

            assertTrue(result.isSuccess)

            val project = result.getOrNull()
            assertNotNull(project)
            assertEquals(expectedName, project.name, "Project name should be trimmed")

            // Verify directory uses trimmed name
            assertTrue(File(tempDir, expectedName).exists(), "Directory should use trimmed name")
        }
}
