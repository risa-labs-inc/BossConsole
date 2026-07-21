package ai.rever.boss.plugin.icons

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for the plugin-icons module.
 *
 * Tests cover:
 * - FileIcons.forFile() with various extensions
 * - FileIcons.forSpecialFileName() for special cases
 * - FileIcons.forFolder() for folder icons
 *
 * Note: PathUtils tests are in plugin-path-utils module.
 */
class FileIconsTest {

    @Nested
    inner class FileIconsForFileTests {

        @Test
        fun `returns correct icon for Kotlin files`() {
            val result = FileIcons.forFile("MyClass.kt")
            assertEquals(LanguageIcons.kotlin, result.icon)
            assertEquals(LanguageIcons.Colors.kotlin, result.color)
        }

        @Test
        fun `returns correct icon for Kotlin script files`() {
            val result = FileIcons.forFile("build.gradle.kts")
            assertEquals(LanguageIcons.gradle, result.icon)
            assertEquals(LanguageIcons.Colors.gradle, result.color)
        }

        @Test
        fun `returns correct icon for Java files`() {
            val result = FileIcons.forFile("Main.java")
            assertEquals(LanguageIcons.java, result.icon)
            assertEquals(LanguageIcons.Colors.java, result.color)
        }

        @Test
        fun `returns correct icon for Python files`() {
            val result = FileIcons.forFile("script.py")
            assertEquals(LanguageIcons.python, result.icon)
            assertEquals(LanguageIcons.Colors.python, result.color)
        }

        @Test
        fun `returns correct icon for TypeScript files`() {
            val result = FileIcons.forFile("app.ts")
            assertEquals(LanguageIcons.typescript, result.icon)
            assertEquals(LanguageIcons.Colors.typescript, result.color)
        }

        @Test
        fun `returns correct icon for JavaScript files`() {
            val result = FileIcons.forFile("index.js")
            assertEquals(LanguageIcons.javascript, result.icon)
            assertEquals(LanguageIcons.Colors.javascript, result.color)
        }

        @Test
        fun `returns correct icon for Go files`() {
            val result = FileIcons.forFile("main.go")
            assertEquals(LanguageIcons.go, result.icon)
            assertEquals(LanguageIcons.Colors.go, result.color)
        }

        @Test
        fun `returns correct icon for Rust files`() {
            val result = FileIcons.forFile("lib.rs")
            assertEquals(LanguageIcons.rust, result.icon)
            assertEquals(LanguageIcons.Colors.rust, result.color)
        }

        @Test
        fun `returns correct icon for shell scripts`() {
            val result = FileIcons.forFile("setup.sh")
            assertEquals(LanguageIcons.gnubash, result.icon)
            assertEquals(LanguageIcons.Colors.gnubash, result.color)
        }

        @Test
        fun `returns correct icon for JSON files`() {
            val result = FileIcons.forFile("config.json")
            assertEquals(LanguageIcons.json, result.icon)
            assertEquals(LanguageIcons.Colors.json, result.color)
        }

        @Test
        fun `returns correct icon for YAML files`() {
            val result = FileIcons.forFile("config.yaml")
            assertEquals(LanguageIcons.yaml, result.icon)
            assertEquals(LanguageIcons.Colors.yaml, result.color)

            val resultYml = FileIcons.forFile("config.yml")
            assertEquals(LanguageIcons.yaml, resultYml.icon)
        }

        @Test
        fun `returns correct icon for Markdown files`() {
            val result = FileIcons.forFile("README.md")
            assertEquals(LanguageIcons.markdown, result.icon)
            assertEquals(LanguageIcons.Colors.markdown, result.color)
        }

        @Test
        fun `returns correct icon for HTML files`() {
            val result = FileIcons.forFile("index.html")
            assertEquals(LanguageIcons.html, result.icon)
            assertEquals(LanguageIcons.Colors.html, result.color)
        }

        @Test
        fun `returns correct icon for CSS files`() {
            val result = FileIcons.forFile("styles.css")
            assertEquals(LanguageIcons.css, result.icon)
            assertEquals(LanguageIcons.Colors.css, result.color)
        }

        @Test
        fun `returns default icon for unknown extensions`() {
            val result = FileIcons.forFile("data.xyz")
            assertEquals(FileIcons.file, result.icon)
            assertEquals(FileIcons.Colors.unknown, result.color)
        }

        @Test
        fun `handles case-insensitive extensions`() {
            val lower = FileIcons.forFile("file.KT")
            val upper = FileIcons.forFile("file.kt")
            assertEquals(lower.icon, upper.icon)
            assertEquals(lower.color, upper.color)
        }

        @Test
        fun `handles paths with directories`() {
            val result = FileIcons.forFile("/path/to/MyClass.kt")
            assertEquals(LanguageIcons.kotlin, result.icon)
        }
    }

    @Nested
    inner class SpecialFileNameTests {

        @Test
        fun `returns Docker icon for Dockerfile`() {
            val result = FileIcons.forFile("Dockerfile")
            assertEquals(LanguageIcons.docker, result.icon)
            assertEquals(LanguageIcons.Colors.docker, result.color)
        }

        @Test
        fun `returns Docker icon for Dockerfile variants`() {
            val result = FileIcons.forFile("Dockerfile.dev")
            assertEquals(LanguageIcons.docker, result.icon)
        }

        @Test
        fun `returns Docker icon for docker-compose files`() {
            assertEquals(LanguageIcons.docker, FileIcons.forFile("docker-compose.yml").icon)
            assertEquals(LanguageIcons.docker, FileIcons.forFile("docker-compose.yaml").icon)
            assertEquals(LanguageIcons.docker, FileIcons.forFile("compose.yml").icon)
            assertEquals(LanguageIcons.docker, FileIcons.forFile("compose.yaml").icon)
        }

        @Test
        fun `returns npm icon for package json`() {
            val result = FileIcons.forFile("package.json")
            assertEquals(LanguageIcons.npm, result.icon)
            assertEquals(LanguageIcons.Colors.npm, result.color)
        }

        @Test
        fun `returns npm icon for package-lock json`() {
            val result = FileIcons.forFile("package-lock.json")
            assertEquals(LanguageIcons.npm, result.icon)
        }

        @Test
        fun `returns yarn icon for yarn lock`() {
            val result = FileIcons.forFile("yarn.lock")
            assertEquals(LanguageIcons.yarn, result.icon)
            assertEquals(LanguageIcons.Colors.yarn, result.color)
        }

        @Test
        fun `returns cargo icon for Cargo toml`() {
            val result = FileIcons.forFile("Cargo.toml")
            assertEquals(LanguageIcons.cargo, result.icon)
            assertEquals(LanguageIcons.Colors.rust, result.color)
        }

        @Test
        fun `returns Go icon for go mod`() {
            val result = FileIcons.forFile("go.mod")
            assertEquals(LanguageIcons.go, result.icon)
        }

        @Test
        fun `returns pip icon for requirements txt`() {
            val result = FileIcons.forFile("requirements.txt")
            assertEquals(LanguageIcons.pip, result.icon)
        }

        @Test
        fun `returns Gradle icon for build gradle files`() {
            assertEquals(LanguageIcons.gradle, FileIcons.forFile("build.gradle").icon)
            assertEquals(LanguageIcons.gradle, FileIcons.forFile("build.gradle.kts").icon)
            assertEquals(LanguageIcons.gradle, FileIcons.forFile("settings.gradle").icon)
            assertEquals(LanguageIcons.gradle, FileIcons.forFile("settings.gradle.kts").icon)
            assertEquals(LanguageIcons.gradle, FileIcons.forFile("gradle.properties").icon)
        }

        @Test
        fun `returns Maven icon for pom xml`() {
            val result = FileIcons.forFile("pom.xml")
            assertEquals(LanguageIcons.maven, result.icon)
        }

        @Test
        fun `returns Git icon for gitignore`() {
            val result = FileIcons.forFile(".gitignore")
            assertEquals(LanguageIcons.git, result.icon)
            assertEquals(LanguageIcons.Colors.git, result.color)
        }

        @Test
        fun `returns lock icon for env files`() {
            assertEquals(FileIcons.lock, FileIcons.forFile(".env").icon)
            assertEquals(FileIcons.lock, FileIcons.forFile(".env.local").icon)
            assertEquals(FileIcons.lock, FileIcons.forFile(".env.production").icon)
        }

        @Test
        fun `returns lock icon for key and pem files`() {
            assertEquals(FileIcons.lock, FileIcons.forFile("private.key").icon)
            assertEquals(FileIcons.lock, FileIcons.forFile("certificate.pem").icon)
        }

        @Test
        fun `returns Kubernetes icon for k8s yaml files`() {
            val result = FileIcons.forFile("deployment.k8s.yaml")
            assertEquals(LanguageIcons.kubernetes, result.icon)
        }
    }

    @Nested
    inner class FolderIconTests {

        @Test
        fun `returns closed folder icon when not expanded`() {
            val result = FileIcons.forFolder(isExpanded = false)
            assertEquals(FileIcons.folder, result.icon)
            assertEquals(FileIcons.Colors.folder, result.color)
        }

        @Test
        fun `returns open folder icon when expanded`() {
            val result = FileIcons.forFolder(isExpanded = true)
            assertEquals(FileIcons.folderOpen, result.icon)
            assertEquals(FileIcons.Colors.folder, result.color)
        }

        @Test
        fun `default folder is not expanded`() {
            val result = FileIcons.forFolder()
            assertEquals(FileIcons.folder, result.icon)
        }
    }

    @Nested
    inner class MediaFileTests {

        @Test
        fun `returns image icon for image files`() {
            listOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").forEach { ext ->
                val result = FileIcons.forFile("image.$ext")
                assertEquals(FileIcons.image, result.icon, "Failed for extension: $ext")
                assertEquals(FileIcons.Colors.image, result.color, "Failed for extension: $ext")
            }
        }

        @Test
        fun `returns audio icon for audio files`() {
            listOf("mp3", "wav", "flac", "ogg", "aac").forEach { ext ->
                val result = FileIcons.forFile("audio.$ext")
                assertEquals(FileIcons.audio, result.icon, "Failed for extension: $ext")
            }
        }

        @Test
        fun `returns video icon for video files`() {
            listOf("mp4", "mkv", "avi", "mov", "webm").forEach { ext ->
                val result = FileIcons.forFile("video.$ext")
                assertEquals(FileIcons.video, result.icon, "Failed for extension: $ext")
            }
        }

        @Test
        fun `returns archive icon for archive files`() {
            listOf("zip", "tar", "gz", "rar", "7z").forEach { ext ->
                val result = FileIcons.forFile("archive.$ext")
                assertEquals(FileIcons.archive, result.icon, "Failed for extension: $ext")
            }
        }

        @Test
        fun `returns font icon for font files`() {
            listOf("ttf", "otf", "woff", "woff2").forEach { ext ->
                val result = FileIcons.forFile("font.$ext")
                assertEquals(FileIcons.font, result.icon, "Failed for extension: $ext")
            }
        }
    }
}
