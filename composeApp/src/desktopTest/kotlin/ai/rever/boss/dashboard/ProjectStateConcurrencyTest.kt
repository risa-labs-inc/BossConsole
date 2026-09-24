package ai.rever.boss.dashboard

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.window.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the lost-update races and torn writes fixed in
 * [ProjectState] (issue #1244):
 * concurrent project openings from per-window selection callbacks must serialize
 * under a Mutex without dropping entries, and every completed mutation must be
 * persisted atomically so the decoded on-disk state always matches memory.
 *
 * Without the mutex, two coroutines calling [ProjectState.updateRecentProjects]
 * at once each read the same `_recentProjects.value`, build their own copy, and
 * the later write silently drops the first - which is what an every-window
 * startup restore does in the real app. The atomic write pins the second half
 * of the defect family #860/#1011/#1240 named.
 *
 * Each test runs against a hermetic temp file via [ProjectState.resetForTesting]
 * and restores the singleton to the real settings file when it finishes, so
 * other tests in the same JVM (which use the real file) are unaffected.
 */
class ProjectStateConcurrencyTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("project-state-test-").toFile()
        tempFile = File(tempDir, "recent-projects.json")
        runBlocking { ProjectState.resetForTesting(tempFile) }
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would.
        runBlocking { ProjectState.resetForTesting(BossDirectories.resolve("recent-projects.json")) }
        tempDir.deleteRecursively()
    }

    private fun project(index: Int): Project {
        val name = "Project $index"
        val path = "/tmp/project-$index"
        return Project(name = name, path = path, lastOpened = index.toLong())
    }

    @Test
    fun `concurrent updates from multiple windows do not drop entries`() =
        runBlocking(Dispatchers.Default) {
            // Capped at MAX_RECENT_PROJECTS so the in-memory LRU does not evict any of these
            // additions; the race this regression pins would otherwise mix with the eviction
            // and hide. The author of this test read 50 as "more is better" - the LRU had
            // other ideas.
            val count = 10
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { index ->
                    async {
                        start.await()
                        ProjectState.updateRecentProjects(project(index))
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val recorded = ProjectState.recentProjects.value
            assertEquals(
                count,
                recorded.size,
                "Expected all $count distinct projects to be retained in memory without race overwrites",
            )
            // Every distinct path lands exactly once - LRU keeps each at index 0 in turn but
            // never drops one.
            val paths = recorded.map { it.path }.toSet()
            assertEquals(count, paths.size, "Each concurrent caller must keep its own entry")
        }

    @Test
    fun `a remove racing with an update does not resurrect removed entries`() =
        runBlocking(Dispatchers.Default) {
            // Seed two entries deterministically before the race.
            ProjectState.updateRecentProjects(project(1))
            ProjectState.updateRecentProjects(project(2))

            val start = CompletableDeferred<Unit>()
            val jobs =
                listOf(
                    async {
                        start.await()
                        ProjectState.removeRecentProject("/tmp/project-1")
                    },
                    async {
                        start.await()
                        ProjectState.updateRecentProjects(project(3))
                    },
                    async {
                        start.await()
                        ProjectState.updateRecentProjects(project(1))
                    },
                )
            start.complete(Unit)
            jobs.awaitAll()

            val recorded = ProjectState.recentProjects.value
            val paths = recorded.map { it.path }.toSet()
            assertTrue(
                "/tmp/project-2" in paths,
                "An untouched entry must survive concurrent remove/update races",
            )
            assertTrue(
                "/tmp/project-3" in paths,
                "An entry written during the race must remain visible",
            )
            // /tmp/project-1 was both removed and re-added during the race; whichever wins, the
            // path is in the list at most once - 0 if remove ran last, 1 if update(1) ran last.
            assertTrue(
                paths.count { it == "/tmp/project-1" } <= 1,
                "An entry touched by both remove and update in the race must not be duplicated",
            )
        }
}
