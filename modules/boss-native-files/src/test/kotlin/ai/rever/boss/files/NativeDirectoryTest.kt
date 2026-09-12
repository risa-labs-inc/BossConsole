package ai.rever.boss.files

import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDirectoryTest {
    @Test
    fun `Windows junction entries can be removed without traversing their target`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("native-junction-").toRealPath()
        try {
            val outside = Files.createDirectory(root.resolve("outside"))
            val inside = Files.createDirectory(root.resolve("inside"))
            Files.writeString(outside.resolve("sentinel"), "unchanged")
            val script =
                "\$ErrorActionPreference='Stop'; " +
                    "New-Item -ItemType Junction -Path \$env:NATIVE_TEST_LINK " +
                    "-Target \$env:NATIVE_TEST_TARGET | Out-Null"
            val builder = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            builder.environment()["NATIVE_TEST_LINK"] = inside.resolve("junction").toString()
            builder.environment()["NATIVE_TEST_TARGET"] = outside.toString()
            val process = builder.start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Junction creation timed out")
                assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
            } finally {
                process.destroyForcibly()
            }
            NativeDirectory.open(inside).use { directory ->
                assertTrue(checkNotNull(directory.info("junction")).isLink)
                assertFails { directory.child("junction") }
                directory.delete("junction")
                assertEquals("unchanged", Files.readString(outside.resolve("sentinel")))
                assertFalse(Files.exists(inside.resolve("junction")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `platform temporary directories work without caller canonicalization`() {
        val root = Files.createTempDirectory("native-directory-alias-")
        try {
            NativeDirectory.open(root).use { directory ->
                directory.file("file", create = true).use { it.write(ByteBuffer.wrap(byteArrayOf(1))) }
            }
            assertEquals(1L, Files.size(root.resolve("file")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `held directory cannot be redirected by replacing its pathname with a link`() {
        val root = Files.createTempDirectory("native-directory-").toRealPath()
        try {
            val inside = Files.createDirectory(root.resolve("inside"))
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("sentinel"), "unchanged")
            NativeDirectory.open(inside).use { directory ->
                Files.move(inside, root.resolve("moved"))
                Files.createSymbolicLink(inside, outside)
                directory.file("new", create = true).use { it.write(ByteBuffer.wrap("safe".toByteArray())) }
                directory.move("new", directory, "renamed", overwrite = false)
                directory.child("child", create = true).close()
                directory.delete("renamed")
                assertFalse(Files.exists(outside.resolve("new")))
                assertFalse(Files.exists(outside.resolve("child")))
                assertTrue(Files.isDirectory(root.resolve("moved/child")))
                assertEquals("unchanged", Files.readString(outside.resolve("sentinel")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ordinary files metadata enumeration and atomic non-overwriting moves work`() {
        val root = Files.createTempDirectory("native-directory-").toRealPath()
        try {
            NativeDirectory.open(root).use { directory ->
                directory.restrictToOwner()
                directory.file("file", create = true).use {
                    it.write(ByteBuffer.wrap("hello".toByteArray()))
                    assertEquals(5L, it.size())
                    it.position(0)
                    val bytes = ByteBuffer.allocate(5)
                    assertEquals(5, it.read(bytes))
                    assertEquals("hello", String(bytes.array()))
                }
                directory.file("other", create = true).close()
                assertFailsWith<FileAlreadyExistsException> { directory.move("file", directory, "other", false) }
                assertEquals("hello", Files.readString(root.resolve("file")))
                val info = checkNotNull(directory.info("file"))
                assertEquals(5L, info.size)
                assertTrue(info.isRegularFile)
                assertFalse(info.isDirectory)
                assertTrue(info.modifiedMillis > 1_700_000_000_000)
                val names = mutableListOf<String>()
                directory.entries {
                    names.add(it)
                    true
                }
                assertEquals(setOf("file", "other"), names.toSet())
                if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
                    val actualPermissions = Files.getPosixFilePermissions(root.resolve("file"))
                    assertEquals(PosixFilePermissions.fromString("rw-------"), actualPermissions)
                }
                directory.move("file", directory, "other", true)
                assertEquals("hello", Files.readString(root.resolve("other")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `links cannot be opened but can be inspected renamed and unlinked`() {
        val root = Files.createTempDirectory("native-directory-").toRealPath()
        try {
            val target = Files.writeString(root.resolve("target"), "sentinel")
            Files.createSymbolicLink(root.resolve("link"), target)
            NativeDirectory.open(root).use { directory ->
                assertTrue(checkNotNull(directory.info("link")).isLink)
                assertFails { directory.file("link") }
                assertFails { directory.file("link", create = true) }
                assertFails { directory.child("link") }
                assertFails { directory.file("../escape", create = true) }
                directory.move("link", directory, "moved-link", false)
                directory.delete("moved-link")
                assertEquals("sentinel", Files.readString(target))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
