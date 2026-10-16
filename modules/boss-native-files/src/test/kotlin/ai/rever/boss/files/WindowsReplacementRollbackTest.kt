package ai.rever.boss.files

import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsReplacementRollbackTest {
    @Test
    fun `failed source move restores the staged destination link without touching either target`() {
        assumeTrue(Platform.isWindows())
        val root = Files.createTempDirectory("native-rollback-").toRealPath()
        try {
            val target = Files.createDirectory(root.resolve("target"))
            Files.writeString(target.resolve("sentinel"), "unchanged")
            val source = Files.writeString(root.resolve("source"), "source bytes")
            val link = root.resolve("destination")
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: IOException) {
                assumeTrue("Windows symbolic-link fixture requires permission", false)
            }
            rollback(root, source, link)
            assertEquals(target, Files.readSymbolicLink(link))
            assertEquals("unchanged", Files.readString(target.resolve("sentinel")))
            assertEquals("source bytes", Files.readString(source))
            assertFalse(hasBackup(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun rollback(
        root: Path,
        source: Path,
        link: Path,
    ) {
        val handles = mutableListOf<Pointer>()
        try {
            val parent = open(root).also(handles::add)
            val from = open(source).also(handles::add)
            val destination = open(link).also(handles::add)
            val failure =
                assertFailsWith<IOException> {
                    WindowsReplacement.move(from, destination, parent, "destination") {
                        assertFalse(Files.exists(link))
                        assertTrue(hasBackup(root))
                        throw IOException("injected source move failure after real destination staging")
                    }
                }
            assertTrue(failure.message.orEmpty().contains("injected source move"))
        } finally {
            handles.asReversed().forEach(WindowsApi::close)
        }
    }

    private fun hasBackup(root: Path): Boolean =
        Files.list(root).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith(".boss-replace-") }
        }

    private fun open(path: Path): Pointer {
        val handle =
            WindowsApi.kernel.getFunction("CreateFileW").invokePointer(
                arrayOf<Any?>(WString(path.toString()), 0x10080, 7, null, 3, 0x02200000, null),
            )
        if (handle == null || Pointer.nativeValue(handle) == -1L) throw WindowsApi.error("Open rollback fixture")
        return handle
    }
}
