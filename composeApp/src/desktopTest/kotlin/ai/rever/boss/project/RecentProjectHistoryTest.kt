package ai.rever.boss.project

import ai.rever.boss.window.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentProjectHistoryTest {
    private fun project(name: String) = Project(name = name, path = "/temporary/$name")

    @Test
    fun `delayed seed replays removal and add in request order`() =
        runTest {
            val seed = CompletableDeferred<List<Project>>()
            val writes = mutableListOf<List<Project>>()
            val history = RecentProjectHistory(backgroundScope, { seed.await() }, { writes += it })
            val old = project("old")
            val keep = project("keep")
            val added = project("added")
            runCurrent()
            history.removeRecentProject(old.path)
            history.updateRecentProjects(added)
            history.removeRecentProject(added.path)
            runCurrent()
            assertEquals(emptyList(), writes, "never overwrite the unread disk seed")
            seed.complete(listOf(old, keep))
            runCurrent()
            assertEquals(listOf(keep), history.recentProjects.value)
            assertEquals(listOf(keep), writes.last())
        }

    @Test
    fun `remove then reopen during load keeps most recent ordering`() =
        runTest {
            val seed = CompletableDeferred<List<Project>>()
            val writes = mutableListOf<List<Project>>()
            val history = RecentProjectHistory(backgroundScope, { seed.await() }, { writes += it })
            val old = project("old")
            val keep = project("keep")
            history.removeRecentProject(old.path)
            history.updateRecentProjects(old)
            seed.complete(listOf(keep, old))
            runCurrent()
            assertEquals(listOf(old.path, keep.path), history.recentProjects.value.map { it.path })
            assertEquals(history.recentProjects.value, writes.last())
        }

    @Test
    fun `concurrent window callbacks retain bounded unique history and persist valid JSON`() =
        runTest {
            val directory = Files.createTempDirectory("recent-project-history-test").toFile()
            try {
                val file = directory.resolve("recent-projects.json")
                val seed = CompletableDeferred<List<Project>>()
                val history =
                    RecentProjectHistory(backgroundScope, { seed.await() }, {
                        file.writeText(Json.encodeToString(it))
                    })
                (0 until 30)
                    .map { index ->
                        async(Dispatchers.Default) {
                            val selected = project("project-${index % 15}")
                            history.updateRecentProjects(selected)
                            history.removeRecentProject(selected.path)
                            history.updateRecentProjects(selected)
                        }
                    }.awaitAll()
                history.updateRecentProjects(project("final"))
                seed.complete(listOf(project("seed")))
                runCurrent()
                val finalHistory = history.recentProjects.value
                assertEquals("final", finalHistory.first().name)
                assertEquals(10, finalHistory.size)
                assertEquals(finalHistory.size, finalHistory.map { it.path }.distinct().size)
                assertEquals(finalHistory, Json.decodeFromString<List<Project>>(file.readText()))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `failed initial load does not overwrite the existing file`() =
        runTest {
            val directory = Files.createTempDirectory("recent-project-load-failure-test").toFile()
            try {
                val file = directory.resolve("recent-projects.json")
                val original = "unreadable history retained for recovery"
                file.writeText(original)
                val history =
                    RecentProjectHistory(backgroundScope, { null }, {
                        file.writeText(Json.encodeToString(it))
                    })
                runCurrent()
                assertEquals(emptyList(), history.recentProjects.value)
                assertEquals(original, file.readText())
                history.updateRecentProjects(project("new"))
                runCurrent()
                assertEquals(history.recentProjects.value, Json.decodeFromString<List<Project>>(file.readText()))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `adding during load retains the seed and promotes the new project`() =
        runTest {
            val seed = CompletableDeferred<List<Project>>()
            val history = RecentProjectHistory(backgroundScope, { seed.await() }, {})
            val old = project("old")
            val added = project("added")
            history.updateRecentProjects(added)
            assertEquals(listOf(added.path), history.recentProjects.value.map { it.path })
            seed.complete(listOf(old))
            runCurrent()
            assertEquals(listOf(added.path, old.path), history.recentProjects.value.map { it.path })
        }

    @Test
    fun `writes never overlap or finish in reverse order`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<List<Project>>()
            var active = 0
            var maximum = 0
            val history =
                RecentProjectHistory(backgroundScope, { emptyList() }, {
                    active++
                    maximum = maxOf(maximum, active)
                    release.await()
                    writes += it
                    active--
                })
            runCurrent()
            history.updateRecentProjects(project("first"))
            runCurrent()
            history.updateRecentProjects(project("last"))
            runCurrent()
            assertEquals(1, maximum)
            release.complete(Unit)
            runCurrent()
            assertEquals(history.recentProjects.value, writes.last())
        }
}
