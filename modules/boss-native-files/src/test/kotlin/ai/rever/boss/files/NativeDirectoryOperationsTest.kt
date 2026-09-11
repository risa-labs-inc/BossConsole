package ai.rever.boss.files

import java.nio.ByteBuffer
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDirectoryOperationsTest {
    @Test
    fun `ordinary writable opens preserve content and private defaults remain private`() =
        fixture { root ->
            Files.writeString(root.resolve("ordinary"), "original")
            NativeDirectory.open(root).use { directory ->
                directory.file("ordinary", writable = true, permissions = CreationPermissions.INHERIT).use { file ->
                    file.truncate(0)
                    file.write(ByteBuffer.wrap("changed".toByteArray()))
                }
                directory.file("private", create = true).close()
            }
            assertEquals("changed", Files.readString(root.resolve("ordinary")))
            if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
                assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                        .toString(Files.getPosixFilePermissions(root.resolve("private"))),
                )
            }
        }

    @Test
    fun `copy fallback preserves files timestamps empty directories and symbolic entries`() =
        fixture { root ->
            val from = Files.createDirectory(root.resolve("from"))
            val to = Files.createDirectory(root.resolve("to"))
            val source = Files.writeString(from.resolve("file"), "content")
            val modified = FileTime.fromMillis(1_750_000_000_123)
            Files.setLastModifiedTime(source, modified)
            Files.createDirectory(from.resolve("empty"))
            Files.createDirectory(from.resolve("full"))
            Files.writeString(from.resolve("full/child"), "survives")
            Files.createSymbolicLink(from.resolve("link"), Path.of("file"))
            NativeDirectory.open(from).use { parent ->
                NativeDirectory.open(to).use { destination ->
                    parent.copyEntry("file", destination, "file")
                    parent.copyEntry("empty", destination, "empty")
                    parent.copyEntry("link", destination, "link")
                    assertFailsWith<DirectoryNotEmptyException> { parent.copyEntry("full", destination, "full") }
                    assertTrue(checkNotNull(destination.info("link")).isLink)
                    assertTrue(checkNotNull(destination.info("empty")).isDirectory)
                }
            }
            assertEquals("content", Files.readString(to.resolve("file")))
            assertEquals(modified, Files.getLastModifiedTime(to.resolve("file")))
            assertEquals(Path.of("file"), Files.readSymbolicLink(to.resolve("link")))
            assertFalse(Files.exists(to.resolve("full")))
            assertEquals("survives", Files.readString(from.resolve("full/child")))
        }

    @Test
    fun `watch observes held directory after pathname replacement and precise file changes`() =
        fixture { root ->
            val directory = Files.createDirectory(root.resolve("directory"))
            val moved = root.resolve("moved")
            val replacement = Files.createDirectory(root.resolve("replacement"))
            NativeDirectory.open(directory).use { held ->
                held.watch().use { watch ->
                    Files.move(directory, moved)
                    Files.createSymbolicLink(directory, replacement)
                    Files.writeString(replacement.resolve("secret"), "invisible")
                    Files.writeString(moved.resolve("file"), "one")
                    await(watch, "file", DirectoryChange.Kind.CREATED)
                    Files.writeString(moved.resolve("file"), "two")
                    await(watch, "file", DirectoryChange.Kind.MODIFIED)
                    Files.delete(moved.resolve("file"))
                    await(watch, "file", DirectoryChange.Kind.DELETED)
                }
            }
            assertEquals("invisible", Files.readString(replacement.resolve("secret")))
        }

    @Test
    fun `shared watch session delivers each directory events independently`() =
        fixture { root ->
            val first = Files.createDirectory(root.resolve("first"))
            val second = Files.createDirectory(root.resolve("second"))
            DirectoryWatchSession().use { session ->
                NativeDirectory.open(first).use { one ->
                    NativeDirectory.open(second).use { two ->
                        one.watch(session).use { firstWatch ->
                            two.watch(session).use { secondWatch ->
                                Files.writeString(first.resolve("one"), "one")
                                Files.writeString(second.resolve("two"), "two")
                                await(firstWatch, "one", DirectoryChange.Kind.CREATED)
                                await(secondWatch, "two", DirectoryChange.Kind.CREATED)
                            }
                        }
                    }
                }
            }
        }

    private fun await(
        watch: NativeDirectoryWatch,
        name: String,
        kind: DirectoryChange.Kind,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000
        var found = false
        while (!found && System.nanoTime() < deadline) {
            val changes = watch.poll()
            assertTrue(changes.valid)
            assertFalse(changes.events.any { it.name == "secret" }, "Watch followed a replacement path")
            found = changes.events.any { it.name == name && it.kind == kind }
            if (!found) Thread.sleep(10)
        }
        assertTrue(found, "Missing $kind event for $name")
    }

    private fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("native-operations-").toRealPath()
        try {
            test(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
