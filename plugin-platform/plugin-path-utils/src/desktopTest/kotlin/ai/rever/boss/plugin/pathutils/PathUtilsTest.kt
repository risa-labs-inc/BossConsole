package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for PathUtils.
 */
class PathUtilsTest {
    @Nested
    inner class ExtractFileNameTests {
        @Test
        fun `handles Unix paths`() {
            assertEquals("file.txt", "/path/to/file.txt".extractFileName())
            assertEquals("script.sh", "/usr/local/bin/script.sh".extractFileName())
        }

        @Test
        fun `handles Windows paths`() {
            assertEquals("file.txt", "C:\\Users\\Documents\\file.txt".extractFileName())
            assertEquals("app.exe", "D:\\Program Files\\App\\app.exe".extractFileName())
        }

        @Test
        fun `handles mixed separators`() {
            assertEquals("file.txt", "C:/Users\\Documents/file.txt".extractFileName())
            assertEquals("config.yml", "/home/user\\projects/app/config.yml".extractFileName())
        }

        @Test
        fun `handles files without path`() {
            assertEquals("file.txt", "file.txt".extractFileName())
            assertEquals("Dockerfile", "Dockerfile".extractFileName())
        }

        @Test
        fun `handles edge cases`() {
            assertEquals("", "".extractFileName())
            assertEquals("", "/".extractFileName())
            assertEquals("", "\\".extractFileName())
            assertEquals("file", "file".extractFileName())
        }
    }

    @Nested
    inner class ExtractParentNameTests {
        @Test
        fun `handles Unix paths`() {
            assertEquals("to", "/path/to/file.txt".extractParentName())
            assertEquals("bin", "/usr/local/bin/script.sh".extractParentName())
        }

        @Test
        fun `handles Windows paths`() {
            assertEquals("Documents", "C:\\Users\\Documents\\file.txt".extractParentName())
            assertEquals("App", "D:\\Program Files\\App\\app.exe".extractParentName())
        }

        @Test
        fun `handles mixed separators`() {
            assertEquals("Documents", "C:/Users\\Documents/file.txt".extractParentName())
            assertEquals("app", "/home/user/projects/app/config.yml".extractParentName())
        }

        @Test
        fun `handles edge cases`() {
            assertEquals("", "".extractParentName())
            assertEquals("", "/file.txt".extractParentName())
            assertEquals("path", "path/file.txt".extractParentName())
        }
    }
}
