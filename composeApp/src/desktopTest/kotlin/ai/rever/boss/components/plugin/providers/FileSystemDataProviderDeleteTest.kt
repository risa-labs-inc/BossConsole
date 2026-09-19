package ai.rever.boss.components.plugin.providers

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemDataProviderDeleteTest {
    @Test
    fun `delete refuses the user home directory itself`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val canary = File(home, "keep.txt").apply { writeText("keep") }

            val result = deleteUserPath(home, home)

            assertIs<SecurityException>(result.exceptionOrNull())
            assertEquals("keep", canary.readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes an ordinary descendant tree`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val target = File(home, "workspace").apply { mkdirs() }
            File(target, "nested/file.txt").apply {
                parentFile.mkdirs()
                writeText("delete")
            }

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertTrue(home.isDirectory)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes a nested symlink without touching its target`() {
        val root = createTempDirectory("filesystem-provider-symlink").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val target = File(home, "workspace").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canary = File(outside, "keep.txt").apply { writeText("keep") }
            val link = File(target, "external")
            if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertEquals("keep", canary.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
