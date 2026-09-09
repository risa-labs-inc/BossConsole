package ai.rever.boss.components.plugin.providers

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorFileWriterTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a partial write failure preserves the previous file and removes temporary output`() {
        val target = directory.resolve("document.txt").toFile().apply { writeText("irreplaceable original") }
        val writer = failingWriter()

        assertFailsWith<IOException> { writer.write(target.path, "replacement") }

        assertEquals("irreplaceable original", target.readText())
        assertEquals(listOf("document.txt"), directory.toFile().list()!!.toList())
    }

    @Test
    fun `guarded staged failures preserve the original and clean up for IO and stack errors`() {
        val target = directory.resolve("guarded.txt").toFile().apply { writeText("original") }
        for (failure in listOf(IOException("disk full"), StackOverflowError("write stack"))) {
            val writer =
                EditorFileWriter { output, _ ->
                    output.writeText("partial")
                    throw failure
                }

            assertFalse(
                guardedWrite(target.path, "replacement", reportFailure = { _, _, _ -> }) { file, text ->
                    writer.write(file.path, text)
                },
            )
            assertEquals("original", target.readText())
            assertEquals(listOf("guarded.txt"), directory.toFile().list()!!.toList())
        }
    }

    @Test
    fun `a failed first write does not leave a partial file behind`() {
        val target = directory.resolve("new.txt")

        assertFailsWith<IOException> { failingWriter().write(target.toString(), "replacement") }

        assertFalse(Files.exists(target))
        assertTrue(directory.toFile().list()!!.isEmpty())
    }

    @Test
    fun `readers see the old content until the new content is complete`() {
        val target = directory.resolve("document.txt").toFile().apply { writeText("old content") }
        val writer =
            EditorFileWriter { output, _ ->
                output.writeText("first half")
                assertEquals("old content", target.readText())
                output.appendText(" and second half")
            }

        writer.write(target.path, "unused")

        assertEquals("first half and second half", target.readText())
        assertEquals(listOf("document.txt"), directory.toFile().list()!!.toList())
    }

    @Test
    fun `overlapping saves produce one complete version without interleaved bytes`() {
        val target = directory.resolve("shared.txt").toFile().apply { writeText("original") }
        val bothStarted = CountDownLatch(2)
        val writer =
            EditorFileWriter { output, text ->
                output.writeText(text.take(4))
                bothStarted.countDown()
                check(bothStarted.await(10, TimeUnit.SECONDS))
                output.appendText(text.drop(4))
            }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val versions = listOf("AAAAAAAA", "BBBBBBBB")
            val jobs = versions.map { text -> pool.submit { writer.write(target.path, text) } }
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            assertTrue(target.readText() in versions)
            assertEquals(listOf("shared.txt"), directory.toFile().list()!!.toList())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the default writer creates parents and replaces all old bytes`() {
        val target = directory.resolve("nested/a.txt")
        val writer = EditorFileWriter()
        writer.write(target.toString(), "long original content")
        writer.write(target.toString(), "short \u03bb")
        assertEquals("short \u03bb", Files.readString(target))
        assertEquals(
            listOf("a.txt"),
            target.parent
                .toFile()
                .list()!!
                .toList(),
        )
    }

    @Test
    fun `a directory is never replaced by a saved file`() {
        val target = Files.createDirectory(directory.resolve("folder"))
        Files.writeString(target.resolve("keep.txt"), "keep")

        assertFailsWith<IOException> { EditorFileWriter().write(target.toString(), "new content") }

        assertEquals("keep", Files.readString(target.resolve("keep.txt")))
        assertEquals(listOf("folder"), directory.toFile().list()!!.toList())
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `saving through a symlink preserves the link and updates its target`() {
        val target = Files.writeString(directory.resolve("target.txt"), "old")
        val link = Files.createSymbolicLink(directory.resolve("link.txt"), target.fileName)

        EditorFileWriter().write(link.toString(), "new")

        assertTrue(Files.isSymbolicLink(link))
        assertEquals("new", Files.readString(target))
        assertEquals("new", Files.readString(link))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `a dangling symlink is never silently replaced`() {
        val missing = directory.resolve("missing.txt")
        val link = Files.createSymbolicLink(directory.resolve("link.txt"), missing.fileName)

        assertFailsWith<IOException> { EditorFileWriter().write(link.toString(), "new") }

        assertTrue(Files.isSymbolicLink(link))
        assertFalse(Files.exists(missing))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `private executable permissions survive replacement`() {
        val target = Files.writeString(directory.resolve("script.sh"), "old")
        val permissions = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(target, permissions)

        EditorFileWriter().write(target.toString(), "new")

        assertEquals(permissions, Files.getPosixFilePermissions(target))
    }

    @Test
    fun `read only files cannot be bypassed by replacing their directory entry`() {
        val target = Files.writeString(directory.resolve("readonly.txt"), "keep").toFile()
        assertTrue(target.setWritable(false, false))
        try {
            assumeFalse(Files.isWritable(target.toPath()), "Privileged users can still write read-only files")
            assertFailsWith<IOException> { EditorFileWriter().write(target.path, "new") }
            assertEquals("keep", target.readText())
            assertEquals(listOf("readonly.txt"), directory.toFile().list()!!.toList())
        } finally {
            target.setWritable(true, true)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `explicit Windows permissions survive replacement`() {
        val target = Files.writeString(directory.resolve("private.txt"), "old")
        val acl = Files.getFileAttributeView(target, AclFileAttributeView::class.java)
        val ownerOnly =
            AclEntry
                .newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(target))
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
        acl.acl = listOf(ownerOnly)
        val expected = acl.acl

        EditorFileWriter().write(target.toString(), "new")

        assertEquals(expected, Files.getFileAttributeView(target, AclFileAttributeView::class.java).acl)
        assertEquals("new", Files.readString(target))
    }

    private fun failingWriter() =
        EditorFileWriter { output: File, text: String ->
            output.writeText(text.take(3))
            throw IOException("Simulated disk-full error after partial output")
        }
}
