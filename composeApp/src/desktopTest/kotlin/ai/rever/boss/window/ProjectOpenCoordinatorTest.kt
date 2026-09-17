package ai.rever.boss.window

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectOpenCoordinatorTest {
    @Test
    fun `empty window opens directly and same project spelling does not reopen`() =
        withProjects { a, _ ->
            var current = Project("None", "")
            val opened = mutableListOf<Project>()
            val opener = ProjectOpenCoordinator({ current }, { opened += it }, { error("Unexpected new window") })
            opener.request(a)
            assertEquals(listOf(a), opened)
            current = a
            opener.request(a.copy(path = a.path + "/."))
            assertNull(opener.pendingProject)
            assertEquals(listOf(a), opened)
        }

    @Test
    fun `existing window asks and chosen destination alone receives project`() =
        withProjects { a, b ->
            val current = mutableListOf<Project>()
            val new = mutableListOf<Project>()
            val opener = ProjectOpenCoordinator({ a }, { current += it }, { new += it })
            opener.request(b)
            assertEquals(b, opener.pendingProject)
            assertTrue(current.isEmpty() && new.isEmpty())
            opener.confirmCurrent()
            assertEquals(listOf(b), current)
            assertTrue(new.isEmpty())
            opener.request(b)
            opener.confirmNew()
            assertEquals(listOf(b), new)
            assertEquals(listOf(b), current)
            opener.confirmNew()
            assertEquals(listOf(b), new)
        }

    @Test
    fun `missing directory or regular file never switches project`() =
        withProjects { a, b ->
            val opener = ProjectOpenCoordinator({ a }, { error("Unexpected switch") }, { error("Unexpected window") })
            val missing = b.copy(path = b.path + "/missing")
            opener.request(missing)
            assertEquals(missing, opener.unavailableProject)
            assertNull(opener.pendingProject)
            val file = java.io.File(b.path, "file.txt").apply { writeText("data") }
            opener.request(b.copy(path = file.path))
            assertEquals(file.path, opener.unavailableProject?.path)
            opener.dismiss()
            assertNull(opener.unavailableProject)
        }

    @Test
    fun `directory disappearing before either confirmation refuses the open`() =
        withProjects { a, b ->
            val opener = ProjectOpenCoordinator({ a }, { error("Unexpected switch") }, { error("Unexpected window") })
            opener.request(b)
            assertTrue(java.io.File(b.path).delete())
            opener.confirmCurrent()
            assertEquals(b, opener.unavailableProject)
            assertTrue(java.io.File(b.path).mkdir())
            opener.request(b)
            assertTrue(java.io.File(b.path).delete())
            opener.confirmNew()
            assertEquals(b, opener.unavailableProject)
            assertNull(opener.pendingProject)
        }

    @Test
    fun `cancel and replacement selection cannot open an old pending project`() =
        withProjects { a, b ->
            var current = a
            val opened = mutableListOf<Project>()
            val opener = ProjectOpenCoordinator({ current }, { opened += it }, { opened += it })
            opener.request(b)
            opener.dismiss()
            opener.confirmCurrent()
            assertTrue(opened.isEmpty())
            opener.request(b)
            current = b
            opener.confirmCurrent()
            assertTrue(opened.isEmpty())
            opener.request(a)
            opener.request(b)
            opener.confirmNew()
            assertTrue(opened.isEmpty())
        }

    private fun withProjects(block: (Project, Project) -> Unit) {
        val root = Files.createTempDirectory("project-open").toFile()
        try {
            val a = root.resolve("a").apply { mkdir() }
            val b = root.resolve("b").apply { mkdir() }
            block(Project("A", a.path), Project("B", b.path))
        } finally {
            root.deleteRecursively()
        }
    }
}
